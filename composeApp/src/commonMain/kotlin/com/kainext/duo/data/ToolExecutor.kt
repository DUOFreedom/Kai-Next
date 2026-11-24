package com.kainext.duo.data

import com.kainext.duo.getAvailableTools
import com.kainext.duo.getPlatformToolDefinitions
import com.kainext.duo.network.tools.Tool
import com.kainext.duo.smartTruncate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.getString
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

private const val MAX_TOOL_RESULT_LENGTH = 20_000

/** How long a tool call waits for the user to answer the confirmation dialog. */
private const val APPROVAL_TIMEOUT_SECONDS = 120

/**
 * `{"success": false, "error": message}`, built as JSON rather than by string interpolation —
 * error messages routinely contain quotes and newlines that would otherwise produce invalid JSON.
 */
internal fun toolErrorJson(message: String): String = buildJsonObject {
    put("success", false)
    put("error", message)
}.toString()

class ToolExecutor(
    private val appSettings: AppSettings? = null,
    private val toolsProvider: () -> List<Tool> = { getAvailableTools() },
) {

    private val jsonParser = Json { ignoreUnknownKeys = true }

    suspend fun executeTool(
        name: String,
        arguments: String,
        conversationId: String? = null,
    ): String {
        val tools = toolsProvider()
        val tool = tools.find { it.schema.name == name }
            ?: return toolErrorJson("Unknown tool: $name")

        val args = try {
            parseJsonToMap(arguments)
        } catch (e: Exception) {
            return toolErrorJson("Failed to parse arguments: ${e.message}")
        }

        if (!approvedToRun(name, arguments)) {
            return toolErrorJson("The user declined to run '$name'. Do not retry it; ask what to do instead.")
        }

        return try {
            val result = withTimeout(tool.timeout) {
                if (conversationId != null) {
                    withContext(ConversationIdElement(conversationId)) { tool.execute(args) }
                } else {
                    tool.execute(args)
                }
            }
            val resultString = when (result) {
                is Map<*, *> -> {
                    val jsonObject = JsonObject(
                        result.entries.associate { (k, v) ->
                            k.toString() to anyToJsonElement(v)
                        },
                    )
                    jsonParser.encodeToString(JsonElement.serializer(), jsonObject)
                }

                is String -> result

                else -> buildJsonObject { put("result", result.toString()) }.toString()
            }
            truncateResult(resultString)
        } catch (e: TimeoutCancellationException) {
            toolErrorJson("Tool '$name' timed out after ${tool.timeout}")
        } catch (e: CancellationException) {
            // Cooperative cancellation (user pressed stop) must propagate, not become a
            // fake tool result the loop would keep reasoning about.
            throw e
        } catch (e: Exception) {
            toolErrorJson("Tool execution failed: ${e.message}")
        }
    }

    private fun truncateResult(result: String): String = result.smartTruncate(MAX_TOOL_RESULT_LENGTH)

    /**
     * Blocks the tool call until the user allows it, when the configured policy says it must.
     *
     * Returns true immediately when nothing gates this tool, which is the common case: the default
     * policy only stops [ToolRisk.HIGH] tools. When a gate does apply, this suspends for as long as
     * the user takes to answer — the dialog lives in the chat UI, and cancelling the run resolves
     * the wait as a denial (see [ToolApprovalController.cancelPending]).
     */
    private suspend fun approvedToRun(name: String, arguments: String): Boolean {
        val settings = appSettings ?: return true
        // A durable grant is checked before the policy so "always allow" also outranks a later
        // switch to the noisiest policy; otherwise the same tool would start asking again.
        if (name in settings.getAlwaysAllowedTools()) return true

        val policy = settings.getToolApprovalPolicy()
        if (!ToolRiskRegistry.requiresApproval(name, policy)) return true

        val displayName = runCatching { getToolDisplayName(name) }.getOrDefault(name)
        // Bounded wait: a background run (heartbeat, scheduled task) has no chat screen attached, so
        // nobody can answer. Without a deadline that coroutine would hang until the user happened
        // to open the chat, which reads as a frozen assistant.
        val outcome = withTimeoutOrNull(APPROVAL_TIMEOUT_SECONDS.seconds) {
            ToolApprovalController.requestApproval(
                ToolApprovalRequest(
                    id = "$name-${arguments.hashCode()}-${Clock.System.now().toEpochMilliseconds()}",
                    toolId = name,
                    displayName = displayName,
                    arguments = arguments,
                    risk = ToolRiskRegistry.riskOf(name),
                ),
            )
        } ?: ToolApprovalOutcome.DENIED

        if (outcome.allowed && outcome.alwaysAllow) settings.setToolAlwaysAllowed(name, true)
        return outcome.allowed
    }

    private fun anyToJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull

        is String -> JsonPrimitive(value)

        is Boolean -> JsonPrimitive(value)

        is Number -> JsonPrimitive(value)

        is Map<*, *> -> JsonObject(
            value.entries.associate { (k, v) -> k.toString() to anyToJsonElement(v) },
        )

        is List<*> -> JsonArray(value.map { anyToJsonElement(it) })

        else -> JsonPrimitive(value.toString())
    }

    private fun parseJsonToMap(json: String): Map<String, Any> {
        val jsonObject = jsonParser.parseToJsonElement(json).jsonObject
        return jsonObject.toMap()
    }

    // An explicit JSON null means "not provided": dropping the key lets tools fall back to their
    // defaults instead of receiving the string "null".
    private fun JsonObject.toMap(): Map<String, Any> = entries
        .filter { (_, value) -> value !is JsonNull }
        .associate { (key, value) -> key to jsonElementToAny(value) }

    private fun jsonElementToAny(element: JsonElement): Any = when (element) {
        JsonNull -> "null"

        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.boolean
            element.intOrNull != null -> element.int
            element.doubleOrNull != null -> element.double
            else -> element.content
        }

        is JsonObject -> element.toMap()

        is JsonArray -> element.map { jsonElementToAny(it) }
    }

    suspend fun getToolDisplayName(toolId: String): String {
        val toolInfo = getPlatformToolDefinitions().find { it.id == toolId } ?: return toolId
        return toolInfo.nameRes?.let { getString(it) } ?: toolInfo.name
    }
}
