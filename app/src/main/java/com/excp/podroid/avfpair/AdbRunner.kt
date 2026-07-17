/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * AdbRunner — the on-device adb session that performs the AVF permission
 * grant. Drives the bundled adb (see AdbBinary) through:
 *   pair <127.0.0.1:pairPort> <code>
 *   connect <127.0.0.1:connectPort>
 *   shell pm grant <pkg> MANAGE_VIRTUAL_MACHINE
 *   shell pm grant <pkg> USE_CUSTOM_VIRTUAL_MACHINE
 *
 * Then verifies, and on success OR failure reverts the Wireless-Debugging
 * side effects: disconnect + kill the adb server so no shell session leaks.
 *
 * INVARIANTS (do not violate):
 *   - target is ALWAYS 127.0.0.1 (loopback). Never a baked or discovered LAN IP.
 *   - we use `adb pair`/`adb connect` against localhost; adbd on the device
 *     exposes Wireless Debugging on 127.0.0.1 once enabled (the pairing and
 *     connection ports are read from the Settings UI by the a11y layer, which
 *     is why this runner takes them as parameters, not constants).
 *   - the grant is ONE-TIME per device: it persists across reboot + app update
 *     (stored in /data/system/users/0/runtime-permissions.xml), so once both
 *     perms are granted=true for user 0 we never need to re-pair.
 */
package com.excp.podroid.avfpair

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.excp.podroid.engine.avf.AvfDiagnostics
import kotlinx.coroutines.delay

class AdbRunner(private val context: Context) {

    private val appId = context.packageName
    private val perms = listOf(
        "android.permission.MANAGE_VIRTUAL_MACHINE",
        "android.permission.USE_CUSTOM_VIRTUAL_MACHINE",
    )

    data class GrantResult(
        val paired: Boolean,
        val connected: Boolean,
        val granted: Boolean,
        val log: String,
    )

    /**
     * Perform the full grant dance. [pairPort] and [connectPort] come from the
     * Wireless Debugging UI (read by the a11y layer). [code] is the 6-digit
     * pairing code shown in the "Pair device with pairing code" dialog.
     *
     * Returns a result carrying how far it got + the full log (for logcat
     * tuning on-device). Never throws for adb-level failures — those are
     * reported in the result so the orchestrator can fall back to QEMU.
     */
    suspend fun grant(
        pairPort: Int,
        connectPort: Int,
        code: String,
        onLog: (String) -> Unit = {},
    ): GrantResult {
        val sb = StringBuilder()
        fun log(s: String) { Log.i(TAG, s); sb.appendLine(s); onLog(s) }

        log("grant: pairPort=$pairPort connectPort=$connectPort code=${code.replace(Regex("."), "*")}")

        // 0. Clean slate: kill any prior adb server so stale pairings/keys
        //    don't confuse the new session.
        AdbBinary.run(context, listOf("kill-server"), timeoutSec = 10)
        delay(500)

        // 1. pair
        val pairR = AdbBinary.run(
            context,
            listOf("pair", "127.0.0.1:$pairPort", code.trim()),
            timeoutSec = 25,
        )
        val paired = pairR.success || pairR.output.contains("Successfully paired", ignoreCase = true)
        log("pair -> success=$paired exit=${pairR.exitCode} out=${pairR.output.take(300)}")
        if (!paired) {
            return GrantResult(false, false, false, sb.toString()).also {
                revert(log)
            }
        }

        // 2. connect (loopback). Some Android versions need connect after pair.
        val connR = AdbBinary.run(
            context,
            listOf("connect", "127.0.0.1:$connectPort"),
            timeoutSec = 20,
        )
        val connected = connR.success || connR.output.contains("connected to", ignoreCase = true)
        log("connect -> success=$connected exit=${connR.exitCode} out=${connR.output.take(300)}")
        if (!connected) {
            return GrantResult(true, false, false, sb.toString()).also {
                revert(log)
            }
        }

        // 3. grant both perms
        var allGranted = true
        for (p in perms) {
            val r = AdbBinary.run(context, listOf("shell", "pm", "grant", appId, p), timeoutSec = 20)
            val ok = r.success
            log("grant $p -> exit=${r.exitCode} out=${r.output.take(200)}")
            if (!ok) allGranted = false
        }

        // 4. verify via the same probe the engine uses
        val probe = AvfDiagnostics.probe(context)
        val verified = probe.managePermissionGranted && probe.customPermissionGranted
        log("verify perms: manage=${probe.managePermissionGranted} custom=${probe.customPermissionGranted} -> verified=$verified")

        // 5. On ANY failure, revert so we leave the device as we found it.
        if (!verified) revert(log)

        return GrantResult(paired, connected, verified, sb.toString())
    }

    /**
     * Revert Wireless-Debugging side effects: disconnect + kill adb server.
     * We deliberately do NOT toggle Wireless Debugging itself off here — the
     * a11y layer owns that (it remembers whether it enabled it) so we don't
     * fight over the toggle. We just tear down our own adb session.
     */
    fun revert(log: (String) -> Unit = {}) {
        runCatching {
            AdbBinary.run(context, listOf("disconnect", "127.0.0.1:*"), timeoutSec = 10)
        }.onFailure { log("disconnect failed: ${it.message}") }
        runCatching {
            AdbBinary.run(context, listOf("kill-server"), timeoutSec = 10)
        }.onFailure { log("kill-server failed: ${it.message}") }
        log("adb session reverted (disconnect + kill-server)")
    }

    /**
     * Idempotent check: are both AVF perms already granted? Used to skip the
     * whole flow on subsequent launches once the one-time grant is in place.
     */
    fun alreadyGranted(): Boolean {
        val pm = context.packageManager
        return perms.all {
            pm.checkPermission(it, appId) == PackageManager.PERMISSION_GRANTED
        }
    }

    companion object {
        private const val TAG = "AvfAutoPair"
    }
}
