package dev.pranav.applock.core.broadcast

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.content.edit

class DeviceAdmin : DeviceAdminReceiver() {
    companion object {
        private const val TAG = "AppLockDeviceAdmin"
        private const val PREFS_NAME = "dev.pranav.applock.admin_prefs"
        private const val KEY_PASSWORD_VERIFIED = "password_verified"
    }

    override fun onEnabled(context: Context, intent: android.content.Intent) {
        super.onEnabled(context, intent)

        // NOTE: this callback used to call setUninstallBlocked() unconditionally.
        // Android 10 (Q) restricted that API to device/profile owners, so as a plain
        // device admin it throws SecurityException("Calling identity is not authorized")
        // *inside a broadcast receiver* and kills the whole process - which also killed
        // the bound accessibility service and left app locking completely dead until the
        // service was manually re-enabled. Never let a receiver throw like this.
        val uninstallBlocked = tryBlockUninstall(context)

        context.getSharedPreferences("app_lock_settings", Context.MODE_PRIVATE).edit {
            putBoolean("anti_uninstall", uninstallBlocked)
        }
    }

    override fun onDisabled(context: Context, intent: android.content.Intent) {
        super.onDisabled(context, intent)
        context.getSharedPreferences("app_lock_settings", Context.MODE_PRIVATE).edit {
            putBoolean("anti_uninstall", false)
        }
    }

    /**
     * @return true only when uninstall blocking was actually applied.
     */
    private fun tryBlockUninstall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Regular device admins are not allowed to block uninstall anymore.
            Log.w(
                TAG,
                "Uninstall blocking is unavailable for regular device admins on API " +
                        "${Build.VERSION.SDK_INT}; relying on accessibility/device-admin guard instead."
            )
            return false
        }

        return try {
            getManager(context).setUninstallBlocked(
                ComponentName(context, DeviceAdmin::class.java),
                context.packageName,
                true
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to block uninstall", e)
            false
        }
    }

    fun setPasswordVerified(context: Context, verified: Boolean) {
        getSharedPreferences(context).edit { putBoolean(KEY_PASSWORD_VERIFIED, verified) }
    }

    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
