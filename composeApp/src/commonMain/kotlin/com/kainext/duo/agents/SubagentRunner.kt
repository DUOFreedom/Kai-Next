package com.kainext.duo.agents

import com.kainext.duo.network.tools.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** What a finished [SubagentRunner] hands back to its caller. Only the answer leaves the child. */
data class SubagentResult(
    val answer: String,
    val steps: Int,
    val toolCalls: Int,
    val error: String? = null,
    val durationMs: Long = 0L,
) {
    val isSuccess: Boolean get() = error == null
}

/**
 * A throwaway agent that works on one task and reports a conclusion.
 *
 * The distinction from the main loop is what it does *not* share: its own work log instead of the
 * conversation history, and a fixed tool family instead of everything the app can do. That keeps a
 * side investigation ("check whether the file exists, then tell me what it contains") from leaving
 * ten tool rows in the user's transcript and from silently reaching for a tool it should not use.
 *
 * Completion is a plain `(prompt) -> String` call so this class stays provider-agnostic and testable
 * without a network; the caller decides which model answers.
 */
@OptIn(ExperimentalTime::class)
class SubagentRunner(
    private val tools: List<Tool>,
    private val executeTool: suspend (name: String, arguments: String) -> String,
    private val complete: suspend (prompt: String) -> String,
) {

    /**
     * Runs the child to completion, or to [maxSteps] tool calls, whichever comes first.
     *
     * Never throws for a model or tool failure: a failed branch is reported as
     * [SubagentResult.error] so the parent agent can decide what to do next instead of unwinding
     * the whole turn. Cancellation is the one exception — it must propagate.
     */
    suspend fun run(task: String, maxSteps: Int = DEFAULT_MAX_STEPS): SubagentResult {
        val startedAt = Clock.System.now().toEpochMilliseconds()
        val trimmedTask = task.trim()
        if (trimmedTask.isEmpty()) {
            return SubagentResult(answer = "", steps = 0, toolCalls = 0, error = "task is empty")
        }
        val steps = clampSteps(maxSteps)
        val log = mutableListOf<String>()
        var toolCalls = 0
        var lastSignature: String? = null
        var repeats = 0

        while (toolCalls < steps) {
            val reply = try {
                complete(buildPrompt(trimmedTask, log))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return failure(e, toolCalls, startedAt)
            }

            val body = extractJsonObject(reply)
            if (body == null) {
                // A model that answers in prose instead of JSON has still answered the task; failing
                // it would throw away a usable result over a formatting mistake.
                return finish(reply, toolCalls, startedAt)
            }

            val final = body["final"]?.jsonPrimitive?.contentOrNull?.trim()
            if (!final.isNullOrEmpty()) {
                return finish(final, toolCalls, startedAt)
            }

            val toolName = body["tool"]?.jsonPrimitive?.contentOrNull?.trim()
            if (toolName.isNullOrEmpty() || toolName == "null") {
                return finish(reply, toolCalls, startedAt)
            }

            val tool = tools.firstOrNull { it.schema.name == toolName }
                ?: return SubagentResult(
                    answer = "",
                    steps = toolCalls,
                    toolCalls = toolCalls,
                    error = "unknown tool '$toolName'. Available: ${tools.joinToString { it.schema.name }}",
                    durationMs = Clock.System.now().toEpochMilliseconds() - startedAt,
                )

            val arguments = body["arguments"]?.toString() ?: "{}"
            val signature = "$toolName:$arguments"
            repeats = if (signature == lastSignature) repeats + 1 else 0
            lastSignature = signature
            if (repeats >= MAX_REPEATS) {
                // Same call, same arguments, again: no new information can arrive, so stop and
                // report rather than burn the remaining budget on a loop.
                return SubagentResult(
                    answer = "",
                    steps = toolCalls,
                    toolCalls = toolCalls,
                    error = "repeated '$toolName' with identical arguments ${repeats + 1} times",
                    durationMs = Clock.System.now().toEpochMilliseconds() - startedAt,
                )
            }

            val observation = try {
                executeTool(toolName, arguments)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return failure(e, toolCalls + 1, startedAt)
            }
            toolCalls++
            log += "Step $toolCalls: $toolName(${arguments.take(MAX_ARGUMENT_CHARS)}) -> ${observation.take(MAX_OBSERVATION_CHARS)}"

            // Trim from the oldest end: the newest observations are what the next decision needs,
            // and an unbounded log would eventually crowd out the tool catalogue itself.
            while (log.sumOf { it.length } > MAX_LOG_CHARS && log.size > 1) {
                log.removeAt(0)
            }
        }

        // Budget exhausted with the task unfinished. Ask once more for a best-effort answer so the
        // parent gets something usable instead of a bare "out of steps".
        val summary = try {
            complete(buildPrompt(trimmedTask, log, outOfSteps = true))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return failure(e, toolCalls, startedAt)
        }
        return finish(summary, toolCalls, startedAt)
    }

    private fun buildPrompt(
        task: String,
        log: List<String>,
        outOfSteps: Boolean = false,
    ): String = buildString {
        appendLine("You are a subagent. Work on one task and report a conclusion.")
        appendLine("You have your own scratch log; the user's conversation is not visible to you.")
        appendLine()
        appendLine("## Task")
        appendLine(task)
        appendLine()
        appendLine("## Available tools")
        tools.forEach { appendLine(toolLine(it)) }
        appendLine()
        appendLine("## Work log")
        if (log.isEmpty()) {
            appendLine("(nothing yet)")
        } else {
            log.forEach { appendLine(it) }
        }
        appendLine()
        if (outOfSteps) {
            appendLine("## Step budget exhausted")
            appendLine("You cannot call any more tools. Summarise what you found and reply with JSON only:")
            appendLine("""{"final": "your conclusion"}""")
        } else {
            appendLine("## Output")
            appendLine("Reply with JSON only, one of:")
            appendLine("""{"tool": "tool_name", "arguments": {...}}""")
            appendLine("""{"final": "your conclusion"}""")
            appendLine("Use {\"final\": ...} as soon as you can answer; do not call tools you do not need.")
        }
    }

    private fun toolLine(tool: Tool): String {
        val params = tool.schema.parameters.entries.joinToString(", ") { (name, schema) ->
            val optional = if (schema.required) "" else "?"
            "$name:${schema.type}$optional"
        }
        return "- ${tool.schema.name}($params): ${tool.schema.description}"
    }

    private fun finish(answer: String, toolCalls: Int, startedAt: Long): SubagentResult {
        val text = answer.trim()
        return SubagentResult(
            answer = if (text.length > MAX_ANSWER_CHARS) text.take(MAX_ANSWER_CHARS) + "…" else text,
            steps = toolCalls,
            toolCalls = toolCalls,
            error = if (text.isEmpty()) "subagent produced no answer" else null,
            durationMs = Clock.System.now().toEpochMilliseconds() - startedAt,
        )
    }

    private fun failure(e: Throwable, toolCalls: Int, startedAt: Long) = SubagentResult(
        answer = "",
        steps = toolCalls,
        toolCalls = toolCalls,
        error = e.message ?: e::class.simpleName ?: "subagent failed",
        durationMs = Clock.System.now().toEpochMilliseconds() - startedAt,
    )

    /**
     * Pulls the first balanced JSON object out of a reply.
     *
     * Models wrap JSON in fences or add a sentence before it, so scanning for balanced braces beats
     * a regex that would choke on braces inside string literals.
     */
    private fun extractJsonObject(raw: String): JsonObject? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            when {
                escaped -> escaped = false

                c == '\\' && inString -> escaped = true

                c == '"' -> inString = !inString

                inString -> Unit

                c == '{' -> depth++

                c == '}' -> {
                    depth--
                    if (depth == 0) {
                        return runCatching {
                            json.parseToJsonElement(raw.substring(start, i + 1)).jsonObject
                        }.getOrNull()
                    }
                }
            }
        }
        return null
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 6

        const val MIN_MAX_STEPS = 1

        const val MAX_MAX_STEPS = 12

        private const val MAX_OBSERVATION_CHARS = 2_000

        private const val MAX_ARGUMENT_CHARS = 400

        private const val MAX_LOG_CHARS = 8_000

        private const val MAX_ANSWER_CHARS = 4_000

        private const val MAX_REPEATS = 3

        fun clampSteps(steps: Int?): Int = (steps ?: DEFAULT_MAX_STEPS).coerceIn(MIN_MAX_STEPS, MAX_MAX_STEPS)

        private val json = Json { ignoreUnknownKeys = true }
    }
}
