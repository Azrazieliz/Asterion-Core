package com.ailm.android.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Optional Round Sync hand-off. Never reads Round Sync's private rclone.conf,
 * account credentials, or task database. Task IDs must be copied by the user
 * from the explicitly configured Round Sync jobs.
 *
 * A successful hand-off does NOT mean that a cloud transfer has completed.
 * Newer Round Sync builds may intentionally make SyncService non-exported.
 */
class RoundSyncBridge(context: Context) {
    private val appContext = context.applicationContext

    companion object {
        const val DEFAULT_PACKAGE = "de.felixnuesse.extract"
        const val SERVICE_CLASS = "ca.pkay.rcloneexplorer.Services.SyncService"
        const val ACTION_START_TASK = "START_TASK"
        private val packagePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    }

    data class Result(val accepted: Boolean, val message: String)

    fun openApp(packageName: String): Result {
        if (!validPackage(packageName)) return Result(false, "Invalid Round Sync package name.")
        val launcher = appContext.packageManager.getLaunchIntentForPackage(packageName)
            ?: return Result(false, "Round Sync not found. Check the installed variant/package.")
        return try {
            launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(launcher)
            Result(true, "Opened Round Sync.")
        } catch (error: Exception) {
            Result(false, "Unable to open Round Sync: ${error.javaClass.simpleName}")
        }
    }

    fun requestTask(packageName: String, taskId: Int): Result {
        if (!validPackage(packageName)) return Result(false, "Invalid Round Sync package name.")
        if (taskId <= 0) return Result(false, "Enter the positive task ID copied from Round Sync.")
        val component = ComponentName(packageName, SERVICE_CLASS)
        val service = try {
            @Suppress("DEPRECATION")
            appContext.packageManager.getServiceInfo(component, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return Result(false, "Round Sync's task service is unavailable. Run the task inside Round Sync or use a writable SAF provider.")
        } catch (_: SecurityException) {
            return Result(false, "Round Sync does not allow external access to its task service.")
        }

        if (!service.exported) {
            return Result(false, "This Round Sync version blocks external task launches. Open Round Sync and run the configured task there.")
        }

        val intent = Intent(ACTION_START_TASK)
            .setComponent(component)
            .putExtra("task", taskId)
            .putExtra("notification", true)
        return try {
            val started = appContext.startService(intent)
            if (started != null) {
                Result(true, "Round Sync task #$taskId requested. Verify transfer completion in Round Sync before rescanning or relying on cloud updates.")
            } else {
                Result(false, "Round Sync did not accept the task launch.")
            }
        } catch (error: Exception) {
            Result(false, "Round Sync refused the task launch (${error.javaClass.simpleName}). Run it from Round Sync.")
        }
    }

    private fun validPackage(value: String) =
        value.length in 3..180 && packagePattern.matches(value)
}
