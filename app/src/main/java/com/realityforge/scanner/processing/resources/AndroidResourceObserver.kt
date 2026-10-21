package com.realityforge.scanner.processing.resources

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock

/** Local, on-demand observations only. Unknown values remain unknown. */
internal object AndroidResourceObserver {
    fun sample(context: Context): DeviceResourceSnapshot {
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        val lowMemory = try {
            if (activity == null) true else { activity.getMemoryInfo(info); info.lowMemory }
        } catch (_: RuntimeException) { true }
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermal = try {
            if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus?.takeIf { it in 0..6 } else null
        } catch (_: RuntimeException) { null }
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val percent = try {
            battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        } catch (_: RuntimeException) { null }
        val charging = try { battery?.isCharging } catch (_: RuntimeException) { null }
        val storage = try {
            StatFs(context.filesDir.absolutePath).availableBytes.takeIf { it >= 0 }
        } catch (_: RuntimeException) { null }
        val runtime = Runtime.getRuntime()
        val maxHeap = runtime.maxMemory().coerceAtLeast(0)
        val usedHeap = (runtime.totalMemory() - runtime.freeMemory()).coerceIn(0, maxHeap)
        val available = info.availMem.takeIf { activity != null && it > 0 }
        val powerSave = try { power?.isPowerSaveMode } catch (_: RuntimeException) { null }
        return DeviceResourceSnapshot(SystemClock.elapsedRealtime(), thermal, lowMemory, percent,
            charging, storage, maxHeap, usedHeap, available, powerSave)
    }
}

internal data class ResourceMeasurement(
    val snapshot: DeviceResourceSnapshot,
    val stageElapsedMillis: Long,
    val managedUsedBytes: Long,
    val nativeAllocatedBytes: Long?,
) {
    init { require(stageElapsedMillis >= 0 && managedUsedBytes >= 0 && (nativeAllocatedBytes == null || nativeAllocatedBytes >= 0)) }
}

internal object AndroidResourceMeasurement {
    fun capture(context: Context, stageElapsedMillis: Long): ResourceMeasurement {
        val runtime = Runtime.getRuntime()
        val native = try { android.os.Debug.getNativeHeapAllocatedSize() } catch (_: RuntimeException) { null }
        return ResourceMeasurement(AndroidResourceObserver.sample(context), stageElapsedMillis,
            runtime.totalMemory() - runtime.freeMemory(), native)
    }
}
