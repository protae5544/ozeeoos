package com.wall.launcher

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast

data class WallTelemetry(
    val batteryPercent: Int,
    val isCharging: Boolean,
    val freeRamMb: Long,
    val totalRamMb: Long,
    val termuxInstalled: Boolean,
    val termuxStatusText: String,
    val launcherHeapMb: Long
)

/**
 * Dedicated Termux Care & Passive System Telemetry Module.
 *
 * STRICT GUARANTEES:
 * - NEVER calls killBackgroundProcesses() or clears memory.
 * - ZERO background services, ZERO polling loops, ZERO wakelocks.
 * - Reads system metrics strictly on-demand when the wall becomes visible.
 */
object TermuxCare {

    const val TERMUX_PACKAGE = "com.termux"

    // Tracks when Termux was first observed active or invoked via WALL during this boot cycle
    private var termuxObservedStartElapsedMs: Long = 0L

    fun markTermuxActiveNow() {
        if (termuxObservedStartElapsedMs == 0L) {
            termuxObservedStartElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    fun readPassiveTelemetry(context: Context, isTermuxInstalled: Boolean): WallTelemetry {
        // 1. Read Battery Level via sticky system property (no receiver registration needed)
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
        val isCharging = bm.isCharging

        // 2. Read Free & Total RAM via ActivityManager.MemoryInfo
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val freeRamMb = memInfo.availMem / (1024L * 1024L)
        val totalRamMb = memInfo.totalMem / (1024L * 1024L)

        // 3. Measure WALL Launcher's own RAM footprint to guarantee <= 60 MB target
        val runtime = Runtime.getRuntime()
        val usedHeapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)

        // 4. Determine Termux continuous uptime / active state passively
        val termuxStatus = if (!isTermuxInstalled) {
            "NOT INSTALLED"
        } else {
            resolveTermuxUptimeString(context)
        }

        return WallTelemetry(
            batteryPercent = batteryPct,
            isCharging = isCharging,
            freeRamMb = freeRamMb,
            totalRamMb = totalRamMb,
            termuxInstalled = isTermuxInstalled,
            termuxStatusText = termuxStatus,
            launcherHeapMb = usedHeapMb
        )
    }

    private fun resolveTermuxUptimeString(context: Context): String {
        val nowElapsed = SystemClock.elapsedRealtime()

        // Check UsageStatsManager passively if the user granted permission
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm != null) {
                val nowWall = System.currentTimeMillis()
                val stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    nowWall - 86_400_000L,
                    nowWall
                )
                val termuxStat = stats?.firstOrNull { it.packageName == TERMUX_PACKAGE }
                if (termuxStat != null && termuxStat.lastTimeUsed > 0L) {
                    val elapsedSinceActiveMs = (nowWall - termuxStat.lastTimeUsed).coerceAtLeast(0L)
                    if (termuxObservedStartElapsedMs == 0L) {
                        termuxObservedStartElapsedMs = (nowElapsed - elapsedSinceActiveMs).coerceAtLeast(1000L)
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore silently; never disturb user
        }

        if (termuxObservedStartElapsedMs > 0L) {
            val durationMin = ((nowElapsed - termuxObservedStartElapsedMs) / 60_000L).coerceAtLeast(1L)
            val hours = durationMin / 60L
            val mins = durationMin % 60L
            return if (hours > 0) "UP ${hours}H ${mins}M" else "UP ${mins}M"
        }

        // Fallback: Device kernel uptime ready for Termux session
        val sysUptimeMin = (nowElapsed / 60_000L).coerceAtLeast(1L)
        return "READY (${sysUptimeMin / 60}H ${sysUptimeMin % 60}M)"
    }

    /**
     * Launches Termux latest active session (single-tap action).
     */
    fun launchTermuxLatestSession(context: Context) {
        markTermuxActiveNow()
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(TERMUX_PACKAGE)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        if (launchIntent != null) {
            try {
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                Toast.makeText(context, "Unable to open Termux", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Termux is not installed on this wall", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Launches a brand-new Termux session/window via LauncherApps dynamic shortcuts,
     * falling back to Termux's new-session intent action.
     */
    fun launchTermuxNewWindow(context: Context, newTaskWindow: Boolean = false) {
        markTermuxActiveNow()
        try {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val query = LauncherApps.ShortcutQuery().apply {
                setPackage(TERMUX_PACKAGE)
                setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                            LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                )
            }
            val shortcuts = launcherApps.getShortcuts(query, Process.myUserHandle())
            if (!shortcuts.isNullOrEmpty()) {
                val targetShortcut = shortcuts.first()
                launcherApps.startShortcut(
                    TERMUX_PACKAGE,
                    targetShortcut.id,
                    null,
                    null,
                    Process.myUserHandle()
                )
                return
            }
        } catch (_: Exception) {
            // Fallback below if OS shortcut invocation is restricted
        }

        try {
            val intent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)?.apply {
                action = "com.termux.app.oshortcut.new_session"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (newTaskWindow) {
                    addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                }
            }
            if (intent != null) {
                context.startActivity(intent)
            } else {
                launchTermuxLatestSession(context)
            }
        } catch (_: Exception) {
            launchTermuxLatestSession(context)
        }
    }

    fun openAppDetailsSettings(context: Context, packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    fun openDefaultLauncherSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_HOME_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (_: Exception) {
            }
        }
    }

    fun openBatteryOptimizationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppDetailsSettings(context, context.packageName)
        }
    }

    fun openNotificationListenerSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }
}
