package dev.pranav.applock.data.manager

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import dev.pranav.applock.core.utils.appLockRepository
import dev.pranav.applock.core.utils.hasUsagePermission
import dev.pranav.applock.data.repository.BackendImplementation
import dev.pranav.applock.services.AppLockAccessibilityService
import dev.pranav.applock.services.AppLockManager
import dev.pranav.applock.services.UsageLockService

/**
 * Safety net for the automatic backend.
 *
 * Android marks an accessibility service as "crashed" when its process dies while bound and
 * then refuses to rebind it until the user re-toggles it. The secure setting still says the
 * service is enabled, so the resolver happily picks it - and locking silently does nothing.
 * This watchdog notices that the service is enabled but not alive and drops down to usage
 * statistics instead of leaving the user unprotected.
 */
object BackendWatchdog {
    private const val TAG = "BackendWatchdog"
    private const val STARTUP_GRACE_MS = 12_000L

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { evaluate(appContext) }
                .onFailure { Log.e(TAG, "Backend watchdog failed", it) }
        }, STARTUP_GRACE_MS)
    }

    fun evaluate(context: Context) {
        val repository = context.appLockRepository()
        if (!repository.isAutomaticBackend()) return

        if (repository.getEffectiveBackend() != BackendImplementation.ACCESSIBILITY) return
        if (AppLockAccessibilityService.isServiceRunning) return

        if (!context.hasUsagePermission()) {
            Log.w(
                TAG,
                "Accessibility is enabled but not running, and usage stats is not permitted - " +
                        "nothing to fall back to"
            )
            return
        }

        Log.w(TAG, "Accessibility is enabled but dead, falling back to usage statistics")
        AppLockManager.forcedBackend = BackendImplementation.USAGE_STATS
        AppLockManager.stopAllOtherServices(context, UsageLockService::class.java)
        ContextCompat.startForegroundService(context, Intent(context, UsageLockService::class.java))
    }
}
