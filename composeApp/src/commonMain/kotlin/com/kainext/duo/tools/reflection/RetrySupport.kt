package com.kainext.duo.tools.reflection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Describes a single attempt that ran past its own budget, without being a cancellation.
 *
 * `AttemptTimeoutException` is a plain `Exception`, not a `CancellationException` — mixing them
 * would push callers towards "retry on cancellation", which this module exists to prevent.
 */
class AttemptTimeoutException(
    val attempt: Int,
    val timeoutMs: Long,
) : Exception("attempt $attempt timed out after $timeoutMs ms")

/**
 * Generic retry template: runs [block] up to [maxAttempts] times under a per-attempt timeout and
 * an overall deadline, returning the first successful value or `null` once the budget is spent.
 *
 * A per-attempt limit bounds a single hung call, but on its own it is not enough: three attempts
 * that each finish just under the limit still stall the agent. The total budget is the promise
 * made to the user — whichever limit is reached first wins.
 *
 * `CancellationException` is rethrown before anything else: swallowing it would make the caller
 * believe work finished when the surrounding scope is already dead. `withTimeoutOrNull` reports
 * its own timeout as `null` while still propagating outer cancellation as an exception.
 *
 * @param maxAttempts total number of tries; values below 1 are treated as 1.
 * @param attemptTimeoutMs budget for one invocation of [block].
 * @param totalTimeoutMs budget for all attempts combined.
 * @param shouldRetry consulted with the exception thrown by a failed attempt (or with
 *   [AttemptTimeoutException] when an attempt timed out). Returning `false` stops immediately.
 * @param block the work; receives the 1-based attempt number. It should not return `null` —
 *   `null` is this function's "gave up" signal, so wrap a legitimately nullable value in a
 *   holder before returning it.
 * @return the first successful value, or `null` if every attempt failed or the overall deadline
 *   passed. Those two cases are deliberately conflated; use [SelfCorrectingExecutor] when the
 *   caller must tell them apart.
 */
suspend fun <T> retryWithTimeout(
    maxAttempts: Int = 3,
    attemptTimeoutMs: Long = 30_000,
    totalTimeoutMs: Long = 90_000,
    shouldRetry: (Throwable) -> Boolean = { true },
    block: suspend (attempt: Int) -> T,
): T? {
    val attempts = maxAttempts.coerceAtLeast(1)
    var value: T? = null
    var succeeded = false

    withTimeoutOrNull(totalTimeoutMs) {
        var attempt = 1
        while (attempt <= attempts) {
            val step: AttemptStep<T> = try {
                val produced = withTimeoutOrNull(attemptTimeoutMs) { block(attempt) }
                if (produced == null) {
                    AttemptStep(AttemptTimeoutException(attempt, attemptTimeoutMs))
                } else {
                    AttemptStep(value = produced)
                }
            } catch (e: CancellationException) {
                // Outer cancellation, not a failure: never retry, never swallow.
                throw e
            } catch (e: Throwable) {
                AttemptStep(e)
            }

            if (step.error == null) {
                value = step.value
                succeeded = true
                return@withTimeoutOrNull
            }
            if (!shouldRetry(step.error)) return@withTimeoutOrNull
            attempt++
        }
    }

    return if (succeeded) value else null
}

/**
 * One attempt's result: either a value or an error, never both.
 *
 * A plain class rather than a sealed hierarchy because the loop only needs to branch on two
 * nullable fields; a `sealed` carrier would force an unchecked cast back to `T` on the success
 * path for no benefit.
 */
private class AttemptStep<T>(
    val error: Throwable? = null,
    val value: T? = null,
)
