package com.kainext.duo.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kainext.duo.agents.InMemoryBlackboard
import com.kainext.duo.agents.LlmPlanner
import com.kainext.duo.agents.StepStatus
import com.kainext.duo.agents.SwarmOrchestrator
import com.kainext.duo.agents.SwarmRequest
import com.kainext.duo.agents.ToolBackedWorker
import com.kainext.duo.agents.Worker
import com.kainext.duo.agents.WorkerKind
import com.kainext.duo.data.AgentGoalStatus
import com.kainext.duo.data.Conversation
import com.kainext.duo.data.DataRepository
import com.kainext.duo.data.FreeMode
import com.kainext.duo.data.GoalStore
import com.kainext.duo.data.ModelCatalog
import com.kainext.duo.data.Service
import com.kainext.duo.data.ServiceEntry
import com.kainext.duo.data.TaskScheduler
import com.kainext.duo.data.ThinkingEffort
import com.kainext.duo.data.TodoStatus
import com.kainext.duo.data.TodoStore
import com.kainext.duo.data.ToolApprovalController
import com.kainext.duo.data.UiSubmission
import com.kainext.duo.data.supportsReasoningEffort
import com.kainext.duo.getAvailableTools
import com.kainext.duo.getBackgroundDispatcher
import com.kainext.duo.network.UiError
import com.kainext.duo.network.shouldShowFreeProviderSuggestions
import com.kainext.duo.network.toUiError
import com.kainext.duo.network.tools.Tool
import com.kainext.duo.overlay.OverlayController
import com.kainext.duo.overlay.OverlayPlanStep
import com.kainext.duo.overlay.OverlayStepStatus
import com.kainext.duo.tools.AppPermission
import com.kainext.duo.tools.PermissionController
import com.kainext.duo.tools.isLocalNetworkUrl
import com.kainext.duo.ui.markdown.KaiUiBlock
import com.kainext.duo.ui.markdown.KaiUiError
import com.kainext.duo.ui.markdown.parseMarkdown
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.extension
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.conversation_untitled
import kai.composeapp.generated.resources.error_local_network_permission
import kai.composeapp.generated.resources.error_unsupported_file_type
import kai.composeapp.generated.resources.litert_no_model_warning
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

class ChatViewModel(
    private val dataRepository: DataRepository,
    private val taskScheduler: TaskScheduler,
    private val backgroundDispatcher: CoroutineContext = getBackgroundDispatcher(),
    private val localNetworkPermissionController: PermissionController = PermissionController(AppPermission.LOCAL_NETWORK),
    // Nullable so tests and previews can build the view model without a persistence layer.
    private val todoStore: TodoStore? = null,
    private val goalStore: GoalStore? = null,
) : ViewModel() {

    private val actions = ChatActions(
        ask = ::ask,
        retry = ::retry,
        toggleSpeechOutput = ::toggleSpeechOutput,
        clearHistory = ::clearHistory,
        setIsSpeaking = ::setIsSpeaking,
        addFile = ::addFile,
        removeFile = ::removeFile,
        startNewChat = { startNewChat() },
        regenerate = ::regenerate,
        cancel = ::cancel,
        selectService = ::selectService,
        changeThinkingEffort = ::changeThinkingEffort,
        loadConversation = ::loadConversation,
        deleteConversation = ::deleteConversation,
        clearUnreadHeartbeat = ::clearUnreadHeartbeat,
        clearSnackbar = ::clearSnackbar,
        undoDeleteConversation = ::undoDeleteConversation,
        submitUiCallback = ::submitUiCallback,
        resubmit = ::resubmit,
        enterInteractiveMode = ::enterInteractiveMode,
        exitInteractiveMode = ::exitInteractiveMode,
        goBackInteractiveMode = ::goBackInteractiveMode,
        sendSmsDraft = ::sendSmsDraft,
        discardSmsDraft = ::discardSmsDraft,
        consumeComposerPrefill = ::consumeComposerPrefill,
        showOverlay = { dataRepository.showOverlay() },
        toggleTeamMode = ::toggleTeamMode,
        respondToToolApproval = { approved -> ToolApprovalController.respond(approved) },
        alwaysAllowPendingTool = { ToolApprovalController.respondAlwaysAllow() },
        toggleTodo = ::toggleTodo,
        setGoalStatus = ::setGoalStatus,
    )
    private val freeModeNames: Map<FreeMode, String> = FreeMode.entries.associateWith { "Free ${it.modelId.replaceFirstChar { c -> c.uppercase() }}" }
    private var currentJob: Job? = null
    private var pendingConversationDeleteJob: Job? = null
    private val _state = MutableStateFlow(
        ChatUiState(
            actions = actions,
            showPrivacyInfo = dataRepository.isUsingSharedKey(),
        ),
    )

    init {
        updateAvailableServices()

        // The tool executor suspends inside a tool call while it waits for approval, so the
        // pending request has to be mirrored into UI state for the dialog to appear at all.
        viewModelScope.launch {
            ToolApprovalController.pending.collect { request ->
                _state.update { it.copy(pendingToolApproval = request) }
            }
        }
        todoStore?.let { store ->
            viewModelScope.launch {
                combine(store.todos, dataRepository.currentConversationId) { todos, conversationId ->
                    todos[conversationId.orEmpty()].orEmpty().toImmutableList()
                }.distinctUntilChanged().collect { items ->
                    _state.update { it.copy(todos = items) }
                }
            }
        }
        goalStore?.let { store ->
            viewModelScope.launch {
                combine(store.goals, dataRepository.currentConversationId) { goals, conversationId ->
                    goals[conversationId.orEmpty()]
                }.distinctUntilChanged().collect { goal ->
                    _state.update { it.copy(goal = goal) }
                }
            }
        }

        // Bridge the floating window back into the chat loop: a stop tap cancels the running
        // request, and a message typed into the window is submitted as a normal follow-up.
        // Both are observed here rather than in the window's service so there is exactly one
        // owner of the chat state.
        viewModelScope.launch {
            var handled = OverlayController.stopRequests.value
            OverlayController.stopRequests.collect { count ->
                if (count != handled) {
                    handled = count
                    currentJob?.cancel()
                    currentJob = null
                    _state.update { it.copy(isLoading = false) }
                    OverlayController.finishTask(success = false, statusText = "Stopped by user")
                }
            }
        }
        viewModelScope.launch {
            OverlayController.userMessages.collect { pending ->
                if (pending.isEmpty()) return@collect
                // Drain only when the message can actually be sent. Draining first and then
                // discovering the agent is busy would silently drop what the user typed.
                if (_state.value.isLoading) {
                    OverlayController.log("Queued until the current step finishes")
                    return@collect
                }
                val messages = OverlayController.drainUserMessages()
                val last = messages.lastOrNull() ?: return@collect
                askInternal(last, uiSubmission = null)
            }
        }
        // Flush anything the window queued while a task was running. isLoading flipping to false
        // is the signal that the previous turn ended.
        viewModelScope.launch {
            _state.map { it.isLoading }.distinctUntilChanged().collect { loading ->
                if (loading) return@collect
                val queued = OverlayController.drainUserMessages()
                val next = queued.lastOrNull() ?: return@collect
                askInternal(next, uiSubmission = null)
            }
        }

        // Keep restoreCurrentConversation off the main thread; see issue #197 (large persisted
        // tool outputs caused ANRs when JSON-decoded synchronously during VM construction).
        // ChatScreen gates the interactive-mode branch on !isRestoring to avoid a flash.
        viewModelScope.launch(backgroundDispatcher) {
            dataRepository.loadConversations()
            // Share and assist both want a fresh chat. Skip restore so a slow
            // load cannot clobber startNewChat() when Kai is cold-started from
            // ACTION_SEND or ACTION_ASSIST.
            val skipRestore = dataRepository.pendingShareText.value != null ||
                dataRepository.openAssistRequested.value
            if (!skipRestore) {
                dataRepository.restoreCurrentConversation()
                presetInteractiveModeForCurrentConversation()
            }
            _state.update { it.copy(isRestoring = false) }
        }

        viewModelScope.launch(backgroundDispatcher) {
            dataRepository.connectEnabledMcpServers()
        }
        viewModelScope.launch {
            dataRepository.fallbackStatus.collect { status ->
                _state.update { it.copy(fallbackStatus = status) }
            }
        }
        taskScheduler.isLoadingCheck = { _state.value.isLoading }
        taskScheduler.start()

        viewModelScope.launch {
            dataRepository.smsDrafts.collect { drafts ->
                _state.update { it.copy(smsDrafts = drafts.toImmutableList()) }
            }
        }

        viewModelScope.launch {
            dataRepository.openHeartbeatRequested
                .filter { it }
                .collect {
                    val heartbeatId = dataRepository.savedConversations.value
                        .firstOrNull { it.type == Conversation.TYPE_HEARTBEAT }?.id
                    if (heartbeatId != null) {
                        loadConversation(heartbeatId)
                        clearUnreadHeartbeat()
                    }
                    dataRepository.consumeOpenHeartbeatRequest()
                }
        }

        viewModelScope.launch {
            dataRepository.openAssistRequested
                .filter { it }
                .collect {
                    startNewChat()
                    dataRepository.consumeOpenAssistRequest()
                }
        }

        viewModelScope.launch {
            dataRepository.pendingShareText
                .filterNotNull()
                .collect { text ->
                    startNewChat(composerPrefill = text)
                    dataRepository.consumeOpenShareRequest()
                }
        }
    }

    val state = combine(
        _state,
        dataRepository.chatHistory,
        dataRepository.savedConversations,
        dataRepository.currentConversationId,
        dataRepository.hasUnreadHeartbeat,
    ) { state, history, conversations, conversationId, hasUnreadHeartbeat ->
        val summaries = conversations
            .sortedByDescending { it.updatedAt }
            .map {
                val isHeartbeat = it.type == Conversation.TYPE_HEARTBEAT
                val isInteractive = it.type == Conversation.TYPE_INTERACTIVE
                ConversationSummary(
                    id = it.id,
                    title = if (isHeartbeat) "" else it.title.ifEmpty { getString(Res.string.conversation_untitled) },
                    updatedAt = it.updatedAt,
                    isHeartbeat = isHeartbeat,
                    isInteractive = isInteractive,
                )
            }
        // Conversation-level counters for the stats strip. Rounds are user turns (one question =
        // one round); steps are tool executions, which is what makes a long agentic run feel long.
        // Tokens are summed from per-turn provider usage, so they stay honest even when a turn
        // reports nothing (those simply contribute zero). Counted in one pass: this block runs on
        // every streamed delta, so three separate scans of the transcript would triple that cost.
        var rounds = 0
        var steps = 0
        var totalTokens = 0
        for (entry in history) {
            when (entry.role) {
                History.Role.USER -> rounds++
                History.Role.TOOL, History.Role.TOOL_EXECUTING -> steps++
                History.Role.ASSISTANT -> Unit
            }
            totalTokens += entry.tokenUsage?.totalTokens ?: 0
        }
        state.copy(
            history = history.toImmutableList(),
            supportedFileExtensions = dataRepository.supportedFileExtensions().toImmutableList(),
            savedConversations = summaries.toImmutableList(),
            currentConversationId = conversationId,
            hasUnreadHeartbeat = hasUnreadHeartbeat,
            installedSkills = dataRepository.getInstalledSkills().toImmutableList(),
            rounds = rounds,
            // In-flight tool calls count too: otherwise the step counter sits still during a
            // long tool run and then jumps, which reads as "stuck".
            steps = steps,
            totalTokens = totalTokens,
        )
    }.distinctUntilChanged().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = _state.value,
    )

    private fun submitUiCallback(event: String, data: Map<String, String>) {
        val message = if (data.isNotEmpty()) {
            val formattedData = data.entries.joinToString(", ") { "${it.key}: ${it.value}" }
            "Responded with: $formattedData"
        } else {
            "Pressed: $event"
        }
        val lastAssistant = dataRepository.chatHistory.value.lastRenderedAssistant()
        val submission = lastAssistant?.let {
            UiSubmission(sourceContent = it.content, values = data, pressedEvent = event)
        }
        askInternal(message, submission)
    }

    private fun ask(question: String?) {
        askInternal(question, null)
    }

    private fun askInternal(question: String?, uiSubmission: UiSubmission?) {
        // Prevent concurrent requests
        if (_state.value.isLoading) return

        // Capture files before launching coroutine to avoid race with files being cleared
        val files = _state.value.files

        val (strippedQuestion, activeSkillId) = parseSkillInvocation(question)

        currentJob = viewModelScope.launch(backgroundDispatcher) {
            _state.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    showFreeProviderSuggestions = false,
                    files = persistentListOf(),
                )
            }
            // Android 17+ blocks LAN traffic without the local network permission — without
            // asking first, requests to self-hosted servers silently never leave the device.
            if (!ensureLocalNetworkPermission()) {
                _state.update {
                    it.copy(
                        error = UiError.Resource(Res.string.error_local_network_permission),
                        showFreeProviderSuggestions = false,
                        isLoading = false,
                    )
                }
                return@launch
            }
            try {
                // Mirror progress into the floating window so the user can watch (and interrupt)
                // while the agent is operating other apps. The controller is a plain object with
                // a StateFlow, so this works without the window being open.
                val teamMode = _state.value.isTeamMode
                OverlayController.startTask(
                    strippedQuestion?.takeIf { it.isNotBlank() }?.take(120) ?: "Working…",
                    teamMode = teamMode,
                )
                if (teamMode) {
                    OverlayController.setTeamMode(true)
                    runTeamTurn(strippedQuestion.orEmpty())
                } else {
                    dataRepository.ask(strippedQuestion, files, uiSubmission, activeSkillId)
                }

                // Auto-retry in interactive mode if the response has no valid kai-ui
                if (_state.value.isInteractiveMode) {
                    retryIfNoValidKaiUi()
                }

                // A goal the model has not closed out yet means "not finished": keep the turn
                // going instead of making the user type "continue" after every round.
                continueGoalIfActive()

                _state.update {
                    it.copy(isLoading = false)
                }
                OverlayController.finishTask(success = true, statusText = "Done")
            } catch (exception: Exception) {
                // CancellationException must be re-thrown to properly propagate coroutine cancellation
                if (exception is CancellationException) throw exception

                val showUpsell = shouldShowFreeProviderSuggestions(
                    noConfiguredServices = dataRepository.getConfiguredServiceInstances().isEmpty(),
                    exception = exception,
                )
                _state.update {
                    it.copy(
                        error = exception.toUiError(),
                        showFreeProviderSuggestions = showUpsell,
                        isLoading = false,
                    )
                }
                OverlayController.finishTask(success = false, statusText = "Failed")
            }
        }
    }

    /**
     * True unless the active service points at a local network host and the user
     * denied the local network permission. Cheap no-op on non-Android platforms.
     */
    private suspend fun ensureLocalNetworkPermission(): Boolean {
        val instance = dataRepository.getConfiguredServiceInstances().firstOrNull() ?: return true
        val baseUrl = dataRepository.getInstanceBaseUrl(instance.instanceId, Service.fromId(instance.serviceId))
        if (!isLocalNetworkUrl(baseUrl)) return true
        return localNetworkPermissionController.requestPermission()
    }

    private suspend fun retryIfNoValidKaiUi(maxRetries: Int = 2) {
        repeat(maxRetries) {
            currentCoroutineContext().ensureActive()
            val lastAssistant = dataRepository.chatHistory.value.lastRenderedAssistant() ?: return

            val blocks = parseMarkdown(lastAssistant.content).blocks
            val hasValidUi = blocks.any { it is KaiUiBlock }
            if (hasValidUi) return

            // Build error feedback for the AI
            val errorBlock = blocks.filterIsInstance<KaiUiError>().firstOrNull()
            val errorDetail = if (errorBlock != null) {
                "JSON parse error in: ${errorBlock.rawJson.take(200)}"
            } else {
                "No kai-ui code fence found in your response."
            }
            val retryMessage = "[SYSTEM] Your previous response failed to render as interactive UI. $errorDetail " +
                "Remember: respond with ONLY a single ```kai-ui code fence containing valid JSON. No text outside the fence."

            dataRepository.ask(retryMessage, emptyList())
        }
    }

    /**
     * Keeps a long-running goal moving after the model stops on its own.
     *
     * The model is the one that decides a goal is done (update_goal), so a turn that ends
     * with the goal still ACTIVE means "not finished yet". Nudging it to carry on is what
     * turns a multi-turn chore into a single request; every nudge spends one round of the
     * budget, and an exhausted budget marks the goal BLOCKED so the stop is visible instead
     * of the assistant simply going quiet.
     */
    private suspend fun continueGoalIfActive() {
        val store = goalStore ?: return
        val conversationId = dataRepository.currentConversationId.value.orEmpty()
        while (true) {
            currentCoroutineContext().ensureActive()
            val goal = store.forConversation(conversationId)
                ?.takeIf { it.status == AgentGoalStatus.ACTIVE }
                ?: return
            if (goal.rounds >= goal.maxRounds) {
                store.setStatus(conversationId, AgentGoalStatus.BLOCKED, blockedReason = GOAL_BLOCKED_ROUND_LIMIT)
                return
            }
            val updated = store.recordRound(conversationId) ?: return
            OverlayController.setStatus("Goal ${updated.rounds}/${updated.maxRounds}")
            dataRepository.ask(
                "[SYSTEM] Continue working on the active goal: ${goal.objective}. " +
                    "This is automatic round ${updated.rounds} of ${updated.maxRounds}. " +
                    "Carry on from where you stopped; do not repeat work already done. " +
                    "When the goal is fully achieved, call update_goal with status=completed. " +
                    "If you cannot make progress, call update_goal with status=blocked and say why.",
                emptyList(),
            )
            if (_state.value.isInteractiveMode) retryIfNoValidKaiUi()
        }
    }

    private fun setGoalStatus(status: AgentGoalStatus) {
        val store = goalStore ?: return
        val conversationId = dataRepository.currentConversationId.value.orEmpty()
        val current = store.forConversation(conversationId) ?: return
        // Resuming a goal that already spent its budget would block again on the next step,
        // so an explicit resume hands out a fresh budget instead of a useless one-round try.
        if (status == AgentGoalStatus.ACTIVE && current.rounds >= current.maxRounds) {
            store.start(conversationId, current.objective, current.maxRounds)
            return
        }
        store.setStatus(conversationId, status)
    }

    private fun clearHistory() {
        dataRepository.clearHistory()
        _state.update {
            it.copy(error = null, showFreeProviderSuggestions = false)
        }
    }

    /**
     * If [text] begins with `/<skill-id>`, look up the skill among the currently-
     * installed-and-enabled skills and return its id alongside the verbatim user
     * text. The text is sent unchanged so the conversation visibly reflects what
     * the user typed; the skill's instructions in the system prompt tell the model
     * how to parse the args after the slash command. Falls through with null skill
     * id when no match — slash commands are opt-in.
     */
    private fun parseSkillInvocation(text: String?): Pair<String?, String?> {
        if (text == null) return null to null
        val trimmed = text.trimStart()
        if (!trimmed.startsWith('/')) return text to null
        val firstSpace = trimmed.indexOfFirst { it.isWhitespace() }
        val rawId = if (firstSpace < 0) trimmed.substring(1) else trimmed.substring(1, firstSpace)
        if (rawId.isEmpty()) return text to null
        val skill = dataRepository.getInstalledSkills().firstOrNull { it.id.equals(rawId, ignoreCase = true) }
            ?: return text to null
        return text to skill.id
    }

    private fun setIsSpeaking(isSpeaking: Boolean, contentId: String) {
        _state.update {
            it.copy(
                isSpeaking = isSpeaking,
                isSpeakingContentId = if (isSpeaking) {
                    contentId
                } else {
                    it.isSpeakingContentId
                },
            )
        }
    }

    private fun addFile(file: PlatformFile) {
        val ext = file.extension.lowercase()
        val supported = dataRepository.supportedFileExtensions()
        if (ext.isEmpty() || ext !in supported) {
            _state.update {
                it.copy(snackbarMessage = Res.string.error_unsupported_file_type)
            }
            return
        }
        _state.update {
            it.copy(files = (it.files + file).toImmutableList())
        }
    }

    private fun removeFile(file: PlatformFile) {
        _state.update {
            it.copy(files = it.files.filterNot { f -> f == file }.toImmutableList())
        }
    }

    private fun clearSnackbar() {
        _state.update {
            it.copy(snackbarMessage = null)
        }
    }

    private fun retry() {
        ask(null)
    }

    private fun toggleSpeechOutput() {
        _state.update {
            it.copy(
                isSpeechOutputEnabled = !it.isSpeechOutputEnabled,
            )
        }
    }

    private fun cancel() {
        currentJob?.cancel()
        currentJob = null
        // A pending approval would otherwise keep the tool coroutine suspended forever after
        // the user aborted the run.
        ToolApprovalController.cancelPending()
        _state.update {
            it.copy(isLoading = false)
        }
    }

    /**
     * Tapping a checklist row toggles it. A completed step is allowed to go back to pending
     * because the model sometimes finishes a step early and then resumes the same work.
     */
    private fun toggleTodo(itemId: String) {
        val store = todoStore ?: return
        val conversationId = dataRepository.currentConversationId.value.orEmpty()
        val current = store.forConversation(conversationId).firstOrNull { it.id == itemId } ?: return
        val next = if (current.status == TodoStatus.COMPLETED) TodoStatus.PENDING else TodoStatus.COMPLETED
        store.setStatus(conversationId, itemId, next)
    }

    private fun selectService(instanceId: String) {
        val freeMode = FREE_MODE_INSTANCE_IDS[instanceId]
        if (freeMode != null) {
            dataRepository.setFreeMode(freeMode)
            dataRepository.setFreeServicePrimary(true)
            updateAvailableServices()
            return
        }

        dataRepository.setFreeServicePrimary(false)
        val instances = dataRepository.getConfiguredServiceInstances()
        val currentIds = instances.map { it.instanceId }
        if (instanceId !in currentIds) return
        val reordered = listOf(instanceId) + currentIds.filter { it != instanceId }
        dataRepository.reorderConfiguredServices(reordered)
        updateAvailableServices()
    }

    /**
     * Persists the reasoning intensity for the model that is currently in use. Effort is stored
     * per model id (not per service instance) because it describes the model's behaviour, so the
     * same model keeps its setting no matter which instance or provider is selected.
     */
    private fun changeThinkingEffort(effort: ThinkingEffort) {
        val current = _state.value.availableServices.firstOrNull() ?: return
        val modelId = current.modelId.ifEmpty {
            dataRepository.getServiceEntries().firstOrNull()?.modelId.orEmpty()
        }
        if (modelId.isEmpty()) return
        dataRepository.setThinkingEffort(modelId, effort)
        _state.update { state ->
            state.copy(
                availableServices = state.availableServices
                    .map { if (it.modelId == modelId) it.copy(thinkingEffort = effort) else it }
                    .toImmutableList(),
            )
        }
    }

    private fun updateAvailableServices() {
        val configuredEntries = dataRepository.getServiceEntries()
        val currentFreeMode = dataRepository.getFreeMode()
        val freeIsPrimary = dataRepository.isFreeServicePrimary() || configuredEntries.isEmpty()

        val freeModes = (listOf(currentFreeMode) + FreeMode.entries.filter { it != currentFreeMode }).map { mode ->
            ServiceEntry(
                instanceId = mode.instanceId,
                serviceId = Service.Free.id,
                serviceName = freeModeNames.getValue(mode),
                modelId = "",
                icon = mode.icon,
            )
        }

        // Reasoning effort is stored per model id, so re-read it on every refresh. Free-mode
        // entries carry no model id; they report the effort of whatever model they resolve to.
        val withEffort = (if (freeIsPrimary) freeModes + configuredEntries else configuredEntries + freeModes)
            .map { entry ->
                val modelId = entry.modelId.ifEmpty {
                    configuredEntries.firstOrNull()?.modelId.orEmpty()
                }
                if (modelId.isEmpty()) {
                    entry
                } else {
                    // Free-mode entries carry no model id, so they inherit the resolved model's
                    // capability too — otherwise the effort chips would never appear for them
                    // even when the underlying model supports reasoning effort.
                    val service = Service.fromId(entry.serviceId)
                    entry.copy(
                        thinkingEffort = dataRepository.getThinkingEffort(modelId),
                        supportsThinkingEffort = supportsReasoningEffort(service, modelId),
                    )
                }
            }
        val entries = withEffort.toImmutableList()

        // The context window belongs to the model that will actually answer. Free-mode entries
        // carry no model id of their own, so they inherit whatever model they resolve to — the
        // same fallback the reasoning-effort chips use.
        val activeModelId = entries.firstOrNull()?.let { entry ->
            entry.modelId.ifEmpty { configuredEntries.firstOrNull()?.modelId.orEmpty() }
        }.orEmpty()
        // A user override wins over the catalog estimate, matching what the request path trims to.
        val activeContextWindow = activeModelId
            .takeIf { it.isNotEmpty() }
            ?.let { id ->
                val override = dataRepository.getModelContextTokens(id)
                (if (override > 0) override else ModelCatalog.estimateContextWindow(id)).toLong()
            }

        val primaryService = entries.firstOrNull()?.let { Service.fromId(it.serviceId) }
        val warning = if (primaryService?.isOnDevice == true && dataRepository.getLocalDownloadedModels().isEmpty()) {
            Res.string.litert_no_model_warning
        } else {
            null
        }
        _state.update {
            it.copy(
                availableServices = entries,
                warning = warning,
                showPrivacyInfo = dataRepository.isUsingSharedKey(),
                contextWindow = activeContextWindow,
            )
        }
    }

    companion object {
        private val FREE_MODE_INSTANCE_IDS = FreeMode.entries.associateBy { it.instanceId }
    }

    private fun regenerate() {
        dataRepository.regenerate()
        ask(null)
    }

    private fun loadConversation(id: String) {
        currentJob?.cancel()
        currentJob = null
        val conversation = dataRepository.savedConversations.value.find { it.id == id }
        val isInteractive = conversation?.type == Conversation.TYPE_INTERACTIVE
        dataRepository.setInteractiveMode(isInteractive)
        dataRepository.loadConversation(id)
        _state.update {
            it.copy(
                error = null,
                showFreeProviderSuggestions = false,
                isInteractiveMode = isInteractive,
                isLoading = false,
                composerPrefill = null,
            )
        }
    }

    private fun deleteConversation(id: String) {
        commitPendingConversationDeletion()
        _state.update { it.copy(pendingConversationDeletion = id) }
        pendingConversationDeleteJob = viewModelScope.launch(backgroundDispatcher) {
            delay(4.seconds)
            dataRepository.deleteConversation(id)
            _state.update { it.copy(pendingConversationDeletion = null) }
        }
    }

    private fun undoDeleteConversation() {
        pendingConversationDeleteJob?.cancel()
        pendingConversationDeleteJob = null
        _state.update { it.copy(pendingConversationDeletion = null) }
    }

    private fun commitPendingConversationDeletion() {
        pendingConversationDeleteJob?.cancel()
        pendingConversationDeleteJob = null
        val pendingId = _state.value.pendingConversationDeletion ?: return
        _state.update { it.copy(pendingConversationDeletion = null) }
        viewModelScope.launch(backgroundDispatcher) {
            dataRepository.deleteConversation(pendingId)
        }
    }

    override fun onCleared() {
        commitPendingConversationDeletion()
        // The scheduler lives longer than this ViewModel (it's a singleton driving the
        // Android foreground service). Reset the predicate so the daemon path keeps
        // running without a stale reference to a dead state flow. The foreground-visible
        // signal (`appInForeground`) is tracked separately via `ProcessLifecycleOwner`
        // on Android — ViewModel lifecycle is too narrow (survives backgrounding).
        taskScheduler.isLoadingCheck = { false }
        super.onCleared()
    }

    private fun clearUnreadHeartbeat() {
        dataRepository.clearUnreadHeartbeat()
    }

    private fun sendSmsDraft(draftId: String) {
        viewModelScope.launch(backgroundDispatcher) {
            dataRepository.sendSmsDraft(draftId)
        }
    }

    private fun discardSmsDraft(draftId: String) {
        viewModelScope.launch(backgroundDispatcher) {
            dataRepository.discardSmsDraft(draftId)
        }
    }

    private fun startNewChat(composerPrefill: String? = null) {
        currentJob?.cancel()
        currentJob = null
        dataRepository.startNewChat()
        dataRepository.setInteractiveMode(false)
        _state.update {
            it.copy(
                error = null,
                showFreeProviderSuggestions = false,
                isInteractiveMode = false,
                isLoading = false,
                composerPrefill = composerPrefill,
            )
        }
    }

    private fun consumeComposerPrefill() {
        _state.update { it.copy(composerPrefill = null) }
    }

    private fun enterInteractiveMode() {
        dataRepository.startNewChat()
        dataRepository.setInteractiveMode(true)
        _state.update {
            it.copy(
                isInteractiveMode = true,
                error = null,
                showFreeProviderSuggestions = false,
            )
        }
    }

    private fun exitInteractiveMode() {
        currentJob?.cancel()
        currentJob = null
        dataRepository.startNewChat()
        dataRepository.setInteractiveMode(false)
        _state.update {
            it.copy(
                isInteractiveMode = false,
                isLoading = false,
                error = null,
                showFreeProviderSuggestions = false,
            )
        }
    }

    private fun resubmit(messageId: String, event: String, data: Map<String, String>) {
        if (_state.value.isLoading) return
        dataRepository.truncateFrom(messageId)
        submitUiCallback(event, data)
    }

    private fun goBackInteractiveMode() {
        val userCount = dataRepository.chatHistory.value.count { it.role == History.Role.USER }
        if (userCount <= 1) {
            // Go back to initial prompt — clear history but stay in interactive mode
            dataRepository.clearHistory()
        } else {
            dataRepository.popLastExchange()
        }
    }

    fun refreshSettings() {
        updateAvailableServices()
        viewModelScope.launch(backgroundDispatcher) {
            dataRepository.restoreCurrentConversation()
            presetInteractiveModeForCurrentConversation()
        }
    }

    /**
     * Resolves the interactive mode flag from the currently-loaded conversation, or — when
     * there is no loaded conversation (new empty chat) — falls back to the persisted flag.
     */
    private fun presetInteractiveModeForCurrentConversation() {
        val currentId = dataRepository.currentConversationId.value
        val conversation = dataRepository.savedConversations.value.find { it.id == currentId }
        val isInteractive = if (conversation != null) {
            conversation.type == Conversation.TYPE_INTERACTIVE
        } else {
            dataRepository.isInteractiveModeActive()
        }
        dataRepository.setInteractiveMode(isInteractive)
        _state.update { it.copy(isInteractiveMode = isInteractive) }
    }

    /** Switches the next turn between one model call and a planned swarm run. */
    fun toggleTeamMode() {
        _state.update { it.copy(isTeamMode = !it.isTeamMode) }
    }

    /**
     * Runs one turn through the swarm (planner + workers) instead of a single model call.
     *
     * The plan and every finished step are mirrored into chat state and into the floating window:
     * a swarm run can take minutes, and progress that only appears at the end is indistinguishable
     * from a hang. The planner and workers reuse [DataRepository.askSilently] as their model, so
     * team mode needs no extra credentials or client.
     */
    private suspend fun runTeamTurn(instruction: String) {
        _state.update {
            it.copy(
                isTeamRunning = true,
                teamInstruction = instruction,
                teamPlan = persistentListOf(),
                teamResults = persistentListOf(),
                teamSummary = "",
            )
        }
        val complete: suspend (String) -> String = { prompt -> dataRepository.askSilently(prompt) }
        val allTools = getAvailableTools()
        val workers: Map<WorkerKind, Worker> = WorkerKind.entries.associateWith { kind ->
            ToolBackedWorker(kind = kind, tools = toolsFor(kind, allTools), complete = complete)
        }
        val orchestrator = SwarmOrchestrator(
            planner = LlmPlanner(complete),
            workers = workers,
            blackboard = InMemoryBlackboard(),
        )
        val result = orchestrator.run(
            request = SwarmRequest(instruction = instruction),
            onPlan = { plan ->
                _state.update { it.copy(teamPlan = plan.steps.toImmutableList()) }
                OverlayController.setPlan(
                    plan.steps.map { step -> OverlayPlanStep(id = step.id, text = step.description) },
                )
            },
            onStep = { step ->
                _state.update { it.copy(teamResults = (it.teamResults + step).toImmutableList()) }
                OverlayController.setStepStatus(step.stepId, step.status.toOverlayStatus())
                OverlayController.logTool("${step.workerKind} ${step.stepId} → ${step.status.name}")
            },
        )
        _state.update { it.copy(teamSummary = result.summary(), isTeamRunning = false) }
    }

    /**
     * Hands each worker only the tools its role can use. GENERAL keeps the full set so the
     * degraded single-step plan (used when planning fails) still has every capability.
     */
    private fun toolsFor(kind: WorkerKind, tools: List<Tool>): List<Tool> = when (kind) {
        WorkerKind.UI -> tools.filter { it.matchesAny(UI_TOOL_HINTS) }
        WorkerKind.SYSTEM -> tools.filter { it.matchesAny(SYSTEM_TOOL_HINTS) }
        WorkerKind.RESEARCH -> tools.filter { it.matchesAny(RESEARCH_TOOL_HINTS) }
        WorkerKind.GENERAL -> tools
    }

    private fun Tool.matchesAny(hints: List<String>): Boolean {
        val name = schema.name.lowercase()
        return hints.any { hint -> name.contains(hint) }
    }

    private fun StepStatus.toOverlayStatus(): OverlayStepStatus = when (this) {
        StepStatus.PENDING -> OverlayStepStatus.PENDING
        StepStatus.RUNNING -> OverlayStepStatus.RUNNING
        StepStatus.DONE -> OverlayStepStatus.DONE
        StepStatus.FAILED -> OverlayStepStatus.FAILED
        StepStatus.SKIPPED -> OverlayStepStatus.SKIPPED
    }
}

/** Tool-name fragments per worker role; matched case-insensitively against [Tool.schema].name. */
private val UI_TOOL_HINTS = listOf("android_ui", "android_files", "android_context")
private val SYSTEM_TOOL_HINTS = listOf("android_shell", "android_files", "android_context", "notification")
private val RESEARCH_TOOL_HINTS = listOf("android_files", "android_context", "web", "search", "memory")

/** Stored in [AgentGoal.blockedReason] so the card can localise the reason instead of baking text in. */
private const val GOAL_BLOCKED_ROUND_LIMIT = "round_limit"
