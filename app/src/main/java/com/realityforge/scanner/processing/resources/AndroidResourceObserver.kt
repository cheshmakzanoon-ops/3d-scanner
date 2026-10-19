package com.realityforge.scanner.processing.resources

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.os.StatFs

/** Samples platform resources on demand. No receiver, polling service or network access. */
object AndroidResourceObserver {
    fun sample(context: Context): DeviceResourceSnapshot {
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        val memoryAvailable = try { if (activity != null) { activity.getMemoryInfo(info); true } else false } catch (_: RuntimeException) { false }
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val percent = try { battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } } catch (_: RuntimeException) { null }
        val plugged = try {
            val state = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            when (state) {
                BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
                BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
                else -> null
            }
        } catch (_: RuntimeException) { null }
        val thermal = try { if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus else null } catch (_: RuntimeException) { null }
        val saver = try { power?.isPowerSaveMode } catch (_: RuntimeException) { null }
        val storage = try { StatFs(context.filesDir.absolutePath).availableBytes.coerceAtLeast(0) } catch (_: RuntimeException) { null }
        return DeviceResourceSnapshot(
            elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            managedHeapLimitBytes = Runtime.getRuntime().maxMemory(),
            availableStorageBytes = storage,
            availableMemoryBytes = if (memoryAvailable) info.availMem else null,
            lowMemory = if (memoryAvailable) info.lowMemory else null,
            thermalStatus = thermal,
            batteryPercent = percent,
            pluggedIn = plugged,
            powerSaveMode = saver,
        )
    }
}

/** Captures timestamped planning telemetry without falsely calling estimates measured memory. */
data class ResourceMeasurement(
    val snapshot: DeviceResourceSnapshot,
    val stageElapsedMillis: Long,
    val managedUsedBytes: Long,
    val nativeAllocatedBytes: Long?,
) {
    init { require(stageElapsedMillis >= 0 && managedUsedBytes >= 0 && (nativeAllocatedBytes == null || nativeAllocatedBytes >= 0)) }
}

object AndroidResourceMeasurement {
    fun capture(context: Context, stageElapsedMillis: Long): ResourceMeasurement {
        val runtime = Runtime.getRuntime()
        val native = try { android.os.Debug.getNativeHeapAllocatedSize() } catch (_: RuntimeException) { null }
        return ResourceMeasurement(
            AndroidResourceObserver.sample(context), stageElapsedMillis,
            runtime.totalMemory() - runtime.freeMemory(), native,
        )
    }
}
