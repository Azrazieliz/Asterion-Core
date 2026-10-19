package com.ailm.android.runtime

import android.content.Context
import android.content.Intent

/**
 * Opens Round Sync without reading any private rclone configuration, tokens,
 * or task database. Cloud document access uses Android SAF in the UI.
 */
class RoundSyncBridge(context: Context) {
    private val appContext = context.applicationContext

    data class Result(val accepted: Boolean, val message: String)

    companion object {
        const val DEFAULT_PACKAGE = "de.felixnuesse.extract"
        private val PACKAGE_PATTERN =
            Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    }

    fun openApp(packageName: String): Result {
        val name = packageName.trim()
        if (name.length !in 3..180 || !PACKAGE_PATTERN.matches(name)) {
            return Result(false, "Invalid Round Sync application package.")
        }
        val launcher = appContext.packageManager.getLaunchIntentForPackage(name)
            ?: return Result(false, "Round Sync not found. Confirm the package name and installed variant.")
        return try {
            launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(launcher)
            Result(true, "Opened Round Sync.")
        } catch (error: Exception) {
            Result(false, "Unable to open Round Sync: ${error.javaClass.simpleName}")
        }
    }
}
