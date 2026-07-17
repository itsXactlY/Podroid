/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * SettingsNode — the SINGLE SOURCE OF TRUTH for how the AccessibilityService
 * finds UI elements in the (OEM/localized) Android Settings app during the
 * AVF auto-pair flow.
 *
 * LOCALE TOLERANCE STRATEGY (read before editing):
 *   1. PREFER resource-ids. They are stable across languages. The Settings app
 *      on Pixel/stock Android exposes ids like
 *      `com.android.settings:id/switchWidget` (the toggle) and
 *      `android:id/title` / `android:id/summary` on preference rows. We match
 *      the *row* by its title text, then click the *switch* inside it by id.
 *   2. TEXT matching is a fallback and MUST cover multiple locales. The test
 *      device is a GERMAN Pixel 7 Pro, so German (de) + English (en) are
 *      required; we also include a few more (es/fr) since they are cheap.
 *   3. Matching is normalized: lowercase, strip diacritics, collapse spaces and
 *      punctuation, so "Drahtloses Debugging" and "Drahtloses  Debugging!"
 *      both hit. We match by CONTAINS (not exact) so partial/localized variants
 *      still resolve.
 *
 * IF THE FLOW BREAKS ON A DEVICE, the fix is almost always HERE: add the
 * device's localized string (or the correct resource-id) to the relevant entry.
 * Nothing else in the a11y layer hardcodes UI text.
 *
 * DO NOT hardcode only German or only English. Every text selector below lists
 * every locale we know. Claude tunes on-device by appending the real string
 * logcat prints when a match fails (the service logs the visible texts).
 */
package com.excp.podroid.avfpair

/**
 * A node selector: a set of candidate title texts (across locales) plus an
 * optional resource-id fragment. A row matches if its title text matches ANY
 * candidate (normalized contains) OR its resource-id contains [idContains]
 * (when set). Toggles are clicked via the row's switch child, not the row.
 */
data class NodeSelector(
    val id: String,
    val texts: List<String>,
    val idContains: String? = null,
)

object SettingsNode {

    /**
     * Normalize for matching: lowercase, NFD-strip diacritics, drop everything
     * that isn't a letter/digit/space, collapse whitespace.
     */
    fun norm(s: String?): String {
        if (s == null) return ""
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace("[\\p{InCombiningDiacriticalMarks}]".toRegex(), "")
            .lowercase()
            .replace("[^a-z0-9 ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    /** Does [text] match any candidate in [sel] (normalized contains)? */
    fun matches(sel: NodeSelector, text: String?): Boolean {
        val n = norm(text)
        if (n.isEmpty()) return false
        return sel.texts.any { cand -> n.contains(norm(cand)) }
    }

    // ── Developer Options ────────────────────────────────────────────────
    // Settings > About phone > Build number (tap 7x). We navigate by text.
    val DEVELOPER_OPTIONS = NodeSelector(
        "developer_options",
        listOf(
            "Entwickleroptionen", // de
            "Developer options",  // en
            "Opciones de desarrollador", // es
            "Options pour les développeurs", // fr
        ),
    )
    val ABOUT_PHONE = NodeSelector(
        "about_phone",
        listOf("Über das Telefon", "About phone", "Acerca del teléfono", "À propos du téléphone"),
    )
    val BUILD_NUMBER = NodeSelector(
        "build_number",
        listOf("Build-Nummer", "Build number", "Número de compilación", "Numéro de build"),
    )
    // "You are now a developer!" toast — we don't parse toasts; we just tap 7x
    // and then verify developer options became visible by looking for it.

    // ── Wireless Debugging ──────────────────────────────────────────────
    val WIRELESS_DEBUGGING = NodeSelector(
        "wireless_debugging",
        listOf(
            "Drahtloses Debugging",        // de
            "Wireless debugging",           // en
            "Depuración inalámbrica",       // es
            "Débogage sans fil",            // fr
        ),
    )
    val PAIR_DEVICE = NodeSelector(
        "pair_device",
        listOf(
            "Gerät mit Kopplungscode koppeln", // de
            "Pair device with pairing code",   // en
            "Emparejar dispositivo con código", // es
            "Associer un appareil avec un code", // fr
        ),
    )
    // The dialog title for the pairing-code screen.
    val PAIR_DIALOG_TITLE = NodeSelector(
        "pair_dialog_title",
        listOf(
            "Gerät mit Kopplungscode koppeln",
            "Pair device with pairing code",
            "Emparejar dispositivo con código",
            "Associer un appareil avec un code",
        ),
    )

    // ── Dialog buttons / consent ────────────────────────────────────────
    // "Allow wireless debugging on this network?" dialog
    val ALLOW_WD_DIALOG = NodeSelector(
        "allow_wd_dialog",
        listOf(
            "Drahtloses Debugging in diesem Netzwerk erlauben",
            "Allow wireless debugging on this network",
            "Permitir la depuración inalámbrica en esta red",
            "Autoriser le débogage sans fil sur ce réseau",
        ),
    )
    // RSA "Allow debugging?" dialog (the adb authorize prompt)
    val RSA_DIALOG = NodeSelector(
        "rsa_dialog",
        listOf(
            "Debugging zulassen",        // de (title often "USB-Debugging zulassen")
            "Allow USB debugging",       // en
            "Permitir la depuración USB", // es
            "Autoriser le débogage USB",  // fr
        ),
    )
    // Generic "Allow"/"Zulassen" button (used for both consent dialogs).
    val ALLOW_BUTTON = NodeSelector(
        "allow_button",
        listOf("Zulassen", "Allow", "Permitir", "Autoriser"),
    )
    // "Always allow"/"Immer zulassen" checkbox (optional, check it if present).
    val ALWAYS_ALLOW = NodeSelector(
        "always_allow",
        listOf(
            "Immer zulassen",   // de
            "Always allow",     // en
            "Permitir siempre", // es
            "Toujours autoriser", // fr
        ),
    )

    /** Resource-id fragments we trust across locales. */
    object Ids {
        const val SWITCH = "switchWidget"      // the toggle inside a preference row
        const val TITLE = "title"              // preference row title TextView
        const val SUMMARY = "summary"          // preference row summary TextView
        const val BUTTON = "button"            // generic button
        const val ALERT_BUTTON = "button1"     // AlertDialog positive button
    }
}
