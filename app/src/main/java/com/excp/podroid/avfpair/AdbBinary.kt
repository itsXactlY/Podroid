/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * AdbBinary — extracts the bundled static aarch64 Android `adb` client and
 * runs it under the app's own uid.
 *
 * WHY a bundled adb?
 *   The AVF permission grant (`pm grant ... MANAGE_VIRTUAL_MACHINE` /
 *   `USE_CUSTOM_VIRTUAL_MACHINE`) must run in the shell-uid (2000) context,
 *   reachable on-device only via Wireless Debugging (Android 11+). We pair +
 *   connect to the local adbd over loopback (127.0.0.1) and then issue the
 *   grant from a bundled adb client — no PC, no root, no network-hosted IP.
 *
 * BINARY SOURCE:
 *   `libadb.so` is placed in `app/src/main/jniLibs/arm64-v8a/` and lands in
 *   `applicationInfo.nativeLibraryDir` at install. It is a *static* aarch64
 *   Android (API 24+, NDK r29) `adb` client binary sourced from the Termux
 *   `android-tools` package (`android-tools_35.0.2-8_aarch64.deb`, adb at
 *   `data/data/com.termux/files/usr/bin/adb`) and renamed to `libadb.so`.
 *   It is named `libadb.so` so the packager keeps it executable and extracts
 *   it to disk (W^X: only files in the app's own dirs are exec-permitted). We
 *   copy it into filesDir so we can chmod 0700 and isolate HOME/ADBKEYS to a
 *   private dir.
 *
 * The binary is STATIC (no libc dependency beyond the NDK's static libc), so it
 * runs on any arm64 Android 11+ device regardless of the vendor libc.
 */
package com.excp.podroid.avfpair

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

object AdbBinary {
    private const val TAG = "AvfAutoPair"
    private const val LIB_NAME = "libadb.so"
    private const val EXTRACTED_NAME = "libadb.so"

    /** Absolute path to the executable adb we extracted into filesDir. */
    fun adbFile(context: Context): File = File(context.filesDir, EXTRACTED_NAME)

    /**
     * Extract the bundled adb once (idempotent). Returns the executable File,
     * or throws if the binary is missing from the APK (build error) or the
     * copy fails. Native libs live in `nativeLibraryDir`; we copy to filesDir,
     * chmod 0700, and point HOME/ADBKEYS at a private subdir so adb can write
     * its key + ini without touching shared storage.
     */
    fun ensureExtracted(context: Context): File {
        val dest = adbFile(context)
        if (dest.exists() && dest.canExecute() && dest.length() > 1_000_000) {
            Log.i(TAG, "adb already extracted at ${dest.absolutePath} (${dest.length()} bytes)")
            return dest
        }
        val src = File(context.applicationInfo.nativeLibraryDir, LIB_NAME)
        Log.i(TAG, "extracting adb from ${src.absolutePath} -> ${dest.absolutePath}")
        if (!src.exists()) {
            throw IllegalStateException(
                "bundled adb ($LIB_NAME) missing from nativeLibraryDir — " +
                    "did the build forget to place it in jniLibs/arm64-v8a?"
            )
        }
        src.copyTo(dest, overwrite = true)
        if (!dest.setExecutable(true, false)) {
            throw IllegalStateException("chmod +x failed for ${dest.absolutePath}")
        }
        Log.i(TAG, "adb extracted OK (${dest.length()} bytes)")
        return dest
    }

    /**
     * Run adb with the given args. HOME and ADBKEYS are pinned to a private
     * dir so the pairing keys persist across runs (pairing is one-time) and
     * adb never touches external storage. `target` is always 127.0.0.1 — we
     * never bake a real IP.
     *
     * Returns the combined stdout+stderr trimmed, or throws on exec failure.
     * A non-zero adb exit is NOT an exception — callers inspect the output.
     */
    fun run(context: Context, args: List<String>, timeoutSec: Long = 30): AdbResult {
        val adb = ensureExtracted(context)
        val home = File(context.filesDir, "adb_home").also { it.mkdirs() }
        val cmd = mutableListOf(adb.absolutePath, "-L", "tcp:0")
        cmd += args
        Log.i(TAG, "adb run: ${cmd.joinToString(" ")}")
        val proc = ProcessBuilder(cmd)
            .directory(home)
            .redirectErrorStream(true)
            .apply {
                environment().put("HOME", home.absolutePath)
                environment().put("ADBKEYS", home.absolutePath)
                environment().put("ANDROID_SERIAL", "127.0.0.1")
            }
            .start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return AdbResult(false, "TIMEOUT after ${timeoutSec}s", -1)
        }
        val code = proc.exitValue()
        Log.i(TAG, "adb exit=$code out=${out.take(2000)}")
        return AdbResult(code == 0, out.trim(), code)
    }

    data class AdbResult(val success: Boolean, val output: String, val exitCode: Int)
}
