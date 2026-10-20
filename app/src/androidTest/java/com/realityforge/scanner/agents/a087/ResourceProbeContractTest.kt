package com.realityforge.scanner.agents.a087

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.qa.ResourceProbe
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.realityforge.scanner.RealityForgeApplication
import com.realityforge.scanner.engine.NativeCore
import com.realityforge.scanner.engine.SparsePipeline
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

@RunWith(AndroidJUnit4::class)
class ResourceProbeContractTest {
    @Test fun profileOperatorSelectedDisposableProject() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val id=args.getString("resourceProjectId")
        assumeTrue("Requires an authorized disposable workload project",id!=null)
        val runs=args.getString("resourceRuns")?.toInt() ?: 1
        require(runs in 1..3)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=context.applicationContext as RealityForgeApplication
        assertNotNull(app.repository.dao.project(requireNotNull(id)))
        assertTrue(app.repository.dao.photos(id).size>=2)
        ResourceProbe.reset(context)
        repeat(runs) { run ->
            ResourceProbe.record(context,"run-$run:start")
            val token=NativeCore.beginReconstruction()
            try {
                val result=SparsePipeline(app.repository).run(id,32.88,token) { progress ->
                    ResourceProbe.record(context,"run-$run:${progress.stage}")
                }
                assertTrue(result.getInt("pointCount")>0)
                assertNotNull("Dense workload failed: ${result.optString("denseFailure")}",result.optJSONObject("densePreview"))
            } finally { NativeCore.finishReconstruction(token) }
            ResourceProbe.record(context,"run-$run:end")
        }
    }
    @Test fun debugProbeRecordsActualSameProcessCounters() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        ResourceProbe.reset(context)
        ResourceProbe.record(context,"synthetic-probe-contract-start")
        val allocation=ByteArray(1024*1024) { 42 }
        ResourceProbe.record(context,"synthetic-probe-contract-end")
        assertEquals(42,allocation[0].toInt())
        val rows=File(context.filesDir,"qa-resource-probe.jsonl").readLines().map { JSONObject(it) }
        assertEquals(2,rows.size)
        assertEquals(android.os.Process.myPid(),rows[0].getInt("pid"))
        assertTrue(rows[0].getLong("pssBytes")>0)
        assertTrue(rows[1].getLong("elapsedRealtimeNanos")>rows[0].getLong("elapsedRealtimeNanos"))
    }
}
