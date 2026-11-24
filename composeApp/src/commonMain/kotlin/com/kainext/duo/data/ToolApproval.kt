package com.kainext.duo.data

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * How much damage a tool can do if the model gets it wrong.
 *
 * The agent runs unattended for many steps, so the useful distinction is not "which tool" but
 * "which tool can lose the user's data or spend their money". Anything that only reads is LOW;
 * anything that mutates the device, sends a message on the user's behalf, or runs an arbitrary
 * command is HIGH and is the only class a "ask only for risky tools" policy gates.
 */
enum class ToolRisk {
    /** Reads state: search, list, read, fetch, clock. Never changes anything. */
    LOW,

    /** Writes local app state that the user can see and undo: memory, todos, drafts. */
    MEDIUM,

    /** Irreversible or externally visible: shell, device control, SMS, email, alarms. */
    HIGH,
}

/** Which tools pause for confirmation before running. */
enum class ToolApprovalPolicy {
    /** Ask before every single tool call. Noisiest, safest. */
    ALWAYS,

    /** Ask only for [ToolRisk.HIGH] tools — the default. */
    HIGH_RISK,

    /** Never ask. For users who want the agent to run hands-off. */
    NEVER,
}

/** One tool call waiting on the user's yes/no. */
@Immutable
data class ToolApprovalRequest(
    val id: String,
    /** Tool id as the model called it, e.g. `execute_shell_command`. */
    val toolId: String,
    /** Human-readable tool name for the dialog title. */
    val displayName: String,
    /** Raw JSON arguments, shown so the user can see the actual command/path before allowing. */
    val arguments: String,
    val risk: ToolRisk,
) {
    /** Arguments can be long; the dialog shows a bounded preview. */
    val argumentsPreview: String
        get() = if (arguments.length <= MAX_PREVIEW_CHARS) arguments else arguments.take(MAX_PREVIEW_CHARS) + "…"

    companion object {
        const val MAX_PREVIEW_CHARS = 600
    }
}

/**
 * What the user decided.
 *
 * [alwaysAllow] is not a third answer but a modifier on "yes": it means the same tool should stop
 * asking for the rest of the install. Carrying it here rather than persisting inside the dialog
 * keeps the UI free of storage access and lets the executor decide what a durable grant means.
 */
data class ToolApprovalOutcome(
    val allowed: Boolean,
    val alwaysAllow: Boolean = false,
) {
    companion object {
        val DENIED = ToolApprovalOutcome(allowed = false)
        val ALLOWED = ToolApprovalOutcome(allowed = true)
    }
}

/**
 * The channel between the tool executor and the UI that asks the user.
 *
 * Modelled on [com.kainext.duo.overlay.OverlayController] and for the same reason: the executor
 * lives in the data layer with no Compose tree to show a dialog from, while the dialog has to be
 * hosted by the chat screen. A single shared object gives both sides a plain StateFlow plus one
 * suspend function that returns the answer, so the tool loop blocks exactly as long as the user
 * takes to tap — no polling, no timeout, no busy loop.
 */
object ToolApprovalController {

    private val _pending = MutableStateFlow<ToolApprovalRequest?>(null)

    /** The request the UI should render, or null when nothing is waiting. */
    val pending: StateFlow<ToolApprovalRequest?> = _pending.asStateFlow()

    /** Set while a request is in flight so the answer can be delivered to the waiting caller. */
    private var answer: CompletableDeferred<ToolApprovalOutcome>? = null

    /** Serialises concurrent requests into a queue of one dialog at a time. */
    private val gate = Mutex()

    /** Incremented by [cancelPending] so queued callers can tell the run was stopped. */
    private var generation = 0

    /**
     * Publishes [request] and suspends until the user answers.
     *
     * The executor runs a batch of tool calls in parallel, so two gated calls can arrive at the
     * same moment. They are serialised here rather than allowed to overwrite each other: replacing
     * the published request would silently deny everything but the last one, which reads as the
     * assistant refusing to work. A [Mutex] keeps one dialog on screen at a time and the rest queue.
     */
    suspend fun requestApproval(request: ToolApprovalRequest): ToolApprovalOutcome {
        val generationAtEntry = generation
        return gate.withLock {
            // A cancel that landed while this caller was queued applies to it too; otherwise the
            // next queued tool would pop a dialog for a run the user already stopped.
            if (generation != generationAtEntry) return@withLock ToolApprovalOutcome.DENIED

            val deferred = CompletableDeferred<ToolApprovalOutcome>()
            answer = deferred
            _pending.value = request
            try {
                deferred.await()
            } finally {
                if (answer === deferred) {
                    answer = null
                    _pending.update { if (it?.id == request.id) null else it }
                }
            }
        }
    }

    /** Delivers the user's decision. No-op when the request already resolved. */
    fun respond(approved: Boolean) {
        answer?.complete(ToolApprovalOutcome(allowed = approved))
    }

    /**
     * "Yes, and stop asking for this tool." The executor persists the grant, so a later call of the
     * same tool skips the dialog entirely; the settings screen can revoke it.
     */
    fun respondAlwaysAllow() {
        answer?.complete(ToolApprovalOutcome(allowed = true, alwaysAllow = true))
    }

    /** Drops any pending request and denies it — used when the run is cancelled. */
    fun cancelPending() {
        // Bumping the generation also denies callers still queued behind the current one.
        generation++
        answer?.complete(ToolApprovalOutcome.DENIED)
    }
}
