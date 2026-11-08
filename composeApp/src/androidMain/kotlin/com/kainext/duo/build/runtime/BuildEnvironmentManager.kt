package com.kainext.duo.build.runtime

import android.content.Context
import com.kainext.duo.build.KaiBuildState
import com.kainext.duo.linux.LinuxPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class BuildEnvironmentManager(
    private val paths: LinuxPaths,
    initialState: KaiBuildState = KaiBuildState(),
) {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<KaiBuildState> = _state

    var onEnvironmentRemoved: (() -> Unit)? = null
    var onEnvironmentChanged: (() -> Unit)? = null

    constructor(context: Context, paths: LinuxPaths) : this(paths)

    fun install(agentIds: Set<String>) {}
    fun cancel() {}
    fun uninstall() {}
    fun refresh() {}
    fun createProject(name: String): String? = null
    fun deleteProject(name: String) {}
    fun renameProject(name: String, newName: String): String? = null
    fun startSession(project: String, agentId: String?) {}
    fun selectSession(id: String) {}
    fun closeSession(id: String) {}
    fun resumeProject(project: String): Boolean = false
    fun leaveProject(project: String) {}
    fun writeToTerminal(text: String) {}
    fun resizeTerminal(columns: Int, rows: Int) {}
}
