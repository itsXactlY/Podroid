package com.excp.podroid.data.repository

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Receives the outcome of a [PackageInstaller] session committed by
 * [UpdateRepository.installApk]. A commit() with a PendingIntent always
 * calls back here — either with STATUS_PENDING_USER_ACTION (the normal case:
 * extract the confirmation UI intent and launch it explicitly) or a terminal
 * STATUS_SUCCESS / failure code.
 *
 * Why not the older `ACTION_VIEW` + FileProvider pattern: that relies on
 * implicit intent resolution, which a THIRD-PARTY APP can hijack by
 * registering an intent-filter that matches `application/vnd.android.package-archive`
 * (observed live: a pre-installed "Huawei Health" app's
 * `HwSchemeFilterActivity` intercepted the install intent ahead of the real
 * system installer and failed with "invalid format", since it tried to parse
 * the content:// URI as one of its own deep links). The intent extracted
 * from EXTRA_INTENT here is EXPLICIT — it targets the real system installer
 * component directly, so no other app can intercept it.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirmIntent)
                    } catch (e: Exception) {
                        android.util.Log.w("InstallResultReceiver", "failed to launch install confirmation", e)
                    }
                } else {
                    android.util.Log.w("InstallResultReceiver", "STATUS_PENDING_USER_ACTION with no EXTRA_INTENT")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                android.util.Log.i("InstallResultReceiver", "install succeeded")
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                android.util.Log.w("InstallResultReceiver", "install failed: status=$status message=$message")
            }
        }
    }
}
