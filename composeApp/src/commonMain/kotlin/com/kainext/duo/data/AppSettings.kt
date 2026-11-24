package com.kainext.duo.data

import com.kainext.duo.defaultUiScale
import com.kainext.duo.linux.LinuxDistro
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class ImportSection {
    SERVICES,
    SOUL,
    MEMORY,
    SCHEDULING,
    HEARTBEAT,
    EMAIL,
    SMS,
    SPLINTERLANDS,
    TOOLS,
    MCP,
    CONVERSATIONS,
    GOALS,
}

enum class ThemeMode {
    System,
    Light,
    Dark,
    OledBlack,
}

/**
 * Stricter than [detectImportSections]: only includes sections that contain actual user data,
 * skipping ones that exist purely because of default feature-toggle flags (e.g. `sms_enabled = false`,
 * `splinterlands_enabled = false`, `mcp_servers = []`). Used to drive the Export preview dialog.
 */
fun detectExportableSections(json: JsonObject): Map<ImportSection, String?> {
    val sections = mutableMapOf<ImportSection, String?>()

    val configured = json["configured_services"]?.jsonArray
    if (configured != null && configured.isNotEmpty()) {
        sections[ImportSection.SERVICES] = "${configured.size}"
    }

    if (json["soul_text"] != null) {
        sections[ImportSection.SOUL] = null
    }

    val memories = json["agent_memories"]?.jsonArray
    if (memories != null && memories.isNotEmpty()) {
        sections[ImportSection.MEMORY] = "${memories.size}"
    }

    val tasks = json["scheduled_tasks"]?.jsonArray
    if (tasks != null && tasks.isNotEmpty()) {
        sections[ImportSection.SCHEDULING] = "${tasks.size}"
    }

    val heartbeatHasPrompt = json["heartbeat_prompt"] != null
    val heartbeatHasConfig = json["heartbeat_config"] != null
    val heartbeatHasLog = json["heartbeat_log"]?.jsonArray?.isNotEmpty() == true
    if (heartbeatHasPrompt || heartbeatHasConfig || heartbeatHasLog) {
        sections[ImportSection.HEARTBEAT] = null
    }

    val emails = json["email_accounts"]?.jsonArray
    if (emails != null && emails.isNotEmpty()) {
        sections[ImportSection.EMAIL] = "${emails.size}"
    }

    val smsEnabled = json["sms_enabled"]?.jsonPrimitive?.content?.toBoolean() == true
    val smsSendEnabled = json["sms_send_enabled"]?.jsonPrimitive?.content?.toBoolean() == true
    if (smsEnabled || smsSendEnabled) {
        sections[ImportSection.SMS] = null
    }

    if (json["splinterlands_account"] != null) {
        sections[ImportSection.SPLINTERLANDS] = null
    }

    val toolOverrides = json["tool_overrides"]?.jsonObject
    if (toolOverrides != null && toolOverrides.isNotEmpty()) {
        val enabled = toolOverrides.count { (_, v) ->
            try {
                v.jsonPrimitive.content.toBoolean()
            } catch (_: Exception) {
                false
            }
        }
        sections[ImportSection.TOOLS] = "$enabled"
    }

    val mcp = json["mcp_servers"]?.jsonArray
    if (mcp != null && mcp.isNotEmpty()) {
        sections[ImportSection.MCP] = "${mcp.size}"
    }

    val conversations = json["conversations"]?.jsonArray
    if (conversations != null && conversations.isNotEmpty()) {
        sections[ImportSection.CONVERSATIONS] = "${conversations.size}"
    }

    val goals = json["goals"]?.jsonArray
    if (goals != null && goals.isNotEmpty()) {
        sections[ImportSection.GOALS] = "${goals.size}"
    }

    return sections
}

fun detectImportSections(json: JsonObject): Map<ImportSection, String?> {
    val sections = mutableMapOf<ImportSection, String?>()
    if (json["configured_services"] != null || json["current_service_id"] != null || json["free_fallback_enabled"] != null || json["instance_settings"] != null) {
        val count = json["configured_services"]?.jsonArray?.size
        sections[ImportSection.SERVICES] = count?.let { "$it" }
    }
    if (json["soul_text"] != null) {
        sections[ImportSection.SOUL] = null
    }
    if (json["memory_enabled"] != null || json["agent_memories"] != null) {
        val count = json["agent_memories"]?.jsonArray?.size
        sections[ImportSection.MEMORY] = count?.let { "$it" }
    }
    if (json["scheduling_enabled"] != null || json["scheduled_tasks"] != null) {
        val count = json["scheduled_tasks"]?.jsonArray?.size
        sections[ImportSection.SCHEDULING] = count?.let { "$it" }
    }
    if (json["heartbeat_config"] != null || json["heartbeat_prompt"] != null || json["heartbeat_log"] != null) {
        sections[ImportSection.HEARTBEAT] = null
    }
    if (json["email_enabled"] != null || json["email_accounts"] != null) {
        val count = json["email_accounts"]?.jsonArray?.size
        sections[ImportSection.EMAIL] = count?.let { "$it" }
    }
    if (json["sms_enabled"] != null || json["sms_poll_interval"] != null || json["sms_send_enabled"] != null) {
        sections[ImportSection.SMS] = null
    }
    if (json["splinterlands_enabled"] != null || json["splinterlands_account"] != null) {
        sections[ImportSection.SPLINTERLANDS] = null
    }
    if (json["tool_overrides"] != null) {
        val enabled = json["tool_overrides"]?.jsonObject?.count { (_, v) ->
            try {
                v.jsonPrimitive.content.toBoolean()
            } catch (_: Exception) {
                false
            }
        }
        sections[ImportSection.TOOLS] = enabled?.let { "$it" }
    }
    if (json["mcp_servers"] != null) {
        val count = json["mcp_servers"]?.jsonArray?.size
        sections[ImportSection.MCP] = count?.let { "$it" }
    }
    if (json["conversations"] != null) {
        val count = try {
            json["conversations"]?.jsonArray?.size
        } catch (_: Exception) {
            null
        }
        sections[ImportSection.CONVERSATIONS] = count?.let { "$it" }
    }
    if (json["goals"] != null) {
        val count = try {
            json["goals"]?.jsonArray?.size
        } catch (_: Exception) {
            null
        }
        sections[ImportSection.GOALS] = count?.let { "$it" }
    }
    return sections
}

data class ServiceInstance(
    val instanceId: String,
    val serviceId: String,
)

class AppSettings(internal val settings: Settings) {

    init {
        // One-time bump of the tool limits: earlier builds shipped 30 iterations / 4 parallel, and
        // whoever pressed "restore defaults" then persisted those numbers, which would otherwise
        // keep pinning them below the new defaults forever.
        if (!settings.getBoolean(KEY_TOOL_LIMITS_MIGRATED, false)) {
            if (settings.getInt(KEY_MAX_TOOL_ITERATIONS, -1) == LEGACY_DEFAULT_MAX_TOOL_ITERATIONS) {
                settings.putInt(KEY_MAX_TOOL_ITERATIONS, DEFAULT_MAX_TOOL_ITERATIONS)
            }
            if (settings.getInt(KEY_TOOL_PARALLEL_LIMIT, -1) == LEGACY_DEFAULT_TOOL_PARALLEL_LIMIT) {
                settings.putInt(KEY_TOOL_PARALLEL_LIMIT, DEFAULT_TOOL_PARALLEL_LIMIT)
            }
            settings.putBoolean(KEY_TOOL_LIMITS_MIGRATED, true)
        }
    }

    // App open tracking
    fun trackAppOpen(): Int {
        val currentCount = settings.getInt(KEY_APP_OPENS, 0)
        val newCount = currentCount + 1
        settings.putInt(KEY_APP_OPENS, newCount)
        return newCount
    }

    // Tool enable/disable settings
    fun isToolEnabled(toolId: String, defaultEnabled: Boolean = true): Boolean = settings.getBoolean("$KEY_TOOL_PREFIX$toolId", defaultEnabled)

    fun setToolEnabled(toolId: String, enabled: Boolean) {
        settings.putBoolean("$KEY_TOOL_PREFIX$toolId", enabled)
    }

    // Tool loop limits. Both are user-tunable from Settings > Tools so a long
    // agentic task is not cut off mid-way by a hardcoded ceiling, while a runaway
    // loop can still be bounded from the UI.
    fun getMaxToolIterations(): Int = settings
        .getInt(KEY_MAX_TOOL_ITERATIONS, DEFAULT_MAX_TOOL_ITERATIONS)
        .coerceIn(MIN_TOOL_ITERATIONS, MAX_TOOL_ITERATIONS_LIMIT)

    fun setMaxToolIterations(iterations: Int) {
        settings.putInt(KEY_MAX_TOOL_ITERATIONS, iterations.coerceIn(MIN_TOOL_ITERATIONS, MAX_TOOL_ITERATIONS_LIMIT))
    }

    fun getMaxRepeatedToolCalls(): Int = settings
        .getInt(KEY_MAX_REPEATED_TOOL_CALLS, DEFAULT_MAX_REPEATED_TOOL_CALLS)
        .coerceIn(MIN_REPEATED_TOOL_CALLS, MAX_REPEATED_TOOL_CALLS_LIMIT)

    fun setMaxRepeatedToolCalls(repetitions: Int) {
        settings.putInt(KEY_MAX_REPEATED_TOOL_CALLS, repetitions.coerceIn(MIN_REPEATED_TOOL_CALLS, MAX_REPEATED_TOOL_CALLS_LIMIT))
    }

    // Whether a tool call pauses for the user's confirmation. Stored by enum name so the stored
    // value stays readable if the enum order ever changes.
    fun getToolApprovalPolicy(): ToolApprovalPolicy {
        val stored = settings.getStringOrNull(KEY_TOOL_APPROVAL_POLICY) ?: return DEFAULT_TOOL_APPROVAL_POLICY
        return ToolApprovalPolicy.entries.firstOrNull { it.name == stored } ?: DEFAULT_TOOL_APPROVAL_POLICY
    }

    fun setToolApprovalPolicy(policy: ToolApprovalPolicy) {
        settings.putString(KEY_TOOL_APPROVAL_POLICY, policy.name)
    }

    // Tools the user answered "always allow" for. Comma-separated because tool ids never contain
    // commas, and a Json array here would mean importing a serializer into this settings class for
    // one field. Survives restarts so the same shell command is not re-confirmed every session.
    fun getAlwaysAllowedTools(): Set<String> = settings.getStringOrNull(KEY_ALWAYS_ALLOWED_TOOLS).orEmpty()
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    fun setToolAlwaysAllowed(toolId: String, allowed: Boolean) {
        val current = getAlwaysAllowedTools().toMutableSet()
        if (allowed) current.add(toolId) else current.remove(toolId)
        settings.putString(KEY_ALWAYS_ALLOWED_TOOLS, current.joinToString(","))
    }

    fun clearAlwaysAllowedTools() {
        settings.putString(KEY_ALWAYS_ALLOWED_TOOLS, "")
    }

    // Batch tool calls run in parallel; unbounded fan-out on a phone means a dozen shell probes and
    // file reads fighting for the same cores and memory, which is slower and can be OOM-killed.
    fun getToolParallelLimit(): Int = settings.getInt(KEY_TOOL_PARALLEL_LIMIT, DEFAULT_TOOL_PARALLEL_LIMIT)
        .coerceIn(MIN_TOOL_PARALLEL_LIMIT, MAX_TOOL_PARALLEL_LIMIT)

    fun setToolParallelLimit(limit: Int) {
        settings.putInt(KEY_TOOL_PARALLEL_LIMIT, limit.coerceIn(MIN_TOOL_PARALLEL_LIMIT, MAX_TOOL_PARALLEL_LIMIT))
    }

    // The agent's visible to-do lists, one per conversation, serialized as a JSON map. Stored here
    // rather than in the conversation file so ticking a checkbox does not rewrite chat history.
    fun getTodosJson(): String? = settings.getStringOrNull(KEY_TODOS)

    fun setTodosJson(json: String) {
        settings.putString(KEY_TODOS, json)
    }

    // Long-running goals, one per conversation, serialized as a JSON map. Persisted like the to-do
    // lists so a restart resumes the same round budget instead of starting over.
    fun getAgentGoalsJson(): String? = settings.getStringOrNull(KEY_AGENT_GOALS)

    fun setAgentGoalsJson(json: String) {
        settings.putString(KEY_AGENT_GOALS, json)
    }

    // Folded context blocks, one list per conversation, serialized as a JSON map. Kept beside the
    // history rather than inside it: the block holds the text the summary replaced, and the message
    // list must stay small — that is the whole point of folding.
    fun getContextFoldsJson(): String? = settings.getStringOrNull(KEY_CONTEXT_FOLDS)

    fun setContextFoldsJson(json: String) {
        settings.putString(KEY_CONTEXT_FOLDS, json)
    }

    // Persistent team members (serialized list of Member). Survives restarts and is included in backup/export.
    fun getMembersJson(): String? = settings.getStringOrNull(KEY_MEMBERS)

    fun setMembersJson(json: String) {
        settings.putString(KEY_MEMBERS, json)
    }

    // Team task board (serialized list of TeamTask). Append-only ledger like agent-team.
    fun getTaskBoardJson(): String? = settings.getStringOrNull(KEY_TASK_BOARD)

    fun setTaskBoardJson(json: String) {
        settings.putString(KEY_TASK_BOARD, json)
    }

    // How many autonomous continuation turns a goal may run before it is reported as blocked.
    fun getGoalMaxRounds(): Int = settings
        .getInt(KEY_GOAL_MAX_ROUNDS, GoalStore.DEFAULT_MAX_ROUNDS)
        .coerceIn(GoalStore.MIN_MAX_ROUNDS, GoalStore.MAX_MAX_ROUNDS)

    fun setGoalMaxRounds(rounds: Int) {
        settings.putInt(
            KEY_GOAL_MAX_ROUNDS,
            rounds.coerceIn(GoalStore.MIN_MAX_ROUNDS, GoalStore.MAX_MAX_ROUNDS),
        )
    }

    fun getConversationsJson(): String? = settings.getStringOrNull(KEY_CONVERSATIONS)

    fun setConversationsJson(json: String) {
        settings.putString(KEY_CONVERSATIONS, json)
    }

    fun removeConversationsJson() {
        settings.remove(KEY_CONVERSATIONS)
    }

    fun getCurrentConversationId(): String? = settings.getStringOrNull(KEY_CURRENT_CONVERSATION_ID)

    fun setCurrentConversationId(id: String?) {
        if (id == null) {
            settings.remove(KEY_CURRENT_CONVERSATION_ID)
        } else {
            settings.putString(KEY_CURRENT_CONVERSATION_ID, id)
        }
    }

    fun getCurrentInteractiveMode(): Boolean = settings.getBoolean(KEY_CURRENT_INTERACTIVE_MODE, false)

    fun setCurrentInteractiveMode(enabled: Boolean) {
        settings.putBoolean(KEY_CURRENT_INTERACTIVE_MODE, enabled)
    }

    fun isCurrentConversationMigrated(): Boolean = settings.getBoolean(KEY_CURRENT_CONVERSATION_MIGRATED, false)

    fun markCurrentConversationMigrated() {
        settings.putBoolean(KEY_CURRENT_CONVERSATION_MIGRATED, true)
    }

    fun getEncryptionKey(): ByteArray? {
        val encoded = settings.getStringOrNull(KEY_ENCRYPTION_KEY) ?: return null
        return try {
            @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
            kotlin.io.encoding.Base64.decode(encoded)
        } catch (_: Exception) {
            null
        }
    }

    // Free fallback
    fun isFreeFallbackEnabled(): Boolean = settings.getBoolean(KEY_FREE_FALLBACK_ENABLED, true)

    fun setFreeFallbackEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_FREE_FALLBACK_ENABLED, enabled)
    }

    fun getFreeMode(): FreeMode {
        val stored = settings.getStringOrNull(KEY_FREE_MODE) ?: return FreeMode.FAST
        return FreeMode.entries.find { it.name == stored } ?: FreeMode.FAST
    }

    fun setFreeMode(mode: FreeMode) {
        settings.putString(KEY_FREE_MODE, mode.name)
    }

    fun isFreeServicePrimary(): Boolean = settings.getBoolean(KEY_FREE_SERVICE_PRIMARY, false)

    fun setFreeServicePrimary(primary: Boolean) {
        settings.putBoolean(KEY_FREE_SERVICE_PRIMARY, primary)
    }

    // Soul (system prompt)
    fun getSoulText(): String = settings.getString(KEY_SOUL, "")

    fun setSoulText(text: String) {
        settings.putString(KEY_SOUL, text)
    }

    // Memory
    fun isMemoryEnabled(): Boolean = settings.getBoolean(KEY_MEMORY_ENABLED, true)

    fun setMemoryEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_MEMORY_ENABLED, enabled)
    }

    fun getMemoryInstructions(): String = settings.getString(KEY_MEMORY_INSTRUCTIONS, DEFAULT_MEMORY_INSTRUCTIONS)

    // Agent memories
    fun getMemoriesJson(): String = settings.getString(KEY_AGENT_MEMORIES, "[]")

    /**
     * Keeps a persisted JSON blob that no longer decodes (e.g. after a schema change without a
     * migration) under its own key, so the store's next write can't destroy the only copy.
     * Not part of settings export; recoverable by hand or by a later migration.
     */
    fun backupCorruptJson(label: String, raw: String) {
        settings.putString("$KEY_CORRUPT_JSON_BACKUP_PREFIX$label", raw)
    }

    fun setMemoriesJson(json: String) {
        settings.putString(KEY_AGENT_MEMORIES, json)
    }

    // Scheduling
    fun isSchedulingEnabled(): Boolean = settings.getBoolean(KEY_SCHEDULING_ENABLED, true)

    fun setSchedulingEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_SCHEDULING_ENABLED, enabled)
    }

    // Dynamic UI
    fun isDynamicUiEnabled(): Boolean = settings.getBoolean(KEY_DYNAMIC_UI_ENABLED, true)

    fun setDynamicUiEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_DYNAMIC_UI_ENABLED, enabled)
    }

    private val _themeModeFlow = MutableStateFlow(loadInitialThemeMode())
    val themeModeFlow: StateFlow<ThemeMode> = _themeModeFlow

    fun getThemeMode(): ThemeMode = _themeModeFlow.value

    fun setThemeMode(mode: ThemeMode) {
        settings.putString(KEY_THEME_MODE, mode.name)
        _themeModeFlow.value = mode
    }

    private fun loadInitialThemeMode(): ThemeMode {
        val raw = settings.getString(KEY_THEME_MODE, "")
        if (raw.isNotEmpty()) {
            return try {
                ThemeMode.valueOf(raw)
            } catch (_: IllegalArgumentException) {
                ThemeMode.System
            }
        }
        // Migrate the legacy boolean OLED toggle: true → OledBlack, false → System.
        return if (settings.getBoolean(KEY_OLED_MODE_ENABLED, false)) ThemeMode.OledBlack else ThemeMode.System
    }

    // Daemon mode
    fun isDaemonEnabled(): Boolean = settings.getBoolean(KEY_DAEMON_ENABLED, false)

    fun setDaemonEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_DAEMON_ENABLED, enabled)
    }

    // Device control (Android only)
    fun isDeviceControlEnabled(): Boolean = settings.getBoolean(KEY_DEVICE_CONTROL_ENABLED, false)

    fun setDeviceControlEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_DEVICE_CONTROL_ENABLED, enabled)
    }

    /** "auto" | "root" | "shizuku". Auto prefers root, then Shizuku. */
    fun getPrivilegedMode(): String = settings.getString(KEY_PRIVILEGED_MODE, "auto")

    fun setPrivilegedMode(mode: String) {
        settings.putString(KEY_PRIVILEGED_MODE, mode)
    }

    fun isDeviceShellEnabled(): Boolean = settings.getBoolean(KEY_DEVICE_SHELL_ENABLED, true)

    fun setDeviceShellEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_DEVICE_SHELL_ENABLED, enabled)
    }

    fun isSdcardAccessEnabled(): Boolean = settings.getBoolean(KEY_SDCARD_ACCESS_ENABLED, true)

    fun setSdcardAccessEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_SDCARD_ACCESS_ENABLED, enabled)
    }

    fun isUiAutomationEnabled(): Boolean = settings.getBoolean(KEY_UI_AUTOMATION_ENABLED, false)

    fun setUiAutomationEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_UI_AUTOMATION_ENABLED, enabled)
    }

    // Linux Sandbox
    fun isSandboxEnabled(): Boolean = settings.getBoolean(KEY_SANDBOX_ENABLED, true)

    fun setSandboxEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_SANDBOX_ENABLED, enabled)
    }

    /**
     * Which distribution the shell integration runs in. Each keeps its own
     * install, so changing this points the sandbox at the other one rather than
     * replacing anything — and an install always records its own distro, which
     * is what everything downstream reads.
     */
    fun getSandboxDistro(): LinuxDistro = LinuxDistro.fromId(settings.getStringOrNull(KEY_SANDBOX_DISTRO))

    /**
     * The distribution the user actually picked, or null if they never have.
     * Sandboxes predating the picker never recorded one, and the default must
     * not be mistaken for a choice — it would point them away from the Linux
     * they have been using all along.
     */
    fun getSandboxDistroOrNull(): LinuxDistro? = settings.getStringOrNull(KEY_SANDBOX_DISTRO)
        ?.let { id -> LinuxDistro.entries.firstOrNull { it.id == id } }

    fun setSandboxDistro(distro: LinuxDistro) {
        settings.putString(KEY_SANDBOX_DISTRO, distro.id)
    }

    /**
     * Kai Build's "Open with" choice — the agent a freshly opened project starts,
     * or null for a plain shell. Stored as an empty string so "never picked" and
     * "picked the shell" both come back as null.
     */
    fun getKaiBuildLaunchAgent(): String? = settings.getStringOrNull(KEY_KAI_BUILD_LAUNCH_AGENT)?.takeIf { it.isNotEmpty() }

    fun setKaiBuildLaunchAgent(agentId: String?) {
        settings.putString(KEY_KAI_BUILD_LAUNCH_AGENT, agentId.orEmpty())
    }

    fun getScheduledTasksJson(): String = settings.getString(KEY_SCHEDULED_TASKS, "[]")

    fun setScheduledTasksJson(json: String) {
        settings.putString(KEY_SCHEDULED_TASKS, json)
    }

    // Goals (目标/计划)
    fun getGoalsJson(): String = settings.getString(KEY_GOALS, "[]")

    fun setGoalsJson(json: String) {
        settings.putString(KEY_GOALS, json)
    }

    // Heartbeat config
    fun getHeartbeatConfigJson(): String = settings.getString(KEY_HEARTBEAT_CONFIG, "")

    fun setHeartbeatConfigJson(json: String) {
        settings.putString(KEY_HEARTBEAT_CONFIG, json)
    }

    // Heartbeat log
    fun getHeartbeatLogJson(): String = settings.getString(KEY_HEARTBEAT_LOG, "")

    fun setHeartbeatLogJson(json: String) {
        settings.putString(KEY_HEARTBEAT_LOG, json)
    }

    // Heartbeat prompt
    fun getHeartbeatPrompt(): String = settings.getString(KEY_HEARTBEAT_PROMPT, "")

    fun setHeartbeatPrompt(text: String) {
        settings.putString(KEY_HEARTBEAT_PROMPT, text)
    }

    // MCP Servers
    fun getMcpServersJson(): String = settings.getString(KEY_MCP_SERVERS, "")

    fun setMcpServersJson(json: String) {
        settings.putString(KEY_MCP_SERVERS, json)
    }

    // UI Scale
    private val _uiScaleFlow = MutableStateFlow(settings.getFloat(KEY_UI_SCALE, defaultUiScale))
    val uiScaleFlow: StateFlow<Float> = _uiScaleFlow

    fun getUiScale(): Float = _uiScaleFlow.value

    fun setUiScale(scale: Float) {
        settings.putFloat(KEY_UI_SCALE, scale)
        _uiScaleFlow.value = scale
    }

    // Email
    fun isEmailEnabled(): Boolean = settings.getBoolean(KEY_EMAIL_ENABLED, true)

    fun setEmailEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_EMAIL_ENABLED, enabled)
    }

    fun getEmailAccountsJson(): String = settings.getString(KEY_EMAIL_ACCOUNTS, "")

    fun setEmailAccountsJson(json: String) {
        settings.putString(KEY_EMAIL_ACCOUNTS, json)
    }

    fun getEmailPassword(accountId: String): String = settings.getString("${KEY_EMAIL_PASSWORD_PREFIX}$accountId", "")

    fun setEmailPassword(accountId: String, password: String) {
        settings.putString("${KEY_EMAIL_PASSWORD_PREFIX}$accountId", password)
    }

    fun removeEmailPassword(accountId: String) {
        settings.remove("${KEY_EMAIL_PASSWORD_PREFIX}$accountId")
    }

    fun getEmailSyncStateJson(accountId: String): String = settings.getString("${KEY_EMAIL_SYNC_PREFIX}$accountId", "")

    fun setEmailSyncStateJson(accountId: String, json: String) {
        settings.putString("${KEY_EMAIL_SYNC_PREFIX}$accountId", json)
    }

    fun getEmailPollIntervalMinutes(): Int = settings.getInt(KEY_EMAIL_POLL_INTERVAL, 15)

    fun setEmailPollIntervalMinutes(minutes: Int) {
        settings.putInt(KEY_EMAIL_POLL_INTERVAL, minutes)
    }

    fun getEmailPendingJson(): String = settings.getString(KEY_EMAIL_PENDING, "")

    fun setEmailPendingJson(json: String) {
        settings.putString(KEY_EMAIL_PENDING, json)
    }

    // SMS (FOSS-only, Android-only — settings layer is platform-agnostic, feature gate
    // is enforced by the READ_SMS permission being declared only in foss/AndroidManifest.xml)
    fun isSmsEnabled(): Boolean = settings.getBoolean(KEY_SMS_ENABLED, false)

    fun setSmsEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_SMS_ENABLED, enabled)
    }

    fun getSmsPollIntervalMinutes(): Int = settings.getInt(KEY_SMS_POLL_INTERVAL, 15)

    fun setSmsPollIntervalMinutes(minutes: Int) {
        settings.putInt(KEY_SMS_POLL_INTERVAL, minutes)
    }

    fun getSmsPendingJson(): String = settings.getString(KEY_SMS_PENDING, "")

    fun setSmsPendingJson(json: String) {
        settings.putString(KEY_SMS_PENDING, json)
    }

    fun getSmsSyncStateJson(): String = settings.getString(KEY_SMS_SYNC_STATE, "")

    fun setSmsSyncStateJson(json: String) {
        settings.putString(KEY_SMS_SYNC_STATE, json)
    }

    fun isSmsSendEnabled(): Boolean = settings.getBoolean(KEY_SMS_SEND_ENABLED, false)

    fun setSmsSendEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_SMS_SEND_ENABLED, enabled)
    }

    fun getSmsDraftsJson(): String = settings.getString(KEY_SMS_DRAFTS, "")

    fun setSmsDraftsJson(json: String) {
        settings.putString(KEY_SMS_DRAFTS, json)
    }

    // Notifications (FOSS-only, Android-only — settings layer is platform-agnostic, feature
    // gate is enforced by the listener service being declared only in foss/AndroidManifest.xml)
    fun isNotificationsEnabled(): Boolean = settings.getBoolean(KEY_NOTIFICATIONS_ENABLED, false)

    fun setNotificationsEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled)
    }

    fun getNotificationsPendingJson(): String = settings.getString(KEY_NOTIFICATIONS_PENDING, "")

    fun setNotificationsPendingJson(json: String) {
        settings.putString(KEY_NOTIFICATIONS_PENDING, json)
    }

    fun getNotificationsStoreJson(): String = settings.getString(KEY_NOTIFICATIONS_STORE, "")

    fun setNotificationsStoreJson(json: String) {
        settings.putString(KEY_NOTIFICATIONS_STORE, json)
    }

    fun getNotificationsSyncStateJson(): String = settings.getString(KEY_NOTIFICATIONS_SYNC_STATE, "")

    fun setNotificationsSyncStateJson(json: String) {
        settings.putString(KEY_NOTIFICATIONS_SYNC_STATE, json)
    }

    // Local model context size
    fun getModelContextTokens(modelId: String): Int = settings.getInt("$KEY_MODEL_CONTEXT_PREFIX$modelId", 0)

    fun setModelContextTokens(modelId: String, contextTokens: Int) {
        settings.putInt("$KEY_MODEL_CONTEXT_PREFIX$modelId", contextTokens)
    }

    // Per-model max output tokens override (0 = not set, use the model's own default)
    fun getModelMaxOutputTokens(modelId: String): Int = settings.getInt("$KEY_MODEL_MAX_OUTPUT_PREFIX$modelId", 0)

    fun setModelMaxOutputTokens(modelId: String, maxOutputTokens: Int) {
        settings.putInt("$KEY_MODEL_MAX_OUTPUT_PREFIX$modelId", maxOutputTokens)
    }

    // Model thinking effort
    fun getThinkingEffort(modelId: String): ThinkingEffort {
        val raw = settings.getStringOrNull("$KEY_THINKING_EFFORT_PREFIX$modelId") ?: return ThinkingEffort.MEDIUM
        return try {
            ThinkingEffort.valueOf(raw)
        } catch (_: IllegalArgumentException) {
            ThinkingEffort.MEDIUM
        }
    }

    fun setThinkingEffort(modelId: String, effort: ThinkingEffort) {
        settings.putString("$KEY_THINKING_EFFORT_PREFIX$modelId", effort.name)
    }

    // Splinterlands
    fun isSplinterlandsEnabled(): Boolean = settings.getBoolean(KEY_SPLINTERLANDS_ENABLED, false)

    fun setSplinterlandsEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_SPLINTERLANDS_ENABLED, enabled)
    }

    fun getSplinterlandsAccountJson(): String = settings.getString(KEY_SPLINTERLANDS_ACCOUNT, "")

    fun setSplinterlandsAccountJson(json: String) {
        settings.putString(KEY_SPLINTERLANDS_ACCOUNT, json)
    }

    fun getSplinterlandsPostingKey(): String = settings.getString(KEY_SPLINTERLANDS_POSTING_KEY, "")

    fun getSplinterlandsPostingKey(accountId: String): String = settings.getString("${KEY_SPLINTERLANDS_POSTING_KEY}_$accountId", "")
        .ifEmpty { getSplinterlandsPostingKey() } // fallback to legacy key

    fun setSplinterlandsPostingKey(accountId: String, key: String) {
        settings.putString("${KEY_SPLINTERLANDS_POSTING_KEY}_$accountId", key)
    }

    fun getSplinterlandsInstanceId(): String = settings.getString(KEY_SPLINTERLANDS_INSTANCE_ID, "")

    fun setSplinterlandsInstanceId(instanceId: String) {
        settings.putString(KEY_SPLINTERLANDS_INSTANCE_ID, instanceId)
    }

    fun getSplinterlandsInstanceIdsJson(): String = settings.getString(KEY_SPLINTERLANDS_INSTANCE_IDS, "")

    fun setSplinterlandsInstanceIdsJson(json: String) {
        settings.putString(KEY_SPLINTERLANDS_INSTANCE_IDS, json)
    }

    fun getSplinterlandsBattleLogJson(): String = settings.getString(KEY_SPLINTERLANDS_BATTLE_LOG, "")

    fun setSplinterlandsBattleLogJson(json: String) {
        settings.putString(KEY_SPLINTERLANDS_BATTLE_LOG, json)
    }

    companion object {
        const val KEY_CURRENT_SERVICE_ID = "current_service_id"
        const val KEY_APP_OPENS = "app_opens"

        const val KEY_CONVERSATIONS = "conversations_json"
        const val KEY_CURRENT_CONVERSATION_ID = "current_conversation_id"
        const val KEY_CURRENT_INTERACTIVE_MODE = "current_interactive_mode"
        const val KEY_CURRENT_CONVERSATION_MIGRATED = "current_conversation_migrated"
        const val KEY_ENCRYPTION_KEY = "encryption_key"
        const val KEY_MIGRATION_COMPLETE = "migration_complete_v1"
        const val KEY_TOOL_PREFIX = "tool_enabled_"
        const val KEY_MAX_TOOL_ITERATIONS = "max_tool_iterations"
        const val KEY_MAX_REPEATED_TOOL_CALLS = "max_repeated_tool_calls"
        const val DEFAULT_MAX_TOOL_ITERATIONS = 100
        const val DEFAULT_MAX_REPEATED_TOOL_CALLS = 5
        const val MIN_TOOL_ITERATIONS = 3
        const val MAX_TOOL_ITERATIONS_LIMIT = 200
        const val MIN_REPEATED_TOOL_CALLS = 2
        const val MAX_REPEATED_TOOL_CALLS_LIMIT = 20
        const val KEY_TOOL_APPROVAL_POLICY = "tool_approval_policy"
        val DEFAULT_TOOL_APPROVAL_POLICY = ToolApprovalPolicy.HIGH_RISK
        const val KEY_ALWAYS_ALLOWED_TOOLS = "tool_always_allowed"
        const val KEY_TOOL_PARALLEL_LIMIT = "tool_parallel_limit"
        const val DEFAULT_TOOL_PARALLEL_LIMIT = 20
        const val MIN_TOOL_PARALLEL_LIMIT = 1
        const val MAX_TOOL_PARALLEL_LIMIT = 32
        const val KEY_TOOL_LIMITS_MIGRATED = "tool_limits_migrated_v2"
        private const val LEGACY_DEFAULT_MAX_TOOL_ITERATIONS = 30
        private const val LEGACY_DEFAULT_TOOL_PARALLEL_LIMIT = 4
        const val KEY_TODOS = "agent_todos"
        const val KEY_AGENT_GOALS = "agent_goals"
        const val KEY_GOAL_MAX_ROUNDS = "goal_max_rounds"
        const val KEY_CONTEXT_FOLDS = "context_folds"
        const val KEY_MEMBERS = "team_members"
        const val KEY_TASK_BOARD = "team_task_board"
        const val KEY_SOUL = "soul_text"
        const val KEY_MEMORY_ENABLED = "memory_enabled"
        const val KEY_MEMORY_INSTRUCTIONS = "memory_instructions"
        const val KEY_AGENT_MEMORIES = "agent_memories"
        const val KEY_CORRUPT_JSON_BACKUP_PREFIX = "corrupt_json_backup."
        const val KEY_SCHEDULED_TASKS = "scheduled_tasks"
        const val KEY_SCHEDULING_ENABLED = "scheduling_enabled"
        const val KEY_DYNAMIC_UI_ENABLED = "dynamic_ui_enabled"
        const val KEY_OLED_MODE_ENABLED = "oled_mode_enabled"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_DAEMON_ENABLED = "daemon_enabled"
        const val KEY_HEARTBEAT_CONFIG = "heartbeat_config"
        const val KEY_HEARTBEAT_PROMPT = "heartbeat_prompt"
        const val KEY_HEARTBEAT_LOG = "heartbeat_log"

        const val KEY_EMAIL_ENABLED = "email_enabled"
        const val KEY_EMAIL_ACCOUNTS = "email_accounts"
        const val KEY_EMAIL_PASSWORD_PREFIX = "email_password_"
        const val KEY_EMAIL_SYNC_PREFIX = "email_sync_"
        const val KEY_EMAIL_POLL_INTERVAL = "email_poll_interval"
        const val KEY_EMAIL_PENDING = "email_pending"

        const val KEY_SMS_ENABLED = "sms_enabled"
        const val KEY_SMS_POLL_INTERVAL = "sms_poll_interval"
        const val KEY_SMS_PENDING = "sms_pending"
        const val KEY_SMS_SYNC_STATE = "sms_sync_state"
        const val KEY_SMS_SEND_ENABLED = "sms_send_enabled"
        const val KEY_SMS_DRAFTS = "sms_drafts"

        const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        const val KEY_NOTIFICATIONS_PENDING = "notifications_pending"
        const val KEY_NOTIFICATIONS_STORE = "notifications_store"
        const val KEY_NOTIFICATIONS_SYNC_STATE = "notifications_sync_state"
        const val KEY_CONFIGURED_SERVICES = "configured_services"
        const val KEY_FREE_FALLBACK_ENABLED = "free_fallback_enabled"
        const val KEY_FREE_MODE = "free_mode"
        const val KEY_FREE_SERVICE_PRIMARY = "free_service_primary"
        const val KEY_SERVICES_MIGRATION_COMPLETE = "services_migration_complete_v1"
        const val KEY_UI_SCALE = "ui_scale"
        const val KEY_MCP_SERVERS = "mcp_servers"
        const val KEY_INSTANCE_MIGRATION_COMPLETE = "instance_migration_complete_v1"
        const val KEY_BASE_URL_V1_MIGRATION_COMPLETE = "base_url_v1_migration_complete"
        const val KEY_CUSTOM_MODEL_MIGRATION_COMPLETE = "custom_model_migration_complete_v1"

        const val KEY_SPLINTERLANDS_ENABLED = "splinterlands_enabled"
        const val KEY_SPLINTERLANDS_ACCOUNT = "splinterlands_account"
        const val KEY_SPLINTERLANDS_POSTING_KEY = "splinterlands_posting_key"
        const val KEY_SPLINTERLANDS_BATTLE_LOG = "splinterlands_battle_log"
        const val KEY_SPLINTERLANDS_INSTANCE_ID = "splinterlands_instance_id"
        const val KEY_SPLINTERLANDS_INSTANCE_IDS = "splinterlands_instance_ids"

        const val KEY_MODEL_CONTEXT_PREFIX = "model_context_"

        const val KEY_MODEL_MAX_OUTPUT_PREFIX = "model_max_output_"

        const val KEY_THINKING_EFFORT_PREFIX = "thinking_effort_"

        // Goals (目标/计划)
        const val KEY_GOALS = "goals_json"

        // Device control (Android only): privileged shell + /sdcard + UI automation.
        const val KEY_DEVICE_CONTROL_ENABLED = "device_control_enabled"
        const val KEY_PRIVILEGED_MODE = "privileged_mode"
        const val KEY_DEVICE_SHELL_ENABLED = "device_shell_enabled"
        const val KEY_SDCARD_ACCESS_ENABLED = "sdcard_access_enabled"
        const val KEY_UI_AUTOMATION_ENABLED = "ui_automation_enabled"

        const val KEY_SANDBOX_ENABLED = "sandbox_enabled"
        const val KEY_SANDBOX_DISTRO = "sandbox_distro"
        const val KEY_KAI_BUILD_LAUNCH_AGENT = "kai_build_launch_agent"

        // Basic memory guidance shared by every chat variant. The advanced `## Structured
        // Learning` block lives in `ChatSystemPromptBuilder.DEFAULT_STRUCTURED_LEARNING_SECTION`
        // and is composed in only for the remote variant.
        const val DEFAULT_MEMORY_INSTRUCTIONS =
            "You have persistent memory across conversations. " +
                "All your stored memories are listed in the system prompt grouped by category.\n\n" +
                "When you learn important information about the user (name, preferences, projects, goals, etc.), " +
                "proactively use the memory_store tool to save it.\n" +
                "Use the memory_forget tool to remove outdated or incorrect memories.\n" +
                "Do not store trivial or transient information."
    }
}
