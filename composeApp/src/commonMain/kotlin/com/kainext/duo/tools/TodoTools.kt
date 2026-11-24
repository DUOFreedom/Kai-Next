package com.kainext.duo.tools

import com.kainext.duo.data.AppSettings
import com.kainext.duo.data.TodoItem
import com.kainext.duo.data.TodoStatus
import com.kainext.duo.data.TodoStore
import com.kainext.duo.data.currentConversationIdOrNull
import com.kainext.duo.network.tools.ParameterSchema
import com.kainext.duo.network.tools.Tool
import com.kainext.duo.network.tools.ToolInfo
import com.kainext.duo.network.tools.ToolSchema
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.tool_todo_write_description
import kai.composeapp.generated.resources.tool_todo_write_name
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `todo_write` — the agent's own task list.
 *
 * Modelled on the way DSH exposes a todo tool: one call replaces the whole list, every row carries
 * a status, and the list is what the user watches while a multi-step job runs. Replacement rather
 * than "add/remove/update" calls on purpose — a full rewrite is idempotent, so a retried or
 * replayed call converges instead of duplicating rows, and the model never has to track row ids it
 * cannot see.
 */
object TodoTools {

    const val TOOL_ID = "todo_write"

    val todoWriteToolInfo = ToolInfo(
        id = TOOL_ID,
        name = "Update Task List",
        description = "Show the user a task list for a multi-step job and keep it up to date",
        nameRes = Res.string.tool_todo_write_name,
        descriptionRes = Res.string.tool_todo_write_description,
    )

    /** The `todos` array schema: objects with `text` (required) and optional `status`. */
    private val todosSchema = buildJsonObject {
        put("type", "array")
        put(
            "description",
            "The complete task list, in order. Send the full list every time; rows you omit are removed.",
        )
        put(
            "items",
            buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "text",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "One short sentence describing the step")
                            },
                        )
                        put(
                            "status",
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "pending (not started), in_progress (doing it now), or completed",
                                )
                                put(
                                    "enum",
                                    buildJsonArray {
                                        add(kotlinx.serialization.json.JsonPrimitive("pending"))
                                        add(kotlinx.serialization.json.JsonPrimitive("in_progress"))
                                        add(kotlinx.serialization.json.JsonPrimitive("completed"))
                                    },
                                )
                            },
                        )
                    },
                )
                put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("text")) })
            },
        )
    }

    fun getTodoTools(appSettings: AppSettings, todoStore: TodoStore): List<Tool> {
        if (!appSettings.isToolEnabled(TOOL_ID)) return emptyList()
        return listOf(todoWriteTool(todoStore))
    }

    fun todoWriteTool(todoStore: TodoStore): Tool = object : Tool {
        override val schema = ToolSchema(
            name = TOOL_ID,
            description = "Write or update the visible task list for the current job. Use this once a " +
                "request needs more than a couple of steps, and keep exactly one row in_progress. " +
                "Always send the complete list: an omitted row is deleted, so re-send finished rows " +
                "as completed instead of dropping them. Send an empty list when the job is done to " +
                "clear the card.",
            parameters = mapOf(
                "todos" to ParameterSchema(
                    type = "array",
                    description = "The complete task list, in order.",
                    required = true,
                    rawSchema = todosSchema,
                ),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val conversationId = currentConversationIdOrNull()
            val raw = args["todos"]
            val entries: List<Any?> = when (raw) {
                is List<*> -> raw
                null -> return mapOf("error" to "Missing 'todos' array")
                else -> return mapOf("error" to "'todos' must be an array of {text, status} objects")
            }

            val previous = todoStore.forConversation(conversationId)
            val items = entries.mapIndexedNotNull { index, entry ->
                val (text, statusRaw) = when (entry) {
                    is Map<*, *> -> entry["text"]?.toString() to entry["status"]?.toString()
                    is String -> entry to null
                    else -> null to null
                }
                val trimmed = text?.trim().orEmpty()
                if (trimmed.isEmpty()) return@mapIndexedNotNull null
                // Reuse the id at the same index when the text is unchanged, so a row that only
                // changed status keeps its identity and the UI can animate instead of redrawing.
                val reusedId = previous.getOrNull(index)?.takeIf { it.text == trimmed }?.id
                TodoItem(
                    id = reusedId ?: TodoStore.newId(),
                    text = trimmed,
                    status = TodoStore.parseStatus(statusRaw),
                )
            }.take(TodoStore.MAX_ITEMS)

            todoStore.replace(conversationId, items)

            val done = items.count { it.status == TodoStatus.COMPLETED }
            return mapOf(
                "ok" to true,
                "total" to items.size,
                "completed" to done,
                "items" to items.map { mapOf("text" to it.text, "status" to it.status.name.lowercase()) },
            )
        }
    }
}
