package com.kainext.duo.tools.reflection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs an intent, and when an attempt fails, asks a model how to fix it before trying again —
 * at most [maxAttempts] times, under a hard overall deadline.
 *
 * Reflection runs only after a failure: the first attempt always passes `AttemptInput(command =
 * null)`, so the caller's `attempt` lambda produces and executes the command itself. This avoids
 * an LLM round-trip on every run, including the majority that succeed on the first try.
 *
 * Retry priority (earlier rules win):
 * 1. Cancellation — rethrown, never converted to a result.
 * 2. [totalTimeoutMs] / [maxAttempts] — hard budgets.
 * 3. SIGINT (exit 130) — always stops the loop.
 * 4. [ExitCodes.isRetryable] (consulted before [reflect] when [letReflectionOverrideExitCodes] is
 *    false) — ends hopeless runs without spending a reflection call.
 * 5. [reflect] — authoritative for everything that reaches it.
 *
 * @param maxAttempts total attempts; values below 1 are treated as 1.
 * @param attemptTimeoutMs budget for a single `attempt` call.
 * @param totalTimeoutMs budget for the whole run.
 * @param letReflectionOverrideExitCodes see the priority list above; `false` by default.
 * @param reflect the model call that proposes a correction, given the goal and the full history.
 */
class SelfCorrectingExecutor(
    private val maxAttempts: Int = 3,
    private val attemptTimeoutMs: Long = 60_000,
    private val totalTimeoutMs: Long = 180_000,
    private val letReflectionOverrideExitCodes: Boolean = false,
    private val reflect: suspend (ReflectionRequest) -> ReflectionResponse,
) {

    /** How many attempts this executor will make; at least 1 even if constructed with 0. */
    val attemptBudget: Int get() = maxAttempts.coerceAtLeast(1)

    /**
     * Runs [intent], retrying with model-guided corrections until it succeeds or the budget is
     * spent.
     *
     * @param intent the goal in natural language, forwarded to both `attempt` and [reflect] so
     *   the model always sees what was wanted, not merely which command failed.
     * @param attempt the real executor. Its [AttemptInput.command] is `null` on the first attempt
     *   and `null` again whenever reflection returned no concrete command, meaning "re-plan from
     *   the intent, guided by `previousFailure`".
     * @return a [ReflectionResult] with the outcome and the complete attempt history. Ordinary
     *   failures — including timeouts, reported via `timedOut = true` — never throw.
     * @throws CancellationException if the calling coroutine is cancelled.
     */
    suspend fun run(
        intent: String,
        attempt: suspend (AttemptInput) -> ExecutionOutcome,
    ): ReflectionResult {
        val limit = attemptBudget
        val records = mutableListOf<AttemptRecord>()
        var nextCommand: String? = null
        var lastReasoning: String? = null
        var stopReason: String? = null
        var succeeded = false

        // `null` from withTimeoutOrNull means the overall deadline fired; a `true` result means
        // the loop below reached a decision of its own.
        val completed: Boolean? = withTimeoutOrNull(totalTimeoutMs) {
            var running = true
            while (running) {
                val attemptNumber = records.size + 1
                val input = AttemptInput(
                    command = nextCommand,
                    intent = intent,
                    attemptNumber = attemptNumber,
                    previousFailure = records.lastOrNull()?.let { previous ->
                        buildPreviousFailure(previous, lastReasoning)
                    },
                )

                val outcome = runAttempt(attempt, input)
                records += AttemptRecord(
                    attemptNumber = attemptNumber,
                    command = input.command,
                    outcome = outcome,
                    explanation = outcome.failureReason(),
                )

                if (outcome.success) {
                    succeeded = true
                    running = false
                } else if (attemptNumber >= limit) {
                    stopReason = "Gave up after $limit attempt(s): the attempt budget is spent."
                    running = false
                } else if (outcome.exitCode == ExitCodes.SIGINT) {
                    // The user cancelled; a correction cannot make the intent wanted again.
                    stopReason = "Cancelled by the user (exit code 130); not retried."
                    running = false
                } else if (
                    !letReflectionOverrideExitCodes && !ExitCodes.isRetryable(outcome.exitCode)
                ) {
                    // Cheap gate: never spend a reflection call on a code that changing the
                    // command cannot fix, unless the caller asked the model to have a say.
                    stopReason = "Exit code ${outcome.exitCode} is not retryable: " +
                        ExitCodes.explain(outcome.exitCode)
                    running = false
                } else {
                    val request = ReflectionRequest(
                        intent = intent,
                        attempts = records.toList(),
                        maxAttempts = limit,
                    )
                    val response = try {
                        reflect(request)
                    } catch (e: CancellationException) {
                        // User stop or dead scope: propagate instead of inventing a give-up.
                        throw e
                    } catch (e: Throwable) {
                        stopReason = "Reflection failed (" +
                            (e::class.simpleName ?: "error") + ": " +
                            (e.message ?: "no message") + ")"
                        running = false
                        null
                    }

                    if (response != null) {
                        if (!response.shouldRetry) {
                            stopReason = response.giveUpReason?.takeIf { it.isNotBlank() }
                                ?: "The model judged that another attempt would not help."
                            running = false
                        } else {
                            nextCommand = response.correctedCommand?.takeIf { it.isNotBlank() }
                            lastReasoning = response.reasoning.takeIf { it.isNotBlank() }
                        }
                    }
                }
            }
            true
        }

        val finalOutcome = records.lastOrNull()?.outcome
        return ReflectionResult(
            success = succeeded,
            attempts = records.toList(),
            finalOutcome = finalOutcome,
            giveUpReason = when {
                succeeded -> null
                completed == null -> "Ran out of the overall time budget (${totalTimeoutMs} ms)."
                else -> stopReason
            },
            timedOut = completed == null,
        )
    }

    /**
     * Executes one attempt under [attemptTimeoutMs] and normalises every failure mode into an
     * [ExecutionOutcome].
     *
     * Thrown exceptions and per-attempt timeouts become outcomes rather than propagating, because
     * the reflection prompt must be able to *see* them: "the backend was unavailable" is exactly
     * the fact that tells the model to pick a different backend. Only cancellation escapes — and
     * the overall-deadline cancellation never arrives here as an exception, since
     * `withTimeoutOrNull` reports its own timeout as `null`.
     *
     * [ExecutionOutcome.durationMs] is left to the `attempt` lambda to fill in: measuring elapsed
     * time portably in `commonMain` would need either an `expect/actual` clock (an extra platform
     * source file this module deliberately avoids) or a wall clock that can jump when the device
     * syncs time. For a timeout the known budget is used instead, which is exact enough.
     */
    private suspend fun runAttempt(
        attempt: suspend (AttemptInput) -> ExecutionOutcome,
        input: AttemptInput,
    ): ExecutionOutcome {
        val outcome = try {
            withTimeoutOrNull(attemptTimeoutMs) { attempt(input) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ExecutionOutcome.failed(
                errorMessage = "attempt ${input.attemptNumber} raised " +
                    (e::class.simpleName ?: "an exception") + ": " +
                    (e.message ?: "no message"),
            )
        }

        return outcome ?: ExecutionOutcome.failed(
            errorMessage = "attempt ${input.attemptNumber} timed out after $attemptTimeoutMs ms",
            exitCode = ExitCodes.TIMEOUT_OR_SPAWN_FAILURE,
            durationMs = attemptTimeoutMs,
        )
    }

    /**
     * Context handed to the next attempt: the previous failure digest plus the model's own
     * reasoning, so a re-planning `attempt` knows which fixes were already ruled out.
     */
    private fun buildPreviousFailure(previous: AttemptRecord, reasoning: String?): String =
        if (reasoning == null) {
            previous.explanation
        } else {
            previous.explanation + "\nprevious correction reasoning: " + reasoning
        }
}
