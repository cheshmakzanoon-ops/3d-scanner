package com.realityforge.scanner.qa

import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.os.PowerManager
import org.json.JSONObject
import java.io.File

/** Explicit test/debug call only; no release source or product UI integration. */
object ResourceProbe {
    private const val MAX_EVENTS = 2048
    private var events = 0
    @Synchronized fun reset(context: Context) { File(context.filesDir,"qa-resource-probe.jsonl").delete(); events=0 }
    @Synchronized fun record(context: Context, stage: String) {
        require(stage.length in 1..160)
        check(events<MAX_EVENTS) { "Debug resource event budget exhausted" }
        val info=Debug.MemoryInfo();Debug.getMemoryInfo(info)
        val runtime=Runtime.getRuntime()
        val row=JSONObject().put("schema",1).put("pid",Process.myPid()).put("stage",stage)
            .put("elapsedRealtimeNanos",SystemClock.elapsedRealtimeNanos())
            .put("javaAllocatedBytes",runtime.totalMemory()-runtime.freeMemory())
            .put("nativeAllocatedBytes",Debug.getNativeHeapAllocatedSize())
            .put("pssBytes",info.totalPss.toLong()*1024)
            .put("thermalStatus",context.getSystemService(PowerManager::class.java).currentThermalStatus)
        File(context.filesDir,"qa-resource-probe.jsonl").appendText(row.toString()+"\n")
        events++
    }
}
