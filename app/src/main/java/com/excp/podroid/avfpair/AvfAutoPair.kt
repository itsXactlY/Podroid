/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * AvfAutoPair — the orchestrator that runs the full one-time AVF permission
 * grant and then flips the engine to AVF.
 *
 * FLOW
 *   1. If both perms already granted (one-time, persists across reboot/update)
 *      → just ensure EngineSelection is AVF and return success. No UI needed.
 *   2. Otherwise require the user to enable the AccessibilityService once
 *      (the only manual step). AvfSetupScreen deep-links to
 *      Settings.ACTION_ACCESSIBILITY_SETTINGS and detects enablement.
 *   3. Start PodroidSetupAssistant with GOAL_ENABLE_WD. The service walks
 *      Settings: enable Developer Options (tap Build 7x) → enable Wireless
 *      Debugging → open Pair dialog → publish [code, pairPort, connectPort].
 *   4. On pairing-info, run AdbRunner.grant(...) (pair → connect → pm grant
 *      both perms → verify).
 *   5. On success: flip EngineSelection to AVF, signal the VM to relaunch on
 *      the AVF backend, then tell the service GOAL_REVERT_WD to turn Wireless
 *      Debugging back off. Report done.
 *   6. On ANY failure: do NOT flip to AVF; leave EngineSelection as-is (AUTO
 *      → QEMU fallback) and report a clear failure so the UI can retry.
 *
 * The whole flow is idempotent: re-running detects the already-granted state
 * and short-circuits; the a11y steps are derived from live screen content, not
 * a linear script, so a partial run resumes correctly.
 *
 * This class is @Singleton + Hilt-injected so the UI and the service can share
 * the same instance. Heavy work runs on an IO coroutine scope.
 */
package com.excp.podroid.avfpair

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.EngineSelection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AvfAutoPair @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    /** True once the AccessibilityService is enabled in system settings. */
    fun isServiceEnabled(): Boolean {
        val cn = ComponentName(context, PodroidSetupAssistant::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(":").any {
            it.equals(cn.flattenToString(), ignoreCase = true) ||
                it.equals(cn.flattenToShortString(), ignoreCase = true)
        }
    }

    /** Deep-link the user to the Accessibility Settings screen. */
    fun openAccessibilitySettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    /** True if the one-time grant is already in place (skip the whole flow). */
    suspend fun alreadyGranted(): Boolean = AdbRunner(context).alreadyGranted()

    /**
     * Kick off the grant flow. Requires the a11y service to be enabled (caller
     * checks isServiceEnabled() first). [onResult] fires with success/failure.
     */
    fun start(onResult: (Boolean, String) -> Unit) {
        activeJob?.cancel()
        activeJob = scope.launch {
            runCatching { run(onResult) }
                .onFailure { e ->
                    if (e is CancellationException) return@onFailure
                    Log.e(TAG, "AvfAutoPair crashed", e)
                    AvfPairBus.setPhase("failed")
                    AvfPairBus.setResult(e.message ?: "unexpected error")
                    onResult(false, e.message ?: "unexpected error")
                }
        }
    }

    private suspend fun run(onResult: (Boolean, String) -> Unit) {
        val runner = AdbRunner(context)

        // 1. Already granted? Just make sure we're on AVF and we're done.
        if (runner.alreadyGranted()) {
            Log.i(TAG, "perms already granted — flipping to AVF, no a11y needed")
            AvfPairBus.setPhase("done")
            AvfPairBus.setDetails("permissions already granted")
            settings.setEngineSelection(EngineSelection.AVF)
            AvfPairBus.setResult("AVF already enabled")
            onResult(true, "AVF already enabled")
            return
        }

        // 2. Start the a11y service with the ENABLE_WD goal.
        AvfPairBus.reset()
        AvfPairBus.setPhase("enabling")
        val svcIntent = Intent(context, PodroidSetupAssistant::class.java).apply {
            action = PodroidSetupAssistant.ACTION_SET_GOAL
            putExtra(PodroidSetupAssistant.EXTRA_GOAL, PodroidSetupAssistant.GOAL_ENABLE_WD)
        }
        context.startService(svcIntent)
        Log.i(TAG, "started PodroidSetupAssistant (enable_wd)")

        // 3. Wait for the pairing info from the a11y service (or timeout).
        val info = waitForPairingInfo(timeoutMs = 90_000)
        if (info == null) {
            AvfPairBus.setPhase("failed")
            AvfPairBus.setResult("timed out reading pairing code — is Wireless Debugging enabled?")
            onResult(false, "timed out reading pairing code")
            return
        }
        Log.i(TAG, "got pairing info: ${info.code} / ${info.pairPort} / ${info.connectPort}")

        // 4. Run adb grant.
        AvfPairBus.setPhase("granting")
        val res = runner.grant(info.pairPort, info.connectPort, info.code) { line ->
            AvfPairBus.setDetails(line)
        }

        if (!res.granted) {
            AvfPairBus.setPhase("failed")
            AvfPairBus.setResult("grant failed: ${res.log.takeLast(400)}")
            // Revert whatever we toggled, then fall back to QEMU (no engine flip).
            revertWirelessDebugging()
            onResult(false, "grant failed — staying on QEMU/TCG")
            return
        }

        // 5. Success → flip to AVF + relaunch VM, then revert WD.
        Log.i(TAG, "grant succeeded — flipping to AVF")
        AvfPairBus.setPhase("done")
        settings.setEngineSelection(EngineSelection.AVF)
        // Relaunch signal is picked up by PodroidService via Settings change.
        AvfPairBus.setResult("AVF enabled — restarting VM on hardware acceleration")
        revertWirelessDebugging()
        onResult(true, "AVF enabled")
    }

    /** Tell the a11y service to revert (WD off) and wait for it to finish. */
    private suspend fun revertWirelessDebugging() {
        AvfPairBus.setPhase("reverting")
        val svcIntent = Intent(context, PodroidSetupAssistant::class.java).apply {
            action = PodroidSetupAssistant.ACTION_SET_GOAL
            putExtra(PodroidSetupAssistant.EXTRA_GOAL, PodroidSetupAssistant.GOAL_REVERT_WD)
        }
        // The service may not be alive if the user left Settings; restart it.
        context.startService(svcIntent)
        // Give it time to navigate + toggle. We don't block forever.
        var waited = 0
        while (waited < 60_000) {
            if (AvfPairBus.reverted.value) break
            delay(1000); waited += 1000
        }
        Log.i(TAG, "revert done (reverted=${AvfPairBus.reverted.value})")
    }

    /** Poll AvfPairBus.pairingInfo until non-null or timeout. */
    private suspend fun waitForPairingInfo(timeoutMs: Long): PairingInfo? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            AvfPairBus.pairingInfo.value?.let { return it }
            delay(500)
        }
        return null
    }

    companion object {
        private const val TAG = "AvfAutoPair"
    }
}
