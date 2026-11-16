package dev.pranav.applock.data.manager

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dev.pranav.applock.core.utils.appLockRepository
import dev.pranav.applock.data.repository.BackendImplementation
import dev.pranav.applock.services.AppLockAccessibilityService
import dev.pranav.applock.services.AppLockManager
import dev.pranav.applock.services.UsageLockService

/**
 * Runtime demotion when a backend turns out to be broken while locking is supposed to be
 * active (Shizuku binder gone, no permission, cannot read the task list, ...).
 * Only kicks in for the automatic backend - a manually pinned backend stays pinned.
 */
object BackendFallback {
    private const val TAG = "BackendFallback"

    fun demote(context: Context, failing: BackendImplementation) {
        val repository = context.appLockRepository()
        if (!repository.isAutomaticBackend()) {
            Log.w(TAG, "$failing failed, but the user pinned it manually - not switching")
            return
        }

        val next = nextBackend(context, failing)
        if (next == null) {
            Log.e(TAG, "$failing failed and there is no usable fallback left")
            return
        }

        Log.w(TAG, "$failing failed, falling back to $next")
        AppLockManager.forcedBackend = next

        when (next) {
            BackendImplementation.USAGE_STATS -> {
                AppLockManager.stopAllOtherServices(context, UsageLockService::class.java)
                try {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, UsageLockService::class.java)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Could not start the usage stats fallback", e)
                }
            }

            BackendImplementation.ACCESSIBILITY -> {
                // Nothing to start, the service is bound by the system and simply starts
                // locking again now that it is the effective backend.
                AppLockManager.stopAllOtherServices(context, AppLockAccessibilityService::class.java)
            }

            else -> Unit
        }
    }

    private fun nextBackend(
        context: Context,
        failing: BackendImplementation
    ): BackendImplementation? = when (failing) {
        BackendImplementation.SHIZUKU -> when {
            BackendResolver.isAccessibilityReady(context) -> BackendImplementation.ACCESSIBILITY
            BackendResolver.isUsageStatsReady(context) -> BackendImplementation.USAGE_STATS
            else -> null
        }

        BackendImplementation.ACCESSIBILITY ->
            if (BackendResolver.isUsageStatsReady(context)) BackendImplementation.USAGE_STATS else null

        else -> null
    }
}
