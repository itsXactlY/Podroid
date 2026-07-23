/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * PodroidSetupAssistant — an AccessibilityService that drives the OEM Settings
 * app through the one-time toggles needed for AVF: enable Developer Options,
 * enable Wireless Debugging, surface the pairing code + ports, and (after the
 * grant) revert Wireless Debugging.
 *
 * DESIGN
 *   - It is a finite state machine advanced by onAccessibilityEvent. Each event
 *     re-evaluates the current screen and performs AT MOST ONE navigation action
 *     (one click, one read), then waits for the next event. This avoids
 *     race-condition double-clicks and lets the UI settle between steps.
 *   - All UI text matching goes through SettingsNode (locale-tolerant, multi-
 *     language). Resource-ids are used where the Settings app exposes them.
 *   - It does NOT run adb itself. When it has the pairing code + ports it
 *     publishes them on AvfPairBus; the orchestrator (AvfAutoPair) runs the
 *     bundled adb, then tells the service to REVERT (toggle WD off).
 *   - Every state transition is Log.i("AvfAutoPair", ...) so Claude can tune
 *     the matching on-device from logcat.
 *   - Idempotent + resumable: re-entering a screen re-derives the correct next
 *     action from current visible nodes, never from a brittle linear script.
 *
 * PERMISSIONS: the service is declared BIND_ACCESSIBILITY_SERVICE with
 * canRetrieveWindowContent=true and flagDefault. It may only interact with the
 * Settings package (enforced by the config + a package guard in code).
 */
package com.excp.podroid.avfpair

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicLong

class PodroidSetupAssistant : AccessibilityService() {

    companion object {
        const val TAG = "AvfAutoPair"

        // Service action intents (sent via startService with these extras).
        const val ACTION_SET_GOAL = "com.excp.podroid.avfpair.SET_GOAL"
        const val EXTRA_GOAL = "goal"
        const val GOAL_ENABLE_WD = "enable_wd"     // navigate: devopts on + WD on, then halt at pair dialog
        const val GOAL_REVERT_WD = "revert_wd"     // navigate: WD off, then stop

        // Publish current phase so the UI/orchestrator can show progress.
        fun publishPhase(phase: String, detail: String = "") {
            AvfPairBus.setPhase(phase)
            if (detail.isNotEmpty()) AvfPairBus.setDetails(detail)
            Log.i(TAG, "phase=$phase $detail")
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var goal: String = GOAL_ENABLE_WD
    // Guards against acting on the same screen twice within a debounce window
    // (Settings emits many events for one change).
    private val lastActionAt = AtomicLong(0)
    private val ACTION_DEBOUNCE_MS = 700L

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            packageNames = arrayOf("com.android.settings")
            notificationTimeout = 100
        }
        serviceInfo = info
        Log.i(TAG, "PodroidSetupAssistant connected; goal=$goal")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val root = rootInActiveWindow ?: return
        try {
            // Package guard: only act inside Settings.
            val pkg = event.packageName?.toString() ?: ""
            if (pkg != "com.android.settings") {
                // Outside Settings (e.g. our own app) — ignore but keep state.
                return
            }
            step(root)
        } catch (e: Exception) {
            Log.w(TAG, "onAccessibilityEvent error", e)
        } finally {
            root.recycle()
        }
    }

    override fun onInterrupt() { Log.i(TAG, "onInterrupt") }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_GOAL)?.let {
            goal = it
            Log.i(TAG, "goal set to $goal")
            publishPhase(if (goal == GOAL_REVERT_WD) "reverting" else "enabling")
        }
        return START_STICKY
    }

    // ── State machine ────────────────────────────────────────────────────
    private fun step(root: AccessibilityNodeInfo) {
        val visible = dumpVisibleTexts(root)
        when (goal) {
            GOAL_ENABLE_WD -> stepEnableWd(root, visible)
            GOAL_REVERT_WD -> stepRevertWd(root, visible)
        }
    }

    /**
     * ENABLE_WD sub-states (derived from what's currently on screen):
     *   1. Not in Settings yet / wrong screen → navigate toward Developer Options.
     *   2. Developer Options not enabled → go to About phone, tap Build number 7x.
     *   3. In Developer Options → enable Wireless Debugging (toggle ON).
     *   4. WD enabled → open "Pair device with pairing code", read code+ports,
     *      publish, then HALT (orchestrator runs adb). We do NOT revert here.
     */
    private fun stepEnableWd(root: AccessibilityNodeInfo, visible: List<String>) {
        val joined = visible.joinToString(" ")
        Log.v(TAG, "ENABLE_WD visible=${visible.take(40)}")

        // 4. We are on the Pair dialog → read code + ports, publish, halt.
        if (SettingsNode.matches(SettingsNode.PAIR_DIALOG_TITLE, joined) ||
            (SettingsNode.matches(SettingsNode.PAIR_DEVICE, joined) && hasPairingCode(visible))
        ) {
            val extracted = readPairingInfo(visible)
            if (extracted != null) {
                Log.i(TAG, "PAIR INFO code=${extracted.code} pairPort=${extracted.pairPort} connectPort=${extracted.connectPort}")
                AvfPairBus.setPairingInfo(extracted)
                publishPhase("awaiting_adb", "code read; orchestrator running adb")
                // Halt: orchestrator will switch goal to REVERT_WD on success.
                return
            }
        }

        // 3. Wireless Debugging toggle present → ensure ON, then open pair dialog.
        findRow(root, SettingsNode.WIRELESS_DEBUGGING)?.let { row ->
            val sw = row.findSwitch()
            if (sw != null && !sw.isChecked) {
                if (debounce()) { clickNode(sw, "WD switch ON"); publishPhase("enabling_wd") }
                return
            }
            // WD already ON → open the Pair dialog.
            if (sw != null && sw.isChecked) {
                findRow(root, SettingsNode.PAIR_DEVICE)?.let { pairRow ->
                    if (debounce()) { clickNode(pairRow, "open Pair dialog"); publishPhase("opening_pair") }
                    return
                }
            }
        }

        // 2. In Developer Options but WD not found yet → scroll / wait.
        if (SettingsNode.matches(SettingsNode.DEVELOPER_OPTIONS, joined)) {
            // Still looking for WD; it may be lower — scroll down once.
            if (debounce()) { scrollForward(root); publishPhase("in_devopts_scroll") }
            return
        }

        // 1. Not in Developer Options → enable it via About phone > Build number.
        if (SettingsNode.matches(SettingsNode.ABOUT_PHONE, joined)) {
            findRow(root, SettingsNode.BUILD_NUMBER)?.let { bn ->
                // Tap 7x (we tap repeatedly; idempotent — extra taps after
                // "you are now a developer" are harmless). One tap per event.
                if (debounce()) { clickNode(bn, "Build number tap"); publishPhase("tapping_build") }
                return
            }
        }
        // From a top-level Settings screen, open "About phone".
        findRow(root, SettingsNode.ABOUT_PHONE)?.let { ap ->
            if (debounce()) { clickNode(ap, "open About phone"); publishPhase("opening_about") }
            return
        }
        // If we see "System" (Pixel puts About phone under System), open it.
        findRow(root, NodeSelector("system", listOf("System", "System", "Sistema", "Système")))?.let { sys ->
            if (debounce()) { clickNode(sys, "open System"); publishPhase("opening_system") }
            return
        }
        Log.v(TAG, "ENABLE_WD: no actionable node on this screen yet")
    }

    /**
     * REVERT_WD: open Developer Options, toggle Wireless Debugging OFF, then
     * stop the service. If we are not in Developer Options, navigate there
     * (reuse the same path). We assume Developer Options is already enabled.
     */
    private fun stepRevertWd(root: AccessibilityNodeInfo, visible: List<String>) {
        val joined = visible.joinToString(" ")
        Log.v(TAG, "REVERT_WD visible=${visible.take(40)}")
        findRow(root, SettingsNode.WIRELESS_DEBUGGING)?.let { row ->
            val sw = row.findSwitch()
            if (sw != null && sw.isChecked) {
                if (debounce()) {
                    clickNode(sw, "WD switch OFF")
                    publishPhase("reverting_wd")
                }
                return
            }
            if (sw != null && !sw.isChecked) {
                // Done — WD is off. Stop ourselves.
                Log.i(TAG, "REVERT_WD complete; disabling service")
                publishPhase("reverted")
                AvfPairBus.setRejected()
                disableSelf()
                return
            }
        }
        // Navigate toward Developer Options (mirror ENABLE_WD entry path).
        if (SettingsNode.matches(SettingsNode.DEVELOPER_OPTIONS, joined)) {
            if (debounce()) { scrollForward(root); publishPhase("revert_scroll") }
            return
        }
        if (SettingsNode.matches(SettingsNode.ABOUT_PHONE, joined)) {
            findRow(root, SettingsNode.BUILD_NUMBER)?.let { bn ->
                if (debounce()) { clickNode(bn, "Build number tap (to reach devopts)"); publishPhase("revert_tap_build") }
            }
            return
        }
        findRow(root, SettingsNode.ABOUT_PHONE)?.let { ap ->
            if (debounce()) { clickNode(ap, "open About phone"); publishPhase("revert_open_about") }
            return
        }
        findRow(root, NodeSelector("system", listOf("System", "System", "Sistema", "Système")))?.let { sys ->
            if (debounce()) { clickNode(sys, "open System"); publishPhase("revert_open_system") }
        }
    }

    // ── Node helpers ─────────────────────────────────────────────────────

    /** Find a preference ROW whose title matches [sel]. Returns the row node. */
    private fun findRow(root: AccessibilityNodeInfo, sel: NodeSelector): AccessibilityNodeInfo? {
        val out = mutableListOf<AccessibilityNodeInfo>()
        root.findAccessibilityNodeInfosByText(sel.texts.first())
        // findAccessibilityNodeInfosByText is exact-ish; instead walk manually
        // for normalized matching across locales.
        walk(root) { node ->
            val title = node.rowTitle()
            if (title != null && SettingsNode.matches(sel, title)) out.add(node)
        }
        return out.firstOrNull()
    }

    /** The clickable row containing a title TextView matching [sel]. */
    private fun AccessibilityNodeInfo.rowTitle(): String? {
        // A preference row usually has a TextView with id 'title' or 'summary'.
        for (i in 0 until childCount) {
            val c = getChild(i) ?: continue
            val t = cText(c)
            if (!t.isNullOrEmpty()) return t
        }
        return cText(this)
    }

    private fun cText(n: AccessibilityNodeInfo?): String? {
        if (n == null) return null
        val t = n.text?.toString()
        if (!t.isNullOrEmpty()) return t
        for (i in 0 until n.childCount) {
            cText(n.getChild(i))?.let { return it }
        }
        return null
    }

    /** Find the Switch widget inside a row (by resource-id fragment). */
    private fun AccessibilityNodeInfo.findSwitch(): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(this) { n ->
            val id = n.viewIdResourceName?.toString() ?: ""
            if (id.endsWith(SettingsNode.Ids.SWITCH) && found == null) found = n
        }
        return found
    }

    private fun clickNode(node: AccessibilityNodeInfo, what: String) {
        Log.i(TAG, "click: $what")
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun scrollForward(root: AccessibilityNodeInfo) {
        root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun walk(node: AccessibilityNodeInfo, fn: (AccessibilityNodeInfo) -> Unit) {
        fn(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { walk(it, fn) }
        }
    }

    /** Collect all visible text for logging + dialog parsing. */
    private fun dumpVisibleTexts(root: AccessibilityNodeInfo): List<String> {
        val texts = mutableListOf<String>()
        walk(root) { n ->
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
        }
        return texts
    }

    /**
     * From the visible texts of the Pair dialog, extract the 6-digit code and
     * the pairing/connection ports. Wireless Debugging shows:
     *   - a 6-digit pairing code (TextView)
     *   - "Pairing device with pairing code" / a line like
     *     "127.0.0.1:12345"  (the pairing port) and on the main WD screen
     *     "127.0.0.1:54321"  (the connection port, "IP address & port").
     * We grab ANY ip:port occurrences; the orchestrator knows pairPort from
     * the dialog and connectPort from the main WD screen text.
     */
    private fun readPairingInfo(visible: List<String>): PairingInfo? {
        val code = visible.firstNotNullOfOrNull { t ->
            "\\b\\d{6}\\b".toRegex().find(t)?.value
        }
        val ports = visible.mapNotNull { t ->
            "(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}):(\\d+)".toRegex().find(t)
                ?.let { it.groupValues[1] to it.groupValues[2].toInt() }
        }
        // The pairing dialog shows one ip:port (pairPort). The main WD screen
        // shows the connect port separately; if present we capture both.
        val pairPort = ports.firstOrNull()?.second
        val connectPort = if (ports.size >= 2) ports[1].second else pairPort
        return if (code != null && pairPort != null) {
            PairingInfo(code, pairPort, connectPort ?: pairPort)
        } else null
    }

    private fun hasPairingCode(visible: List<String>): Boolean =
        visible.any { "\\b\\d{6}\\b".toRegex().containsMatchIn(it) }

    private fun debounce(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastActionAt.get() < ACTION_DEBOUNCE_MS) return false
        lastActionAt.set(now)
        return true
    }
}
