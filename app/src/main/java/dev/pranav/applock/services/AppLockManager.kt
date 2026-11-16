package dev.pranav.applock.services

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.data.repository.BackendImplementation
import dev.pranav.applock.services.AppLockAccessibilityService.BiometricState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object AppLockConstants {
    val KNOWN_RECENTS_CLASSES = setOf(
        "com.android.systemui.recents.RecentsActivity",
        "com.android.quickstep.RecentsActivity",
        "com.android.systemui.recents.RecentsView",
        "com.android.systemui.recents.RecentsPanelView",
    )

    val EXCLUDED_APPS = setOf(
        "com.android.systemui",
        "com.android.intentresolver",
        "com.google.android.permissioncontroller",
        "android.uid.system:1000",
        "com.google.android.googlequicksearchbox",
        "android",
        "com.google.android.gms",
        "com.google.android.webview"
    )

    val ACCESSIBILITY_SETTINGS_CLASSES = setOf(
        "com.android.settings.accessibility.AccessibilitySettings",
        "com.android.settings.accessibility.AccessibilityMenuActivity",
        "com.android.settings.accessibility.AccessibilityShortcutActivity",
        "com.android.settings.Settings\$AccessibilitySettingsActivity"
    )

}

fun Context.isDeviceLocked(): Boolean {
    val keyguardManager = getSystemService(KeyguardManager::class.java)
    return keyguardManager?.isKeyguardLocked ?: false
}

@Suppress("DEPRECATION")
fun Context.isServiceRunning(serviceClass: Class<*>): Boolean {
    val manager = getSystemService(ActivityManager::class.java) ?: return false
    return manager.getRunningServices(Int.MAX_VALUE)
        .any { serviceClass.name == it.service.className }
}

object AppLockManager {
    private const val TAG = "AppLockManager"

    var temporarilyUnlockedApp: String = ""
    val appUnlockTimes = ConcurrentHashMap<String, Long>()
    val isLockScreenShown = AtomicBoolean(false)
    var currentBiometricState: AppLockAccessibilityService.BiometricState? = null

    /**
     * Runtime override for the backend, set by the fallback watchdog when the selected
     * backend turns out to be dead (for example a crashed accessibility service).
     * Cleared as soon as the original backend comes back.
     */
    @Volatile
    var forcedBackend: BackendImplementation? = null

    private val lockScreenShownAt = AtomicLong(0L)

    /** Any lock overlay older than this is treated as a leaked flag, not a real lock. */
    private const val LOCK_SCREEN_STALE_MS = 5 * 60_000L

    fun markLockScreenShown() {
        lockScreenShownAt.set(System.currentTimeMillis())
        isLockScreenShown.set(true)
    }

    fun markLockScreenHidden() {
        lockScreenShownAt.set(0L)
        isLockScreenShown.set(false)
    }

    /**
     * True when a lock overlay is currently on screen AND it is not a leaked flag from a
     * flow that never cleared it (biometric prompt cancelled, overlay failed to attach, ...).
     */
    fun isLockScreenBlocking(): Boolean {
        if (!isLockScreenShown.get()) return false
        val shownAt = lockScreenShownAt.get()
        if (shownAt > 0L && System.currentTimeMillis() - shownAt > LOCK_SCREEN_STALE_MS) {
            LogUtils.d(TAG, "Lock screen flag looks stale, clearing it")
            markLockScreenHidden()
            return false
        }
        return true
    }

    // Grace period tracking
    private var recentlyLeftApp: String = ""
    private var recentlyLeftTime: Long = 0L
    private const val GRACE_PERIOD_MS = 300L

    fun setRecentlyLeftApp(packageName: String) {
        recentlyLeftApp = packageName
        recentlyLeftTime = System.currentTimeMillis()
        LogUtils.d(TAG, "Left app $packageName at $recentlyLeftTime")
    }

    fun checkAndRestoreRecentlyLeftApp(packageName: String): Boolean {
        // If we are returning to the same app we just left within the grace period
        if (packageName == recentlyLeftApp && packageName.isNotEmpty()) {
            val elapsed = System.currentTimeMillis() - recentlyLeftTime
            if (elapsed <= GRACE_PERIOD_MS) {
                LogUtils.d(TAG, "Restoring unlock state for $packageName (elapsed: ${elapsed}ms)")
                temporarilyUnlockedApp = packageName
                // Clear the tracking so it doesn't trigger again inappropriately
                recentlyLeftApp = ""
                recentlyLeftTime = 0L
                return true
            } else {
                LogUtils.d(TAG, "Grace period expired for $packageName (elapsed: ${elapsed}ms)")
                recentlyLeftApp = "" // Expired
            }
        }
        return false
    }

    private val ALL_APP_LOCK_SERVICES = setOf(
        ShizukuAppLockService::class.java,
        UsageLockService::class.java
    )

    fun unlockApp(packageName: String) {
        temporarilyUnlockedApp = packageName
        appUnlockTimes[packageName] = System.currentTimeMillis()
        LogUtils.d(
            TAG,
            "App $packageName unlocked at timestamp: ${appUnlockTimes[packageName]}, current time: ${System.currentTimeMillis()}"
        )
    }

    fun temporarilyUnlockAppWithBiometrics(packageName: String) {
        unlockApp(packageName)
        reportBiometricAuthFinished()
    }

    fun reportBiometricAuthStarted() {
        currentBiometricState = BiometricState.AUTH_STARTED
    }

    fun reportBiometricAuthFinished() {
        currentBiometricState = BiometricState.IDLE
    }

    fun isAppTemporarilyUnlocked(packageName: String): Boolean =
        temporarilyUnlockedApp == packageName

    fun clearTemporarilyUnlockedApp() {
        temporarilyUnlockedApp = ""
    }

    fun clearAppUnlockState(packageName: String) {
        if (temporarilyUnlockedApp == packageName) {
            temporarilyUnlockedApp = ""
        }
        appUnlockTimes.remove(packageName)
        if (packageName == recentlyLeftApp) {
            recentlyLeftApp = ""
            recentlyLeftTime = 0L
        }
        LogUtils.d(TAG, "Cleared stale unlock state for $packageName")
    }

    fun stopAllOtherServices(context: Context, excludeService: Class<*>) {
        ALL_APP_LOCK_SERVICES
            .filter { it != excludeService }
            .forEach {
                context.stopService(Intent(context, it))
            }
        LogUtils.d(TAG, "Stopped all main app lock services except ${excludeService.simpleName}.")
    }

}
