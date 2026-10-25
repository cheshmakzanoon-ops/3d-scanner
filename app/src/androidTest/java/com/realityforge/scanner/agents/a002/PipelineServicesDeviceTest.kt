package com.realityforge.scanner.agents.a002

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.data.ScanDatabase
import com.realityforge.scanner.engine.LensCalibration
import com.realityforge.scanner.engine.LensProfile
import com.realityforge.scanner.engine.NativeCore
import com.realityforge.scanner.engine.pipeline.DensePreviewProfile
import com.realityforge.scanner.engine.pipeline.ProductionEngineCompatibility
import com.realityforge.scanner.engine.SparsePipeline
import com.realityforge.scanner.engine.artifacts.SparseFrontierCheckpointStore
import com.realityforge.scanner.engine.registration.RegistrationFrontier
import com.realityforge.scanner.processing.resources.ResourceWorkloadSelection
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Real production entry point with explicitly SYNTHETIC photographic fixtures.
 * This is Android runtime evidence, never a handset/capture quality qualification.
 */
@RunWith(AndroidJUnit4::class)
class PipelineServicesDeviceTest {
    private fun hash(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString(""){"%02x".format(it)}

    private fun assertCompactCoverage(result: JSONObject) {
        val texture = result.getJSONObject("texturePreview")
        val input = texture.getInt("inputTriangles")
        val supported = input - texture.getInt("skippedTriangles")
        assertTrue(supported in 1..input)
        assertEquals(input, texture.getInt("triangleCount"))
        assertEquals(input, result.getJSONObject("meshPreview").getInt("triangleCount"))
        val visibility = result.getJSONObject("scanDiagnostics").getJSONObject("evidence")
            .getJSONObject("visibility")
        assertEquals(input, visibility.getInt("evaluatedFaces"))
        assertEquals(supported, visibility.getInt("supportedFaces"))
        assertEquals(supported.toDouble() / input, visibility.getDouble("supportedFraction"), 0.0)
        assertEquals(supported.toDouble() / input, texture.getDouble("faceCoverage"), 5e-11 + 1e-15)
    }

    @Test fun actualServicesPreserveTrackIdentityRecomputeCorruptCacheAndCancelWithoutPublication() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val db=Room.inMemoryDatabaseBuilder(context,ScanDatabase::class.java).build()
        val repository=ProjectRepository(context,db.dao())
        val project=repository.create("SYNTHETIC agent002 service integration")
        val temporary=mutableListOf<File>()
        try {
            for(index in 1..3) {
                val source=File.createTempFile("a002-relief-", ".jpg",context.cacheDir).also {temporary+=it}
                instrumentation.context.assets.open("relief/view-0$index.jpg").use {input-> source.outputStream().use {input.copyTo(it)}}
                assertTrue(repository.importPhoto(project,Uri.fromFile(source)))
            }
            val metadata=instrumentation.context.assets.open("relief/calibration.json").bufferedReader().use {JSONObject(it.readText())}
            assertTrue(metadata.getBoolean("synthetic"))
            val k=metadata.getJSONArray("cameras").getJSONObject(0).getJSONArray("K")
            val profile=LensProfile(metadata.getInt("width"),metadata.getInt("height"),
                k.getJSONArray(0).getDouble(0),k.getJSONArray(1).getDouble(1),
                k.getJSONArray(0).getDouble(2),k.getJSONArray(1).getDouble(2),
                0.0,0.0,0.0,0.0,0.0,0.0,10,null)
            repository.writeAtomic(LensCalibration.file(repository,project),LensCalibration.json(profile))
            val originals=repository.dao.photos(project).associate {it.id to hash(repository.photoFile(it))}
            val token=NativeCore.beginReconstruction()
            val result=try { SparsePipeline(repository, resourceSelection = ResourceWorkloadSelection.COMPACT,
                    executionProfile = DensePreviewProfile.VerifiedRefinedCompact).run(project,32.88,token) {} }
                finally {NativeCore.finishReconstruction(token)}
            assertTrue(result.toString().take(500),result.getInt("pointCount")>=30)
            assertEquals(ProductionEngineCompatibility.VALUE,result.getString("engineVersion"))
            val registration=result.getJSONObject("incrementalRegistration")
            val sparseStage=result.getJSONObject("pipeline").getJSONObject("stageIdentities")
                .getJSONObject("sparse").getString("digest")
            assertEquals("New project cannot claim a frontier cache hit","absent",registration.getString("frontierResumeStatus"))
            assertTrue("Actual production growth must publish a resumable frontier",registration.getInt("durableFrontierWrites")>0)
            assertEquals(0,registration.getInt("failedFrontierWrites"))
            assertFalse(registration.getBoolean("frontierIsCompletedAlignment"))
            assertTrue(result.getJSONObject("overlapDiagnostics").getInt("geometricallyVerifiedPairs")>0)
            val frontierStore=SparseFrontierCheckpointStore(repository.directory(project))
            val frontierBytes=requireNotNull(frontierStore.load(sparseStage) {false})
            val frontier=RegistrationFrontier.decode(frontierBytes)
            assertEquals(repository.dao.photos(project).map {it.id}.toSet(),frontier.photoIds.toSet())
            assertEquals(result.getInt("registeredCameras"),frontier.cameras.size)
            val sourceIds=result.getJSONArray("sparseSourceTrackIds")
            assertTrue(sourceIds.length()>0)
            for(i in 0 until sourceIds.length())assertTrue(sourceIds.getString(i).matches(Regex("[0-9a-f]{64}")))
            assertEquals("Surface producer must match the stage policy",12,result.getJSONObject("meshPreview").getInt("fusionVersion"))
            val dense=result.getJSONObject("densePreview")
            assertEquals("verified_sparse_tracks",dense.getString("selectionMethod"))
            assertEquals("VerifiedRefinedCompact",dense.getString("executionProfile"))
            assertTrue(dense.getBoolean("approvedSourceAdjacencyApplied"))
            assertTrue(dense.getInt("consistentPixels")>=60)
            assertTrue(dense.getJSONArray("selectedPhotoIds").length() in 2..8)
            val texture = result.getJSONObject("texturePreview")
            assertTrue(texture.getInt("triangleCount")>=40)
            assertEquals("compact_visible_photographic_atlas", texture.getString("representation"))
            assertTrue(texture.getBoolean("compactCharts"))
            assertTrue("Compact texel blending has no per-face switch count", texture.isNull("viewSwitchEdges"))
            assertCompactCoverage(result)
            val key=result.getString("artifactKey")
            val completed=repository.artifact(project,key,"json")
            val completedHash=hash(completed)
            assertEquals(1,repository.dao.complete(project,result.getLong("revision"),key,"SYNTHETIC integrated fixture"))
            assertEquals(key,requireNotNull(repository.currentPreview(project)).getString("artifactKey"))
            val priorAssets=requireNotNull(repository.directory(project).listFiles()).filter {it.name.startsWith("$key.")}
                .associate {it.name to hash(it)}
            val cache=File(repository.directory(project),"features").canonicalFile
            val entries=requireNotNull(cache.listFiles()).filter {it.name.matches(Regex("[0-9a-f]{64}\\.rffc"))}
            assertEquals(3,entries.size)
            val corrupted=entries.first()
            val validBytes=corrupted.readBytes()
            corrupted.writeBytes(byteArrayOf(1,2,3))
            val cancelled=NativeCore.beginReconstruction()
            assertTrue(NativeCore.cancelReconstruction(cancelled))
            try {
                SparsePipeline(repository, resourceSelection = ResourceWorkloadSelection.COMPACT,
                    executionProfile = DensePreviewProfile.VerifiedRefinedCompact).run(project,32.88,cancelled) {}
                fail("A cancelled native lease published a result")
            } catch(expected:CancellationException) {
                // Actual extraction runs before pair verification and repairs this strict cache miss.
            } finally {NativeCore.finishReconstruction(cancelled)}
            assertArrayEquals("Corrupt derived entry must be deterministically recomputed",validBytes,corrupted.readBytes())
            assertEquals("Cancelled run replaced completed artifact",completedHash,hash(completed))
            assertArrayEquals("Pre-cancelled run must not replace the validated frontier",frontierBytes,
                requireNotNull(frontierStore.load(sparseStage) {false}))
            // Force the partial-frontier recovery path, not the newer complete-alignment
            // fast path. Only derived alignment checkpoints in this test-owned project
            // are removed; completed outputs, originals and frontier bytes stay intact.
            val alignmentFiles=requireNotNull(repository.directory(project).listFiles())
                .filter { it.name.startsWith("alignment-") && it.extension=="rfac" }
            assertTrue("Expected a real completed alignment checkpoint",alignmentFiles.isNotEmpty())
            alignmentFiles.forEach { assertTrue(it.delete()) }
            val lateToken=NativeCore.beginReconstruction()
            var observedLateCheckpoint=false
            try {
                SparsePipeline(repository, resourceSelection = ResourceWorkloadSelection.COMPACT,
                    executionProfile = DensePreviewProfile.VerifiedRefinedCompact).run(project,32.88,lateToken) { progress ->
                    if(progress.stage=="Saving diagnostic exports" && progress.completed==0) {
                        val attempt=requireNotNull(repository.directory(project).listFiles()).single {
                            it.name.startsWith("sparse-m16-r") && it.extension=="json" && it.name!="$key.json"
                        }
                        val replay=JSONObject(attempt.readText())
                        assertNotEquals("Publication attempt must not overwrite the prior key",key,replay.getString("artifactKey"))
                        val resumedRegistration=replay.getJSONObject("incrementalRegistration")
                        assertFalse("Complete alignment must not mask frontier recovery",replay.optBoolean("alignmentReused"))
                        assertEquals("Actual production growth must consume its persisted frontier","restored",resumedRegistration.getString("frontierResumeStatus"))
                        assertTrue(resumedRegistration.getInt("durableFrontierWrites")>0)
                        assertEquals(0,resumedRegistration.getInt("failedFrontierWrites"))
                        val resumedFrontier=RegistrationFrontier.decode(requireNotNull(frontierStore.load(sparseStage) {false}))
                        assertEquals(frontier.inputIdentity,resumedFrontier.inputIdentity)
                        assertEquals(frontier.photoIds.toSet(),resumedFrontier.photoIds.toSet())
                        val resumed=replay.getJSONObject("densePreview")
                        assertEquals(dense.getString("depthInputIdentity"),resumed.getString("depthInputIdentity"))
                        assertTrue("Actual fine tiles must resume despite a fresh publication attempt",resumed.getInt("resumedDepthTiles")>0)
                        observedLateCheckpoint=true
                        throw CancellationException("Test cancellation after new assets and JSON, before final export publication")
                    }
                }
                fail("Late cancelled attempt completed")
            } catch(expected:CancellationException) {
                assertTrue("Late publication boundary was not reached",observedLateCheckpoint)
            } finally {NativeCore.finishReconstruction(lateToken)}
            for((name,expected) in priorAssets)assertEquals("Prior asset changed: $name",expected,hash(File(repository.directory(project),name)))
            val remaining=requireNotNull(repository.directory(project).listFiles()).filter {it.name.startsWith("sparse-m16-r")}
            assertEquals(priorAssets.keys,remaining.map {it.name}.toSet())
            assertEquals(key,requireNotNull(repository.currentPreview(project)).getString("artifactKey"))
            // Exercise the complete-alignment path too: it must forward the same verified
            // support/profile, not silently drop back to compatibility after cache restore.
            val reuseToken=NativeCore.beginReconstruction()
            val reused=try { SparsePipeline(repository, resourceSelection = ResourceWorkloadSelection.COMPACT,
                executionProfile = DensePreviewProfile.VerifiedRefinedCompact).run(project,32.88,reuseToken) {} }
                finally { NativeCore.finishReconstruction(reuseToken) }
            assertTrue(reused.getBoolean("alignmentReused"))
            val reusedDense=reused.getJSONObject("densePreview")
            assertEquals("verified_sparse_tracks",reusedDense.getString("selectionMethod"))
            assertEquals("VerifiedRefinedCompact",reusedDense.getString("executionProfile"))
            assertTrue(reusedDense.getBoolean("approvedSourceAdjacencyApplied"))
            val reusedTexture = reused.getJSONObject("texturePreview")
            assertEquals("compact_visible_photographic_atlas", reusedTexture.getString("representation"))
            assertTrue(reusedTexture.getBoolean("compactCharts"))
            assertTrue(reusedTexture.isNull("viewSwitchEdges"))
            assertCompactCoverage(reused)
            assertEquals(dense.getString("depthInputIdentity"),reusedDense.getString("depthInputIdentity"))
            assertEquals(dense.getJSONArray("selectedPhotoIds").toString(),reusedDense.getJSONArray("selectedPhotoIds").toString())
            for(photo in repository.dao.photos(project))assertEquals(originals.getValue(photo.id),hash(repository.photoFile(photo)))
        } finally {
            temporary.forEach {it.delete()}
            repository.delete(project)
            db.close()
        }
    }
}
