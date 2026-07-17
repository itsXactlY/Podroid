/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * AvfSetupViewModel — UI state holder for the one-time AVF speed-up screen.
 * Observes AvfPairBus (driven by the AccessibilityService + AdbRunner) and
 * exposes phase/detail/result to the composable. Handles the user's manual
 * actions (open accessibility settings, start, skip) and records the outcome
 * in SettingsRepository so we never re-prompt after a decision.
 */
package com.excp.podroid.avfpair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltViewModel
class AvfSetupViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val avfAutoPair: AvfAutoPair,
) : ViewModel() {

    data class UiState(
        val serviceEnabled: Boolean = false,
        val alreadyGranted: Boolean = false,
        val phase: String = "idle",
        val detail: String = "",
        val result: String? = null,
        val finished: Boolean = false,
        val success: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        // Mirror the bus into UI state.
        viewModelScope.launch {
            AvfPairBus.phase.collect { p -> _ui.update { it.copy(phase = p) } }
        }
        viewModelScope.launch {
            AvfPairBus.details.collect { d -> _ui.update { it.copy(detail = d) } }
        }
        viewModelScope.launch {
            AvfPairBus.result.collect { r ->
                if (r != null) _ui.update { it.copy(result = r) }
            }
        }
        refresh()
    }

    /** Re-read service-enabled + already-granted state (call on resume). */
    fun refresh() {
        _ui.update {
            it.copy(
                serviceEnabled = avfAutoPair.isServiceEnabled(),
                alreadyGranted = runCatching { runBlocking { avfAutoPair.alreadyGranted() } }.getOrDefault(false),
            )
        }
    }

    fun openAccessibility() = avfAutoPair.openAccessibilitySettings()

    /** Start the automatic grant flow. */
    fun start() {
        _ui.update { it.copy(finished = false, result = null, success = false) }
        avfAutoPair.start { success, msg ->
            _ui.update {
                it.copy(
                    finished = true,
                    success = success,
                    result = msg,
                    phase = if (success) "done" else "failed",
                )
            }
            viewModelScope.launch {
                settings.setAvfAutopairDone(success)
            }
        }
    }

    /** User declines the speed-up — keep QEMU, never ask again. */
    fun skip() {
        viewModelScope.launch { settings.setAvfAutopairDeclined(true) }
        _ui.update { it.copy(finished = true, success = false, result = "skipped") }
    }
}
