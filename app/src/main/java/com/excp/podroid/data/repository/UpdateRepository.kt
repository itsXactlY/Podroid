package com.excp.podroid.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.excp.podroid.BuildConfig
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val latestVersion: String,
    val releaseUrl: String,
    /** Direct download URL of the release's `.apk` asset, or null if the
     *  release has no APK attached (then the UI falls back to opening
     *  [releaseUrl] in a browser). */
    val apkUrl: String? = null,
    /** Size in bytes of the APK asset (0 when unknown) — used for the
     *  download progress bar. */
    val apkSize: Long = 0L,
)

/**
 * Returns true when the cache has expired and a fresh network check is warranted.
 *
 * Uses wall-clock milliseconds (currentTimeMillis) — not uptimeMillis, which
 * resets to zero on reboot and causes the post-reboot delta to be a large
 * negative number, permanently skipping the check. A negative delta is clamped
 * to "stale" so a clock going backwards also triggers a re-check rather than
 * suppressing it indefinitely.
 */
internal fun shouldCheck(now: Long, lastCheck: Long, validityMs: Long): Boolean {
    val elapsed = now - lastCheck
    return elapsed < 0L || elapsed >= validityMs
}

/**
 * Returns true if `latest` is a higher version than `current`. Compares the
 * numeric core (`1.2.3`) first; if those are equal, treats a prerelease suffix
 * (`-rc2`) as lower than a release, and compares prerelease numeric chunks
 * numerically so `rc10 > rc9`.
 */
internal fun isNewer(latest: String, current: String): Boolean {
    fun splitCore(v: String) = v.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
    val l = splitCore(latest)
    val c = splitCore(current)
    val maxLen = maxOf(l.size, c.size)
    for (i in 0 until maxLen) {
        val lv = l.getOrElse(i) { 0 }
        val cv = c.getOrElse(i) { 0 }
        if (lv > cv) return true
        if (lv < cv) return false
    }
    // Numeric cores match — break ties by suffix. Empty suffix > any prerelease suffix.
    val ls = latest.substringAfter("-", "")
    val cs = current.substringAfter("-", "")
    return when {
        ls == cs -> false
        ls.isEmpty() -> true   // "1.2.0" is newer than "1.2.0-rc1"
        cs.isEmpty() -> false  // "1.2.0-rc1" is older than "1.2.0"
        else -> comparePrerelease(ls, cs) > 0
    }
}

/**
 * Compares two prerelease strings (the part after `-`) by splitting on
 * non-numeric/numeric boundaries and comparing each chunk: numeric chunks
 * compare as integers, non-numeric chunks compare lexicographically.
 * Example: "rc10" > "rc9" because the numeric chunk 10 > 9.
 */
private fun comparePrerelease(a: String, b: String): Int {
    fun chunks(s: String): List<String> = buildList {
        val sb = StringBuilder()
        var numeric = s.firstOrNull()?.isDigit() ?: false
        for (ch in s) {
            if (ch.isDigit() == numeric) {
                sb.append(ch)
            } else {
                if (sb.isNotEmpty()) add(sb.toString())
                sb.clear()
                sb.append(ch)
                numeric = ch.isDigit()
            }
        }
        if (sb.isNotEmpty()) add(sb.toString())
    }

    val ac = chunks(a)
    val bc = chunks(b)
    val len = maxOf(ac.size, bc.size)
    for (i in 0 until len) {
        val ac_ = ac.getOrElse(i) { "" }
        val bc_ = bc.getOrElse(i) { "" }
        val cmp = when {
            ac_.isEmpty() && bc_.isEmpty() -> 0
            ac_.isEmpty() -> -1
            bc_.isEmpty() -> 1
            ac_.all { it.isDigit() } && bc_.all { it.isDigit() } ->
                // Clamp oversized numeric chunks to lexicographic so a malformed
                // tag (>18 digits, > Long.MAX) can't throw NumberFormatException.
                if (ac_.length <= 18 && bc_.length <= 18) ac_.toLong().compareTo(bc_.toLong())
                else ac_.compareTo(bc_)
            else -> ac_.compareTo(bc_)
        }
        if (cmp != 0) return cmp
    }
    return 0
}

@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dismissedKey = stringPreferencesKey("dismissed_update_version")
    private val lastCheckKey = longPreferencesKey("update_check_timestamp")
    private val cacheValidityMs = 24 * 60 * 60 * 1000L

    /**
     * @param force when true, bypass the 24h cache gate (for an explicit
     *   user-initiated "check now"). Automatic launch checks pass false.
     */
    suspend fun checkForUpdate(
        currentVersion: String,
        force: Boolean = false,
    ): UpdateInfo? = withContext(Dispatchers.IO) {
        // Wall-clock time so the 24h gate survives device reboots and deep sleep.
        val now = System.currentTimeMillis()
        var connection: java.net.HttpURLConnection? = null
        try {
            val lastCheck = context.dataStore.data
                .catch { e -> if (e is java.io.IOException) emit(emptyPreferences()) else throw e }
                .first()[lastCheckKey] ?: 0L

            if (!force && !shouldCheck(now, lastCheck, cacheValidityMs)) {
                return@withContext null
            }

            connection = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                .openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept", "application/vnd.github+json")

            val code = connection.responseCode
            if (code !in 200..299) {
                // Consume error body so the connection can be reused, then back off.
                runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }
                context.dataStore.edit { it[lastCheckKey] = now }
                return@withContext null
            }

            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            // removePrefix rather than trimStart — trimStart strips repeated 'v' chars.
            val tag = obj.optString("tag_name", "").removePrefix("v")
            val url = obj.optString("html_url", "")

            if (tag.isEmpty() || url.isEmpty()) {
                context.dataStore.edit { it[lastCheckKey] = now }
                return@withContext null
            }

            // Find the first `.apk` release asset for one-tap in-app install.
            // Absent (source-only release) → apkUrl stays null and the UI opens
            // the release page instead.
            var apkUrl: String? = null
            var apkSize = 0L
            val assets = obj.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url", "").ifEmpty { null }
                        apkSize = a.optLong("size", 0L)
                        if (apkUrl != null) break
                    }
                }
            }

            context.dataStore.edit { it[lastCheckKey] = now }

            // Build-type suffix (`-debug`) is decoration, not a prerelease — strip it so
            // 1.1.7-debug compares equal to the 1.1.7 release tag instead of "older".
            val normalizedCurrent = currentVersion.removeSuffix("-debug")
            if (isNewer(tag, normalizedCurrent)) UpdateInfo(tag, url, apkUrl, apkSize) else null
        } catch (c: kotlinx.coroutines.CancellationException) {
            // Coroutine cancellation is not a check failure — don't record a
            // timestamp (which would back off a check the user may retry) and
            // don't swallow it; let it propagate.
            throw c
        } catch (e: Exception) {
            // Network/DataStore/JSON error: log it (a changed GitHub response or
            // JSON shape was previously failing invisibly) and still record the
            // timestamp to back off on the next launch.
            android.util.Log.w("UpdateRepository", "update check failed", e)
            runCatching { context.dataStore.edit { it[lastCheckKey] = now } }
            null
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun isDismissed(version: String): Boolean {
        // Shield the read so a corrupted store returns "not dismissed" instead of
        // throwing into the caller.
        val dismissed = context.dataStore.data
            .catch { e -> if (e is java.io.IOException) emit(emptyPreferences()) else throw e }
            .first()[dismissedKey]
        return dismissed == version
    }

    suspend fun dismissUpdate(version: String) {
        context.dataStore.edit { it[dismissedKey] = version }
    }

    /** True if the OS will let us launch a package-install intent without first
     *  sending the user to "install unknown apps" settings. */
    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Intent that takes the user to grant "install unknown apps" for THIS app.
     *  Caller starts it (with FLAG_ACTIVITY_NEW_TASK from a non-Activity ctx). */
    fun unknownSourcesSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Download the release APK to a private, FileProvider-shareable location.
     * Streams with progress (0f..1f) and is cancellation-aware. Returns the
     * downloaded [java.io.File], or null on any failure. Each call overwrites
     * the previous download so stale APKs never accumulate.
     */
    suspend fun downloadApk(
        info: UpdateInfo,
        onProgress: (Float) -> Unit = {},
    ): java.io.File? = withContext(Dispatchers.IO) {
        val apkUrl = info.apkUrl ?: return@withContext null
        var connection: java.net.HttpURLConnection? = null
        val dir = java.io.File(context.filesDir, "updates").apply { mkdirs() }
        val out = java.io.File(dir, "iris-update.apk")
        val tmp = java.io.File(dir, "iris-update.apk.part")
        try {
            connection = URL(apkUrl).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) {
                runCatching { connection.errorStream?.close() }
                return@withContext null
            }
            val total = if (info.apkSize > 0) info.apkSize else connection.contentLengthLong
            tmp.delete()
            connection.inputStream.use { input ->
                tmp.outputStream().use { sink ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } >= 0) {
                        // Cooperative cancellation: throws CancellationException if
                        // the caller's coroutine was cancelled (e.g. screen left).
                        coroutineContext.ensureActive()
                        sink.write(buf, 0, read)
                        done += read
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                    sink.flush()
                }
            }
            // Atomic-ish publish: only a fully-written file becomes the install target.
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true); tmp.delete()
            }
            onProgress(1f)
            out
        } catch (c: kotlinx.coroutines.CancellationException) {
            runCatching { tmp.delete() }
            throw c
        } catch (e: Exception) {
            android.util.Log.w("UpdateRepository", "apk download failed", e)
            runCatching { tmp.delete() }
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Hand a downloaded APK to the system package installer. Android requires
     * the new APK to carry the SAME signing key + applicationId and a higher
     * versionCode to update in place; the system installer enforces that and
     * shows the standard confirm dialog. Returns false if the intent couldn't
     * be launched.
     */
    fun installApk(file: java.io.File): Boolean = try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        android.util.Log.w("UpdateRepository", "install intent failed", e)
        false
    }
}
