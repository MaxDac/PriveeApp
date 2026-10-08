package com.privee.app.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Desired listening survives process death; running is only set by the service. */
class BackgroundListening(
    initiallyEnabled: Boolean,
    private val saveEnabled: (Boolean) -> Unit,
) {
    private val _enabled = MutableStateFlow(initiallyEnabled)
    val enabled = _enabled.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()

    fun canRun(hasAccount: Boolean, notificationsAllowed: Boolean): Boolean =
        _enabled.value && hasAccount && notificationsAllowed

    fun enable() {
        saveEnabled(true)
        _failed.value = false
        _enabled.value = true
    }

    fun disable() {
        saveEnabled(false)
        _enabled.value = false
        _failed.value = false
    }

    fun started() {
        _failed.value = false
        _running.value = true
    }

    fun stopped() {
        _running.value = false
    }

    fun startFailed() {
        _running.value = false
        _failed.value = true
    }
}
