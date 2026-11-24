package com.kainext.duo.data

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Lifecycle of a persisted goal. */
@Serializable
enum class AgentGoalStatus {
    /** The agent keeps working on its own until it reports success or gives up. */
    ACTIVE,

    /** Stopped by the user; resumable without losing the round count. */
    PAUSED,

    /** Reported as achieved. */
    COMPLETED,

    /** Reported as stuck, or the round budget ran out. */
    BLOCKED,
}

/**
 * A long-running objective the agent carries across turns.
 *
 * [rounds] counts autonomous continuation turns that have already run, so the UI can show
 * "round 3 of 10" and [GoalStore] can stop before the bill runs away. Keeping the count
 * persisted (rather than in memory) means a restart does not reset the budget.
 */
@Immutable
@Serializable
data class AgentGoal(
    val id: String,
    val objective: String,
    val status: AgentGoalStatus = AgentGoalStatus.ACTIVE,
    val rounds: Int = 0,
    val maxRounds: Int = GoalStore.DEFAULT_MAX_ROUNDS,
    val blockedReason: String? = null,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
) {
    val isActive: Boolean get() = status == AgentGoalStatus.ACTIVE
    val roundsLeft: Int get() = (maxRounds - rounds).coerceAtLeast(0)
}

/**
 * Persists at most one goal per conversation.
 *
 * Goals are keyed by conversation id for the same reason todos are: the agent may be running
 * one long job in each of several chats, and a global "current goal" would let them overwrite
 * each other. The empty-string key is used when no conversation exists yet, which keeps the
 * store usable from tests and previews.
 */
@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
class GoalStore(private val appSettings: AppSettings) {

    private val _goals = MutableStateFlow(load())

    /** Every goal, keyed by conversation id. */
    val goals: StateFlow<Map<String, AgentGoal>> = _goals.asStateFlow()

    /** The goal of [conversationId], or null when that chat has none. */
    fun forConversation(conversationId: String?): AgentGoal? = _goals.value[conversationId.orEmpty()]

    /**
     * Creates (or replaces) the goal of [conversationId] and marks it active.
     *
     * Replacing rather than refusing is deliberate: when the agent starts a second long job in
     * the same chat, the newest objective is the one the user just asked for, and keeping the
     * old one would make the agent continue work nobody is waiting for any more.
     */
    fun start(conversationId: String?, objective: String, maxRounds: Int? = null): AgentGoal {
        val now = Clock.System.now().toEpochMilliseconds()
        val trimmed = objective.trim().take(MAX_OBJECTIVE_CHARS)
        val goal = AgentGoal(
            id = Uuid.random().toString(),
            objective = trimmed,
            status = AgentGoalStatus.ACTIVE,
            rounds = 0,
            maxRounds = (maxRounds ?: appSettings.getGoalMaxRounds())
                .coerceIn(MIN_MAX_ROUNDS, MAX_MAX_ROUNDS),
            createdAtMs = now,
            updatedAtMs = now,
        )
        update(conversationId) { goal }
        return goal
    }

    /** Moves the goal to [status], recording why when it is blocked. */
    fun setStatus(conversationId: String?, status: AgentGoalStatus, blockedReason: String? = null): AgentGoal? {
        val key = conversationId.orEmpty()
        val current = _goals.value[key] ?: return null
        val updated = current.copy(
            status = status,
            blockedReason = blockedReason?.trim()?.take(MAX_BLOCKED_REASON_CHARS) ?: blockedReason,
            updatedAtMs = Clock.System.now().toEpochMilliseconds(),
        )
        update(conversationId) { updated }
        return updated
    }

    /**
     * Counts one autonomous continuation turn and returns the updated goal.
     *
     * Returns null when the goal is gone, so a caller that raced with a delete cannot loop on a
     * stale snapshot.
     */
    fun recordRound(conversationId: String?): AgentGoal? {
        val key = conversationId.orEmpty()
        val current = _goals.value[key] ?: return null
        val updated = current.copy(
            rounds = current.rounds + 1,
            updatedAtMs = Clock.System.now().toEpochMilliseconds(),
        )
        update(conversationId) { updated }
        return updated
    }

    fun clear(conversationId: String?) {
        val key = conversationId.orEmpty()
        if (!_goals.value.containsKey(key)) return
        val next = _goals.value - key
        _goals.value = next
        persist(next)
    }

    private fun update(conversationId: String?, transform: () -> AgentGoal) {
        val key = conversationId.orEmpty()
        val next = (_goals.value + (key to transform())).trimToBudget()
        _goals.value = next
        persist(next)
    }

    /** Keeps the JSON from growing without bound when many chats each started a goal. */
    private fun Map<String, AgentGoal>.trimToBudget(): Map<String, AgentGoal> {
        if (size <= MAX_CONVERSATIONS) return this
        return entries
            .sortedByDescending { it.value.updatedAtMs }
            .take(MAX_CONVERSATIONS)
            .associate { it.key to it.value }
    }

    private fun load(): Map<String, AgentGoal> {
        val raw = appSettings.getAgentGoalsJson() ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()
        return try {
            json.decodeFromString(goalsSerializer, raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun persist(goals: Map<String, AgentGoal>) {
        try {
            appSettings.setAgentGoalsJson(json.encodeToString(goalsSerializer, goals))
        } catch (_: Exception) {
            // Persisting is best effort: a goal that cannot be written still works this session.
        }
    }

    companion object {
        /** Upper bound on one objective string; longer text is truncated, not rejected. */
        const val MAX_OBJECTIVE_CHARS = 600

        const val MAX_BLOCKED_REASON_CHARS = 300

        const val DEFAULT_MAX_ROUNDS = 10

        const val MIN_MAX_ROUNDS = 1

        const val MAX_MAX_ROUNDS = 50

        /** Chats that keep a goal; oldest are dropped so the JSON stays small. */
        const val MAX_CONVERSATIONS = 20

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private val goalsSerializer = MapSerializer(String.serializer(), AgentGoal.serializer())
    }
}
