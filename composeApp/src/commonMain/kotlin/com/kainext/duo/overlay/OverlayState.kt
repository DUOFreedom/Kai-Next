package com.kainext.duo.overlay

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the floating window is currently doing, in the coarsest terms the UI needs.
 *
 * Kept separate from the free-form [OverlayState.statusText] so the window can colour-code the
 * state without parsing English prose, and so a future notification mirror can pick an icon.
 */
enum class OverlayPhase {
    /** Window is up but no task is running. */
    IDLE,

    /** A command or screen action is in flight. */
    WORKING,

    /** Waiting on the user — for example an approval or a follow-up message. */
    WAITING,

    /** The task finished successfully. Terminal until the next task. */
    DONE,

    /** The task failed or was stopped. Terminal until the next task. */
    FAILED,
}

/**
 * What a log line means, which decides how the window renders it.
 *
 * The window used to colour every line the same way, so the model's own running commentary was
 * indistinguishable from a tool name — the one line a user actually wants to read (what the agent
 * is about to do next) was buried between two tool lines.
 */
enum class OverlayLineKind {
    /** The model's own step narration: "Opened QQ, next tapping the search box". */
    NARRATION,

    /** A tool invocation line. */
    TOOL,

    /** A tool result line. */
    RESULT,

    /** Anything the harness says: queued messages, stop requests, task boundaries. */
    SYSTEM,
}

/**
 * One line of the window's activity log.
 *
 * [isError] exists so the window can render a failed step in red without inspecting the text:
 * the agent's own messages are prose, and pattern-matching on them would be brittle.
 */
@Immutable
data class OverlayLogLine(
    val text: String,
    val isError: Boolean = false,
    val timestampMs: Long = 0L,
    val kind: OverlayLineKind = OverlayLineKind.SYSTEM,
)

/** Lifecycle of one step of a team-mode plan, as far as the window needs to know. */
enum class OverlayStepStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    SKIPPED,
}

/** One planned step of a team-mode run, shown as a checklist under the narration. */
@Immutable
data class OverlayPlanStep(
    val id: String,
    val text: String,
    val status: OverlayStepStatus = OverlayStepStatus.PENDING,
)

/**
 * Everything the floating window renders. Published as one immutable value so the window never
 * observes a half-updated state — it is recomposed from a single snapshot.
 */
@Immutable
data class OverlayState(
    val isVisible: Boolean = false,
    val phase: OverlayPhase = OverlayPhase.IDLE,
    /** Short description of the current step, e.g. "Tapping 'Sign in'". */
    val statusText: String = "",
    /** Recent activity, oldest first. Bounded by [OverlayController.MAX_LOG_LINES]. */
    val log: List<OverlayLogLine> = emptyList(),
    /** True while a task is running; gates the stop button. */
    val isRunning: Boolean = false,
    /** The model's latest narration line, kept separately so it can be pinned above the log. */
    val narration: String = "",
    /** Team-mode plan. Empty outside team mode, which is what hides the checklist. */
    val plan: List<OverlayPlanStep> = emptyList(),
    /** True when the current run is orchestrated by the planner/worker swarm. */
    val isTeamMode: Boolean = false,
) {
    companion object {
        val Hidden = OverlayState()
    }
}

/**
 * The channel between the agent loop and the floating window.
 *
 * Why this exists as a separate object rather than living on the chat view model: the window is
 * hosted by an Android `Service`, which has no reference to the Compose tree. A single shared
 * controller gives both sides a plain `StateFlow` to read and write, with no coupling to either
 * platform type. It is deliberately process-wide — there is exactly one floating window.
 */
object OverlayController {

    /** How many activity lines are kept. The window shows the last few; older ones scroll away. */
    const val MAX_LOG_LINES = 40

    /** Longest narration line kept. One sentence per step; longer text belongs in the chat. */
    const val MAX_NARRATION_CHARS = 240

    private val _state = MutableStateFlow(OverlayState.Hidden)

    /** The state the window and the chat UI both observe. */
    val state: StateFlow<OverlayState> = _state.asStateFlow()

    /** Messages the user typed into the window, for the agent loop to consume. */
    private val _userMessages = MutableStateFlow<List<String>>(emptyList())

    /** Consumed by the chat layer; drained after each read so nothing is handled twice. */
    val userMessages: StateFlow<List<String>> = _userMessages.asStateFlow()

    private val _stopRequests = MutableStateFlow(0)

    /** Increments on every stop request. The agent loop watches this to cancel its job. */
    val stopRequests: StateFlow<Int> = _stopRequests.asStateFlow()

    /** Shows the window and resets it to a fresh idle state. */
    fun show() {
        _state.update { current -> current.copy(isVisible = true) }
    }

    /** Hides the window. Does not stop a running task; call [requestStop] for that. */
    fun hide() {
        _state.update { current -> current.copy(isVisible = false) }
    }

    /**
     * Announces that a task started. Clears the previous run's log so the window never mixes
     * two runs — a stale "Done" line above a fresh "Working" one reads as a contradiction.
     */
    fun startTask(statusText: String, teamMode: Boolean = false) {
        _state.update {
            it.copy(
                isVisible = true,
                phase = OverlayPhase.WORKING,
                statusText = statusText,
                log = emptyList(),
                narration = "",
                plan = emptyList(),
                isTeamMode = teamMode,
                isRunning = true,
            )
        }
    }

    /** Replaces the one-line status, e.g. when moving from one step to the next. */
    fun setStatus(statusText: String, phase: OverlayPhase = OverlayPhase.WORKING) {
        _state.update { it.copy(statusText = statusText, phase = phase) }
    }

    /**
     * Records the model's narration for the step it is about to run.
     *
     * This is the line the user reads to follow along ("opened QQ, next typing the search term"),
     * so it is published both as the pinned [OverlayState.narration] and as a log line — the pin
     * survives the log scrolling, the log line keeps the order of events.
     */
    fun narrate(text: String) {
        val trimmed = text.trim().take(MAX_NARRATION_CHARS)
        if (trimmed.isEmpty()) return
        appendLine(trimmed, isError = false, kind = OverlayLineKind.NARRATION)
        _state.update { it.copy(narration = trimmed) }
    }

    /** Appends an activity line, trimming the oldest entries past [MAX_LOG_LINES]. */
    fun log(text: String, isError: Boolean = false, timestampMs: Long = 0L) {
        appendLine(text, isError, OverlayLineKind.SYSTEM, timestampMs)
    }

    /** Appends a tool invocation line ("→ android_ui"). */
    fun logTool(text: String) {
        appendLine(text, isError = false, kind = OverlayLineKind.TOOL)
    }

    /** Appends a tool result line ("← android_ui (failed)"). */
    fun logResult(text: String, isError: Boolean = false) {
        appendLine(text, isError = isError, kind = OverlayLineKind.RESULT)
    }

    private fun appendLine(
        text: String,
        isError: Boolean,
        kind: OverlayLineKind,
        timestampMs: Long = 0L,
    ) {
        if (text.isBlank()) return
        _state.update { current ->
            val line = OverlayLogLine(text.trim(), isError, timestampMs, kind)
            val next = current.log + line
            current.copy(log = if (next.size > MAX_LOG_LINES) next.takeLast(MAX_LOG_LINES) else next)
        }
    }

    /** Replaces the team-mode plan. Passing an empty list hides the checklist. */
    fun setPlan(steps: List<OverlayPlanStep>) {
        _state.update { it.copy(plan = steps) }
    }

    /** Marks one plan step's new status without rebuilding the whole list. */
    fun setStepStatus(stepId: String, status: OverlayStepStatus) {
        _state.update { current ->
            if (current.plan.none { it.id == stepId }) return@update current
            current.copy(
                plan = current.plan.map { if (it.id == stepId) it.copy(status = status) else it },
            )
        }
    }

    /** Turns the team-mode flag on or off. Used when the run starts and when the user toggles it. */
    fun setTeamMode(enabled: Boolean) {
        _state.update { it.copy(isTeamMode = enabled) }
    }

    /** Marks the task finished and releases the stop button. */
    fun finishTask(success: Boolean, statusText: String) {
        _state.update {
            it.copy(
                phase = if (success) OverlayPhase.DONE else OverlayPhase.FAILED,
                statusText = statusText,
                isRunning = false,
            )
        }
    }

    /** Marks the task as waiting on the user, keeping it running. */
    fun waitForUser(statusText: String) {
        _state.update { it.copy(phase = OverlayPhase.WAITING, statusText = statusText) }
    }

    /** Records a message typed into the window. */
    fun submitUserMessage(message: String) {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return
        _userMessages.update { it + trimmed }
        log("You: $trimmed")
    }

    /** Returns the queued messages and clears them, so each is delivered exactly once. */
    fun drainUserMessages(): List<String> {
        val pending = _userMessages.value
        if (pending.isNotEmpty()) _userMessages.value = emptyList()
        return pending
    }

    /** Asks the agent loop to stop. Idempotent: repeated taps just bump the counter. */
    fun requestStop() {
        _stopRequests.update { it + 1 }
        _state.update { it.copy(statusText = "Stopping…", phase = OverlayPhase.WAITING) }
    }

    /** Resets everything. Called when the window is dismissed and no task is running. */
    fun reset() {
        _state.value = OverlayState.Hidden
        _userMessages.value = emptyList()
    }
}
