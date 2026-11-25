package com.kai.assistant.voice.wake

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide wake status for the home chip and the first-run model line. */
internal object WakeRuntime {
    private val _presence = MutableStateFlow(WakePresence.Off)
    val presence: StateFlow<WakePresence> = _presence.asStateFlow()

    private val _model = MutableStateFlow<WakeModelStatus>(WakeModelStatus.Missing)
    val model: StateFlow<WakeModelStatus> = _model.asStateFlow()

    fun presence(value: WakePresence) {
        _presence.value = value
    }

    fun model(value: WakeModelStatus) {
        _model.value = value
    }
}
