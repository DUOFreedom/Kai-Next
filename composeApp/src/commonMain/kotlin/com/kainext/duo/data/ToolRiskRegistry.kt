package com.kainext.duo.data

/**
 * Assigns a [ToolRisk] to a tool id.
 *
 * Kept as a plain lookup rather than a property on [com.kainext.duo.network.tools.Tool] because
 * the same classification has to work for tools this build does not compile (MCP tools, skills),
 * and because a wrong default is worse than an explicit table: an unknown tool is treated as HIGH
 * so a newly added capability never silently bypasses the confirmation gate.
 */
object ToolRiskRegistry {

    /** Read-only tools: they observe state and cannot change anything the user cares about. */
    private val LOW = setOf(
        "get_local_time",
        "get_location_from_ip",
        "web_search",
        "fetch_url",
        "open_url",
        "read_sms",
        "search_sms",
        "check_sms",
        "read_email",
        "search_email",
        "check_email",
        "check_notifications",
        "search_notifications",
        "read_notification",
        "list_tasks",
        "get_clipboard",
        "list_files",
        "read_file",
    )

    /** Local, reversible app state. */
    private val MEDIUM = setOf(
        "memory_store",
        "memory_forget",
        "memory_learn",
        "memory_reinforce",
        "promote_learning",
        "todo_write",
        "open_file",
        "cancel_task",
        "set_clipboard",
        "write_file",
        "ssh_configure_host",
    )

    /**
     * Irreversible or externally visible: arbitrary commands, device automation, and anything that
     * sends a message or rings an alarm on the user's behalf.
     */
    private val HIGH = setOf(
        "execute_shell_command",
        "android_shell",
        "android_ui",
        "android_files",
        "device_control",
        "send_sms",
        "reply_sms",
        "send_email",
        "reply_email",
        "compose_email",
        "setup_email",
        "schedule_task",
        "create_calendar_event",
        "set_alarm",
        "send_notification",
        "run_subagent",
        "process_manager",
    )

    fun riskOf(toolId: String): ToolRisk = when (toolId) {
        in LOW -> ToolRisk.LOW

        in MEDIUM -> ToolRisk.MEDIUM

        in HIGH -> ToolRisk.HIGH

        // Unknown tools are gated: a capability this table has not seen yet is exactly the case
        // where an unattended write would be most surprising.
        else -> ToolRisk.HIGH
    }

    /** Whether [toolId] needs the user's confirmation under [policy]. */
    fun requiresApproval(toolId: String, policy: ToolApprovalPolicy): Boolean = when (policy) {
        ToolApprovalPolicy.NEVER -> false
        ToolApprovalPolicy.ALWAYS -> true
        ToolApprovalPolicy.HIGH_RISK -> riskOf(toolId) == ToolRisk.HIGH
    }
}
