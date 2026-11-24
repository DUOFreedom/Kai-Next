package com.kainext.duo.tools

import com.kainext.duo.agents.SubagentRunner
import com.kainext.duo.data.AppSettings
import com.kainext.duo.data.DataRepository
import com.kainext.duo.data.ToolExecutor
import com.kainext.duo.network.tools.ParameterSchema
import com.kainext.duo.network.tools.Tool
import com.kainext.duo.network.tools.ToolInfo
import com.kainext.duo.network.tools.ToolSchema
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.tool_subagent_description
import kai.composeapp.generated.resources.tool_subagent_name
import kotlin.time.Duration.Companion.minutes

/**
 * `run_subagent` — a side investigation with its own scratch log and its own tool family.
 *
 * The point is not parallelism (one call is still one branch) but separation: the child's tool rows
 * never enter the conversation, so a five-step errand leaves the transcript as clean as a one-line
 * answer. The child also cannot reach `run_subagent` itself, which is what stops a runaway tree.
 */
object SubagentTools {

    const val TOOL_ID = "run_subagent"

    val runSubagentToolInfo = ToolInfo(
        id = TOOL_ID,
        name = "Run Subagent",
        description = "Delegate a self-contained investigation to a subagent that reports only its conclusion",
        nameRes = Res.string.tool_subagent_name,
        descriptionRes = Res.string.tool_subagent_description,
    )

    fun getSubagentTools(
        appSettings: AppSettings,
        toolExecutor: () -> ToolExecutor,
        dataRepository: () -> DataRepository,
        toolsProvider: () -> List<Tool>,
    ): List<Tool> = if (appSettings.isToolEnabled(TOOL_ID)) {
        listOf(
            runSubagentTool(
                toolExecutor = toolExecutor,
                dataRepository = dataRepository,
                toolsProvider = toolsProvider,
            ),
        )
    } else {
        emptyList()
    }

    fun runSubagentTool(
        toolExecutor: () -> ToolExecutor,
        dataRepository: () -> DataRepository,
        toolsProvider: () -> List<Tool>,
    ): Tool = object : Tool {

        // A child may spend several tool calls, each with its own timeout, so the parent's envelope
        // has to be generous; the child's own step budget is what actually bounds the work.
        override val timeout = 8.minutes

        override val schema = ToolSchema(
            name = TOOL_ID,
            description = "Delegate a task to a subagent. The subagent works on its own with a tool " +
                "budget, keeps a private work log, and returns only a conclusion — its tool calls " +
                "never appear in the conversation. Use it for a side errand whose intermediate " +
                "steps would clutter the chat (checking several files, gathering facts from a few " +
                "pages, a small experiment). Do not use it for work that needs the user's ongoing " +
                "input, and do not nest it: the subagent cannot call run_subagent.",
            parameters = mapOf(
                "task" to ParameterSchema(
                    type = "string",
                    description = "The complete, self-contained task. The subagent cannot see this " +
                        "conversation, so state every fact and expectation it needs.",
                    required = true,
                ),
                "max_steps" to ParameterSchema(
                    type = "integer",
                    description = "How many tool calls the subagent may make before it must " +
                        "summarise (${SubagentRunner.MIN_MAX_STEPS}-${SubagentRunner.MAX_MAX_STEPS}, " +
                        "default ${SubagentRunner.DEFAULT_MAX_STEPS}).",
                    required = false,
                ),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val task = args["task"]?.toString()?.trim().orEmpty()
            if (task.isEmpty()) {
                return mapOf("ok" to false, "error" to "task is required")
            }
            val executor = toolExecutor()
            val runner = SubagentRunner(
                // Excluding this tool by name is the recursion guard: the child gets the rest of the
                // user's enabled tool family, minus the ability to spawn its own children.
                tools = toolsProvider().filter { it.schema.name != TOOL_ID },
                executeTool = { name, arguments -> executor.executeTool(name, arguments) },
                complete = { prompt -> dataRepository().askSilently(prompt) },
            )
            val maxSteps = SubagentRunner.clampSteps(
                (args["max_steps"] as? Number)?.toInt() ?: args["max_steps"]?.toString()?.toIntOrNull(),
            )
            val result = runner.run(task = task, maxSteps = maxSteps)
            return if (result.isSuccess) {
                mapOf(
                    "ok" to true,
                    "answer" to result.answer,
                    "steps" to result.steps,
                )
            } else {
                mapOf(
                    "ok" to false,
                    "error" to result.error.orEmpty(),
                    "answer" to result.answer,
                    "steps" to result.steps,
                )
            }
        }
    }
}
