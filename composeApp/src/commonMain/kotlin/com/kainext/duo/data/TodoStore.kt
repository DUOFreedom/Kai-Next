package com.kainext.duo.data

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Where one to-do stands. Mirrors the three states an agent actually uses. */
@Serializable
enum class TodoStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
}

/**
 * One entry of the agent's task list.
 *
 * [id] is stable across rewrites so the UI can animate a row changing state instead of rebuilding
 * the whole list; the model addresses rows by index or by id, and an id it invents is kept as-is.
 */
@Immutable
@Serializable
data class TodoItem(
    val id: String,
    val text: String,
    val status: TodoStatus = TodoStatus.PENDING,
)

/**
 * The agent's visible task list, one per conversation.
 *
 * Why a list rather than a step counter: a long turn ("book the train, then add it to the calendar,
 * then message my wife") is only followable if the user can see what is left. The model rewrites the
 * whole list through the `todo_write` tool, which is the same protocol the file-editing tools use —
 * a full replacement is idempotent and cannot drift out of sync the way incremental patches do.
 *
 * Persistence is a single JSON map keyed by conversation id in [AppSettings], so a list survives the
 * app being killed mid-run, and a conversation that is deleted simply stops being read.
 */
@OptIn(ExperimentalUuidApi::class)
class TodoStore(private val appSettings: AppSettings) {

    private val json = Json { ignoreUnknownKeys = true }

    private val serializer = MapSerializer(String.serializer(), ListSerializer(TodoItem.serializer()))

    private val _todos = MutableStateFlow(load())

    /** conversation id (empty string for "no conversation yet") to its list. */
    val todos: StateFlow<Map<String, List<TodoItem>>> = _todos.asStateFlow()

    fun forConversation(conversationId: String?): List<TodoItem> = _todos.value[conversationId.orEmpty()].orEmpty()

    /**
     * Replaces the list for one conversation and persists it.
     *
     * A blank result clears the entry rather than storing an empty list, so conversations that never
     * used the tool leave nothing behind.
     */
    fun replace(conversationId: String?, items: List<TodoItem>) {
        val key = conversationId.orEmpty()
        val next = _todos.value.toMutableMap()
        if (items.isEmpty()) next.remove(key) else next[key] = items
        _todos.value = next
        persist(next)
    }

    /** Flips one row's status. Used by the chat card so the user can tick an item off by hand. */
    fun setStatus(conversationId: String?, itemId: String, status: TodoStatus) {
        val current = forConversation(conversationId)
        if (current.none { it.id == itemId }) return
        replace(conversationId, current.map { if (it.id == itemId) it.copy(status = status) else it })
    }

    /** Drops one conversation's list — used when the conversation is deleted. */
    fun clear(conversationId: String?) {
        replace(conversationId, emptyList())
    }

    private fun load(): Map<String, List<TodoItem>> {
        val raw = appSettings.getTodosJson() ?: return emptyMap()
        return try {
            json.decodeFromString(serializer, raw)
        } catch (_: Exception) {
            // A corrupt blob must not take the chat screen down; the next write replaces it.
            emptyMap()
        }
    }

    private fun persist(map: Map<String, List<TodoItem>>) {
        appSettings.setTodosJson(json.encodeToString(serializer, map))
    }

    companion object {
        /** Cap on rows kept per conversation. More than this is a plan, not a task list. */
        const val MAX_ITEMS = 40

        /** Parses the tool's `status` argument, defaulting to PENDING for anything unexpected. */
        fun parseStatus(raw: String?): TodoStatus = when (raw?.trim()?.lowercase()) {
            "completed", "complete", "done" -> TodoStatus.COMPLETED
            "in_progress", "in-progress", "running", "active" -> TodoStatus.IN_PROGRESS
            else -> TodoStatus.PENDING
        }

        @OptIn(ExperimentalUuidApi::class)
        fun newId(): String = Uuid.random().toString()
    }
}
