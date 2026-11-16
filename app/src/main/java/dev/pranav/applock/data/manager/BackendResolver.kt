package dev.pranav.applock.data.manager

import android.content.pm.PackageManager
import android.util.Log
import dev.pranav.applock.core.utils.hasUsagePermission
import dev.pranav.applock.core.utils.isAccessibilityServiceEnabled
import dev.pranav.applock.data.repository.BackendImplementation
import rikka.shizuku.Shizuku

/**
 * Resolves which backend should actually be doing the locking.
 *
 * [BackendImplementation.AUTOMATIC] walks the chain Shizuku -> Accessibility -> Usage stats
 * and picks the first one that is actually usable right now. A manually selected backend is
 * always honoured, so nothing changes for users who pinned a specific implementation.
 */
object BackendResolver {
    private const val TAG = "BackendResolver"

    fun isShizukuReady(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        Log.w(TAG, "Shizuku unavailable: ${e.message}")
        false
    }

    fun isAccessibilityReady(context: android.content.Context): Boolean =
        context.isAccessibilityServiceEnabled()

    fun isUsageStatsReady(context: android.content.Context): Boolean =
        context.hasUsagePermission()

    fun resolve(
        context: android.content.Context,
        selected: BackendImplementation
    ): BackendImplementation {
        if (selected != BackendImplementation.AUTOMATIC) return selected

        if (isShizukuReady()) return BackendImplementation.SHIZUKU
        if (isAccessibilityReady(context)) return BackendImplementation.ACCESSIBILITY
        if (isUsageStatsReady(context)) return BackendImplementation.USAGE_STATS

        Log.w(TAG, "No backend is usable; falling back to accessibility")
        return BackendImplementation.ACCESSIBILITY
    }

    /**
     * Human readable summary of why a backend was chosen, used by the settings screen.
     */
    fun describe(context: android.content.Context, selected: BackendImplementation): String {
        if (selected != BackendImplementation.AUTOMATIC) return ""
        val resolved = resolve(context, selected)
        return when (resolved) {
            BackendImplementation.SHIZUKU -> "Shizuku is available, using it"
            BackendImplementation.ACCESSIBILITY -> if (isUsageStatsReady(context)) {
                "No Shizuku, using the accessibility service (usage stats is the backup)"
            } else {
                "Using the accessibility service"
            }

            BackendImplementation.USAGE_STATS -> "Only usage stats is permitted, using it"
            else -> ""
        }
    }
}
