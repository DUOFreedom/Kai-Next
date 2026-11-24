package com.kainext.duo.tools

import com.kainext.duo.data.AgentGoal
import com.kainext.duo.data.AgentGoalStatus
import com.kainext.duo.data.AppSettings
import com.kainext.duo.data.GoalStore
import com.kainext.duo.data.currentConversationIdOrNull
import com.kainext.duo.network.tools.ParameterSchema
import com.kainext.duo.network.tools.Tool
import com.kainext.duo.network.tools.ToolInfo
import com.kainext.duo.network.tools.ToolSchema
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.tool_goal_create_description
import kai.composeapp.generated.resources.tool_goal_create_name
import kai.composeapp.generated.resources.tool_goal_update_description
import kai.composeapp.generated.resources.tool_goal_update_name
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `create_goal` / `update_goal` — a persisted objective the agent keeps working on by itself.
 *
 * Mirrors how DSH models goals: the model states the objective once, then reports progress with a
 * status, and the runtime decides whether another round is spent. Creation and reporting are split
 * into two tools rather than one upsert so that "start a new job" and "I am done" can never be
 * confused by a malformed call — the destructive case (replacing an objective) needs its own tool
 * name the user can see in the transcript.
 */
object GoalTools {

    const val CREATE_TOOL_ID = "create_goal"

    const val UPDATE_TOOL_ID = "update_goal"

    val createGoalToolInfo = ToolInfo(
        id = CREATE_TOOL_ID,
        name = "Start Goal",
        description = "Record a long-running objective so you can keep working on it across turns",
        nameRes = Res.string.tool_goal_create_name,
        descriptionRes = Res.string.tool_goal_create_description,
    )

    val updateGoalToolInfo = ToolInfo(
        id = UPDATE_TOOL_ID,
        name = "Update Goal",
        description = "Report whether the active goal is done, paused, or blocked",
        nameRes = Res.string.tool_goal_update_name,
        descriptionRes = Res.string.tool_goal_update_description,
    )

    fun getGoalTools(appSettings: AppSettings, goalStore: GoalStore): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (appSettings.isToolEnabled(CREATE_TOOL_ID)) tools += createGoalTool(goalStore)
        if (appSettings.isToolEnabled(UPDATE_TOOL_ID)) tools += updateGoalTool(goalStore)
        return tools
    }

    fun createGoalTool(goalStore: GoalStore): Tool = object : Tool {
        override val schema = ToolSchema(
            name = CREATE_TOOL_ID,
            description = "Start a goal that you will keep working on across replies without asking " +
                "the user each time. Use it when the request needs many steps or a long-running " +
                "effort (migrating files, monitoring something, finishing a long report). Do not " +
                "use it for a single-turn question. Stating the objective replaces any goal still " +
                "active in this conversation.",
            parameters = mapOf(
                "objective" to ParameterSchema(
                    type = "string",
                    description = "One sentence stating what must be true when the goal is complete.",
                    required = true,
                ),
                "max_rounds" to ParameterSchema(
                    type = "number",
                    description = "Optional cap on autonomous continuation rounds " +
                        "(1-${GoalStore.MAX_MAX_ROUNDS}). Defaults to the user's setting.",
                    required = false,
                ),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val objective = args["objective"]?.toString()?.trim().orEmpty()
            if (objective.isEmpty()) return mapOf("error" to "Missing 'objective'")
            val maxRounds = (args["max_rounds"] as? Number)?.toInt()
            val goal = goalStore.start(currentConversationIdOrNull(), objective, maxRounds)
            return describe(goal) + ("ok" to true)
        }
    }

    fun updateGoalTool(goalStore: GoalStore): Tool = object : Tool {
        override val schema = ToolSchema(
            name = UPDATE_TOOL_ID,
            description = "Report the state of the active goal. Call it with \"completed\" as soon as " +
                "the objective is met, or with \"blocked\" when you need something only the user can " +
                "provide. Leaving the goal active means the runtime will spend another round on it.",
            parameters = mapOf(
                "status" to ParameterSchema(
                    type = "string",
                    description = "completed, blocked, paused, or active (to resume).",
                    required = true,
                    rawSchema = buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add(JsonPrimitive("completed"))
                                add(JsonPrimitive("blocked"))
                                add(JsonPrimitive("paused"))
                                add(JsonPrimitive("active"))
                            },
                        )
                    },
                ),
                "blocked_reason" to ParameterSchema(
                    type = "string",
                    description = "Why the goal cannot continue. Required when status is blocked.",
                    required = false,
                ),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val conversationId = currentConversationIdOrNull()
            val current = goalStore.forConversation(conversationId)
                ?: return mapOf("error" to "There is no goal in this conversation")
            val status = parseStatus(args["status"]?.toString())
                ?: return mapOf("error" to "'status' must be one of completed, blocked, paused, active")
            val reason = args["blocked_reason"]?.toString()?.trim().orEmpty()
            if (status == AgentGoalStatus.BLOCKED && reason.isEmpty()) {
                return mapOf("error" to "'blocked_reason' is required when status is blocked")
            }
            val updated = goalStore.setStatus(
                conversationId,
                status,
                reason.takeIf { it.isNotEmpty() },
            ) ?: current
            return describe(updated) + ("ok" to true)
        }
    }

    private fun parseStatus(raw: String?): AgentGoalStatus? = when (raw?.trim()?.lowercase()) {
        "completed", "complete", "done" -> AgentGoalStatus.COMPLETED
        "blocked", "stuck" -> AgentGoalStatus.BLOCKED
        "paused", "pause" -> AgentGoalStatus.PAUSED
        "active", "resume", "running", "in_progress" -> AgentGoalStatus.ACTIVE
        else -> null
    }

    /** The shape the model reads back, so a follow-up turn sees the same numbers the card shows. */
    private fun describe(goal: AgentGoal): Map<String, Any> = buildMap {
        put("objective", goal.objective)
        put("status", goal.status.name.lowercase())
        put("round", goal.rounds)
        put("max_rounds", goal.maxRounds)
        goal.blockedReason?.let { put("blocked_reason", it) }
    }
}
