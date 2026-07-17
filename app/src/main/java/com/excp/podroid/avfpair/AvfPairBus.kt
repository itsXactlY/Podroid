/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * AvfPairBus — the in-process shared state between the AccessibilityService
 * (PodroidSetupAssistant), the orchestrator (AvfAutoPair), and the UI
 * (AvfSetupScreen). A singleton object holding StateFlows so every layer sees
 * the same live progress without passing intents around.
 */
package com.excp.podroid.avfpair

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AvfPairBus {

    /** High-level phase: idle | enabling | opening_pair | awaiting_adb |
     *  granting | reverting | reverted | done | failed. */
    private val _phase = MutableStateFlow("idle")
    val phase = _phase.asStateFlow()

    /** Human-readable detail line for the current phase (logged + shown). */
    private val _details = MutableStateFlow("")
    val details = _details.asStateFlow()

    /** Pairing code + ports read from the Settings UI by the a11y service. */
    private val _pairingInfo = MutableStateFlow<PairingInfo?>(null)
    val pairingInfo = _pairingInfo.asStateFlow()

    /** Whether the revert (WD off) completed. */
    private val _reverted = MutableStateFlow(false)
    val reverted = _reverted.asStateFlow()

    /** Final result message (success or failure reason). */
    private val _result = MutableStateFlow<String?>(null)
    val result = _result.asStateFlow()

    fun reset() {
        _phase.value = "idle"
        _details.value = ""
        _pairingInfo.value = null
        _reverted.value = false
        _result.value = null
    }
    fun setPhase(p: String) { _phase.value = p }
    fun setDetails(d: String) { _details.value = d }
    fun setPairingInfo(i: PairingInfo?) { _pairingInfo.value = i }
    fun setRejected() { _reverted.value = true }
    fun setResult(r: String) { _result.value = r }
}

data class PairingInfo(
    val code: String,
    val pairPort: Int,
    val connectPort: Int,
)
