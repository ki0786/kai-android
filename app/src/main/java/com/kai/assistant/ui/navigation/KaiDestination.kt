package com.kai.assistant.ui.navigation

sealed class KaiDestination(val route: String) {
    data object Home : KaiDestination("home")
    data object Voice : KaiDestination("voice")
    data object Chat : KaiDestination("chat")
    data object Settings : KaiDestination("settings")
}
