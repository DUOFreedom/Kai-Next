package com.kainext.duo.tools

import com.kainext.duo.data.AppSettings
import com.kainext.duo.data.ContextFoldStore
import com.kainext.duo.data.EmailStore
import com.kainext.duo.data.GoalStore
import com.kainext.duo.data.MemoryStore
import com.kainext.duo.data.TaskStore
import com.kainext.duo.data.TodoStore
import com.kainext.duo.mcp.McpServerManager
import com.kainext.duo.network.tools.Tool

/**
 * The tool set every platform builds, in the order the agent receives it.
 *
 * Each platform's `getAvailableTools()` used to repeat this gating block verbatim, which is how
 * they drifted — Android in particular had re-implemented the common-tool checks inline instead
 * of calling [CommonTools.getCommonTools]. Platform-specific tools are contributed through
 * [platformExtras]; MCP tools stay last everywhere.
 */
fun buildAgentToolSet(
    appSettings: AppSettings,
    memoryStore: MemoryStore,
    taskStore: TaskStore,
    mcpServerManager: McpServerManager,
    // The agent's visible task list; per-conversation, so the store is always injected.
    todoStore: TodoStore,
    // Long-running goals; per-conversation, like the todo list.
    goalStore: GoalStore,
    // Folded context blocks: what compaction summarised away, still readable by block id.
    contextFoldStore: ContextFoldStore,
    // Web has no email support and injects no store.
    emailStore: EmailStore? = null,
    platformExtras: MutableList<Tool>.() -> Unit = {},
): List<Tool> = buildList {
    addAll(CommonTools.getCommonTools(appSettings))

    // Todo list: available even with memory and scheduling off, because it is how a multi-step
    // turn shows its progress rather than an optional extra.
    addAll(TodoTools.getTodoTools(appSettings, todoStore))

    // Goals share that trait: they are how a job that outlives one turn reports its state.
    addAll(GoalTools.getGoalTools(appSettings, goalStore))

    // Folding tools are always offered: compaction runs whether or not the user enabled anything,
    // so a summary with no way to read behind it would be a dead end.
    addAll(ContextFoldTools.getContextFoldTools(appSettings, contextFoldStore))

    if (appSettings.isMemoryEnabled()) {
        addAll(CommonTools.getMemoryTools(memoryStore))
    }

    // Heartbeat tools ride the scheduling switch — they are only reachable from a scheduled run.
    if (appSettings.isSchedulingEnabled()) {
        addAll(SchedulingTools.getSchedulingTools(taskStore))
        addAll(HeartbeatTools.getHeartbeatTools(memoryStore, appSettings))
    }

    if (emailStore != null && appSettings.isEmailEnabled()) {
        addAll(EmailTools.getEmailTools(emailStore))
    }

    platformExtras()

    addAll(mcpServerManager.getEnabledMcpTools(reservedNames = mapTo(HashSet()) { it.schema.name }))
}
