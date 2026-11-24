package com.kainext.duo.ui.chat

import androidx.compose.runtime.Immutable
import com.kainext.duo.data.AgentGoalStatus
import com.kainext.duo.data.ThinkingEffort
import io.github.vinceglb.filekit.PlatformFile

@Immutable
data class ChatActions(
    val ask: (String) -> Unit,
    val toggleSpeechOutput: () -> Unit,
    val retry: () -> Unit,
    val clearHistory: () -> Unit,
    val setIsSpeaking: (Boolean, String) -> Unit,
    val addFile: (PlatformFile) -> Unit,
    val removeFile: (PlatformFile) -> Unit,
    val startNewChat: () -> Unit,
    val regenerate: () -> Unit,
    val cancel: () -> Unit,
    val selectService: (String) -> Unit,
    val changeThinkingEffort: (ThinkingEffort) -> Unit,
    val loadConversation: (String) -> Unit,
    val deleteConversation: (String) -> Unit,
    val clearUnreadHeartbeat: () -> Unit,
    val clearSnackbar: () -> Unit,
    val undoDeleteConversation: () -> Unit,
    val submitUiCallback: (event: String, data: Map<String, String>) -> Unit,
    val resubmit: (messageId: String, event: String, data: Map<String, String>) -> Unit,
    val enterInteractiveMode: () -> Unit,
    val exitInteractiveMode: () -> Unit,
    val goBackInteractiveMode: () -> Unit,
    val sendSmsDraft: (String) -> Unit,
    val discardSmsDraft: (String) -> Unit,
    val consumeComposerPrefill: () -> Unit,
    /** Shows the floating control window; no-op when the overlay permission is missing. */
    val showOverlay: () -> Unit,
    /** Flips the next turn between a single model call and a planned swarm run. */
    val toggleTeamMode: () -> Unit,
    /** Answers the tool-approval prompt that is currently on screen. */
    val respondToToolApproval: (Boolean) -> Unit = {},
    val alwaysAllowPendingTool: () -> Unit = {},
    /** Ticks one row of the agent's task list off by hand. */
    val toggleTodo: (String) -> Unit = {},
    /** Pauses, resumes, or retires the conversation's long-running goal. */
    val setGoalStatus: (AgentGoalStatus) -> Unit = {},
)
