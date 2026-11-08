package com.kainext.duo.tools.reflection

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the executor hands to the `attempt` lambda for one try.
 *
 * [command] is `null` on the first attempt ("you produce the command from [intent] yourself").
 * On later attempts it carries the command reflection proposed, or stays `null` when reflection
 * had no concrete fix — in which case the lambda re-plans from [intent] taking [previousFailure]
 * into account.
 *
 * [previousFailure] is the failure digest of the preceding attempt, optionally followed by the
 * reflection reasoning. `null` on the first attempt.
 */
@Serializable
data class AttemptInput(
    val command: String?,
    val intent: String,
    val attemptNumber: Int,
    val previousFailure: String? = null,
)

/**
 * One recorded attempt, fed back to the model.
 *
 * [explanation] is the pre-computed, truncated [ExecutionOutcome.failureReason] rather than raw
 * streams, so prompts stay small and every attempt is described consistently.
 */
@Serializable
data class AttemptRecord(
    val attemptNumber: Int,
    val command: String?,
    val outcome: ExecutionOutcome,
    val explanation: String,
)

/**
 * Everything reflection needs: the goal, the full attempt history, and the hard attempt budget.
 */
@Serializable
data class ReflectionRequest(
    val intent: String,
    val attempts: List<AttemptRecord>,
    val maxAttempts: Int,
)

/**
 * The model's verdict on how to continue.
 *
 * Field names use snake_case because the prompt asks for snake_case JSON; keeping wire names on
 * the class keeps parsing and direct serialization from drifting.
 *
 * [shouldRetry] defaults to `true` so a truncated response that still parses does not silently
 * abort a recoverable run; [correctedCommand] defaults to `null` — a retry with no proposed fix
 * is only allowed as a re-plan (see [AttemptInput.command]), never as a blind re-run.
 */
@Serializable
data class ReflectionResponse(
    @SerialName("corrected_command") val correctedCommand: String? = null,
    @SerialName("reasoning") val reasoning: String = "",
    @SerialName("should_retry") val shouldRetry: Boolean = true,
    @SerialName("give_up_reason") val giveUpReason: String? = null,
)

/**
 * Outcome of a whole self-correcting run. [attempts] is the full history even on success.
 *
 * [timedOut] distinguishes "ran out of the overall time budget" from "used up the attempt budget":
 * both are failures, but the first may be retried later by the caller while the second should
 * change the approach.
 */
@Serializable
data class ReflectionResult(
    val success: Boolean,
    val attempts: List<AttemptRecord>,
    val finalOutcome: ExecutionOutcome?,
    val giveUpReason: String? = null,
    val timedOut: Boolean = false,
) {

    /** How many attempts were actually made. */
    val attemptCount: Int get() = attempts.size

    /** Compact English summary for a chat bubble or log line. The full history is in [attempts]. */
    fun toUserSummary(): String {
        if (success) {
            val n = attempts.size
            val suffix = if (n <= 1) "on the first attempt" else "after $n attempts"
            val command = attempts.lastOrNull()?.command
            return if (command.isNullOrBlank()) {
                "Succeeded $suffix."
            } else {
                "Succeeded $suffix: $command"
            }
        }

        val tried = when (attempts.size) {
            0 -> "No attempt could be made"
            1 -> "Tried once"
            else -> "Tried ${attempts.size} times"
        }
        val reason = giveUpReason?.takeIf { it.isNotBlank() }
        val lastError = finalOutcome?.shortError()

        return buildString {
            append(tried).append(if (attempts.size <= 1) ". It failed." else ". All failed.")
            if (timedOut) append(" Ran out of the overall time budget.")
            when {
                lastError != null -> append(" Last error: ").append(lastError)
                reason != null -> append(" Reason: ").append(reason)
            }
            if (lastError != null && reason != null) append(" (Reason: ").append(reason).append(')')
        }
    }
}
