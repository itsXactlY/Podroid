package com.excp.podroid.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
    /** Expected SHA-256 of the APK (hex, lowercase), or null if the manifest
     *  didn't carry one. When present, [UpdateRepository.downloadApk] verifies
     *  the download against it before handing the file to the installer. */
    val sha256: String? = null,
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
    private val etagKey = stringPreferencesKey("update_check_etag")
    // Mirror of the last 200 response's manifest entry for THIS app. A 304
    // means "the manifest is unchanged", NOT "no update available" — the
    // previously-seen latest version may still be newer than the currently
    // installed one (e.g. a download+install was started but never actually
    // completed). Re-deriving from these cached fields on 304 fixes that:
    // without it, one failed/abandoned install permanently hides the pending
    // update until the manifest changes again.
    private val cachedLatestVersionKey = stringPreferencesKey("update_cached_latest_version")
    private val cachedReleaseUrlKey = stringPreferencesKey("update_cached_release_url")
    private val cachedApkUrlKey = stringPreferencesKey("update_cached_apk_url")
    private val cachedApkSizeKey = longPreferencesKey("update_cached_apk_size")
    private val cachedSha256Key = stringPreferencesKey("update_cached_sha256")
    private val cacheValidityMs = 24 * 60 * 60 * 1000L

    // A failed check (network hiccup, transient GitHub API error, unauthenticated
    // rate limit exhausted — 60 req/hour/IP, trivially shared by every device on
    // the same home/office NAT) must NOT cost a full 24h blackout: back off only
    // an hour so the next app open can recover instead of silently staying dark
    // for a day. This was the actual bug behind the autoupdater never firing —
    // any non-2xx/exception armed the full 24h gate with zero logging, so a
    // single rate-limited check (very easy to hit while iterating on this repo
    // from one home IP) silently disabled updates until the next day, at which
    // point another burst of dev/test traffic could exhaust the quota again.
    private val failureBackoffMs = 60 * 60 * 1000L

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
            val prefs = context.dataStore.data
                .catch { e -> if (e is java.io.IOException) emit(emptyPreferences()) else throw e }
                .first()
            val lastCheck = prefs[lastCheckKey] ?: 0L
            val cachedEtag = prefs[etagKey]

            if (!force && !shouldCheck(now, lastCheck, cacheValidityMs)) {
                return@withContext null
            }

            connection = URL(BuildConfig.UPDATE_MANIFEST_URL)
                .openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept", "application/json")
            // Conditional GET: a 304 (unchanged since our last check) skips body
            // transfer entirely — cheap for a device that checks once a day.
            if (cachedEtag != null) connection.setRequestProperty("If-None-Match", cachedEtag)

            val code = connection.responseCode

            if (code == 304) {
                // Unchanged since last check — cheap, and NOT a failure: use the
                // normal full-length cache window, not the short failure backoff.
                context.dataStore.edit { it[lastCheckKey] = now }
                // Re-evaluate against the CACHED manifest entry rather than
                // assuming "unchanged manifest" means "no update" — the
                // currently-installed version is what changes here, not the
                // manifest, whenever a prior download+install attempt didn't
                // actually take effect.
                val cachedLatest = prefs[cachedLatestVersionKey] ?: return@withContext null
                val normalizedCurrent = currentVersion.removeSuffix("-debug")
                if (!isNewer(cachedLatest, normalizedCurrent)) return@withContext null
                return@withContext UpdateInfo(
                    latestVersion = cachedLatest,
                    releaseUrl = prefs[cachedReleaseUrlKey] ?: "",
                    apkUrl = prefs[cachedApkUrlKey],
                    apkSize = prefs[cachedApkSizeKey] ?: 0L,
                    sha256 = prefs[cachedSha256Key],
                )
            }

            if (code !in 200..299) {
                // Consume error body so the connection can be reused.
                val body = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                android.util.Log.w(
                    "UpdateRepository",
                    "update check got HTTP $code from ${BuildConfig.UPDATE_MANIFEST_URL}: ${body?.take(300)}"
                )
                // Short backoff, not the full 24h gate — a transient/rate-limited
                // failure should not silently disable updates for a full day.
                context.dataStore.edit { it[lastCheckKey] = now - cacheValidityMs + failureBackoffMs }
                return@withContext null
            }

            connection.getHeaderField("ETag")?.let { newEtag ->
                context.dataStore.edit { it[etagKey] = newEtag }
            }

            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val manifest = JSONObject(json)
            val apps = manifest.optJSONArray("apps")
            // The manifest bundles every Box app (podroid/mazemaker/iris/thebox) in
            // one file; find the entry for THIS app. Debug builds carry a
            // ".debug" applicationId suffix that never appears in the manifest —
            // strip it so a debug build can still see (and test against) the
            // real release entry.
            val ourId = context.packageName.removeSuffix(".debug")
            var entry: JSONObject? = null
            if (apps != null) {
                for (i in 0 until apps.length()) {
                    val a = apps.optJSONObject(i) ?: continue
                    if (a.optString("id") == ourId) { entry = a; break }
                }
            }
            if (entry == null) {
                android.util.Log.w("UpdateRepository", "update check: no manifest entry for $ourId")
                context.dataStore.edit { it[lastCheckKey] = now }
                return@withContext null
            }

            val latestVersion = entry.optString("versionName", "")
            val apkUrl = entry.optString("apkUrl", "").ifEmpty { null }
            val apkSize = entry.optLong("sizeBytes", 0L)
            val sha256 = entry.optString("sha256", "").ifEmpty { null }

            if (latestVersion.isEmpty() || apkUrl == null) {
                android.util.Log.w("UpdateRepository", "update check: manifest entry for $ourId missing versionName/apkUrl")
                context.dataStore.edit { it[lastCheckKey] = now }
                return@withContext null
            }

            context.dataStore.edit {
                it[lastCheckKey] = now
                it[cachedLatestVersionKey] = latestVersion
                it[cachedReleaseUrlKey] = apkUrl
                it[cachedApkUrlKey] = apkUrl
                it[cachedApkSizeKey] = apkSize
                if (sha256 != null) it[cachedSha256Key] = sha256 else it.remove(cachedSha256Key)
            }

            // Build-type suffix (`-debug`) is decoration, not a prerelease — strip it so
            // 1.1.7-debug compares equal to the 1.1.7 release tag instead of "older".
            val normalizedCurrent = currentVersion.removeSuffix("-debug")
            val newer = isNewer(latestVersion, normalizedCurrent)
            android.util.Log.i(
                "UpdateRepository",
                "update check ok: latest=$latestVersion current=$normalizedCurrent newer=$newer"
            )
            if (newer) UpdateInfo(latestVersion, apkUrl, apkUrl, apkSize, sha256) else null
        } catch (c: kotlinx.coroutines.CancellationException) {
            // Coroutine cancellation is not a check failure — don't record a
            // timestamp (which would back off a check the user may retry) and
            // don't swallow it; let it propagate.
            throw c
        } catch (e: Exception) {
            // Network/DataStore/JSON error: log it (a changed GitHub response or
            // JSON shape was previously failing invisibly) and back off only an
            // hour, not the full 24h gate — see failureBackoffMs above.
            android.util.Log.w("UpdateRepository", "update check failed", e)
            runCatching {
                context.dataStore.edit { it[lastCheckKey] = now - cacheValidityMs + failureBackoffMs }
            }
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
            // Hash while streaming (one pass) so a verified download costs nothing
            // extra — the manifest carries a sha256 per app; a mismatch means a
            // corrupted transfer or a tampered/wrong file, and must never reach
            // the installer silently.
            val digest = java.security.MessageDigest.getInstance("SHA-256")
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
                        digest.update(buf, 0, read)
                        done += read
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                    sink.flush()
                }
            }
            if (info.sha256 != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(info.sha256, ignoreCase = true)) {
                    android.util.Log.w(
                        "UpdateRepository",
                        "apk download sha256 mismatch: expected ${info.sha256}, got $actual — refusing to install"
                    )
                    runCatching { tmp.delete() }
                    return@withContext null
                }
            }
            // Atomic-ish publish: only a fully-written, hash-verified file becomes
            // the install target.
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
    fun installApk(file: java.io.File): Boolean {
        var session: android.content.pm.PackageInstaller.Session? = null
        return try {
            val installer = context.packageManager.packageInstaller
            val params = android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            params.setSize(file.length())
            val sessionId = installer.createSession(params)
            session = installer.openSession(sessionId)
            session.openWrite("podroid-update", 0, file.length()).use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            // A PendingIntent broadcast (not an implicit ACTION_VIEW) so the OS
            // hands back an EXPLICIT confirmation intent via InstallResultReceiver
            // — see that class's doc comment for why implicit resolution isn't
            // safe here (a third-party app can register an intent-filter that
            // matches the package-archive MIME type and hijack it).
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context, sessionId,
                Intent(context, InstallResultReceiver::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pendingIntent.intentSender)
            true
        } catch (e: Exception) {
            android.util.Log.w("UpdateRepository", "install session failed", e)
            runCatching { session?.abandon() }
            false
        } finally {
            session?.close()
        }
    }
}
