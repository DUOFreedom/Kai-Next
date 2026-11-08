package com.kainext.duo.build

data class KaiBuildState(
    val isInstalled: Boolean = false,
    val isInstalling: Boolean = false,
    val currentProject: String? = null,
    val currentSession: String? = null,
    val lastError: String? = null,
    val isSessionActive: Boolean = false,
)
