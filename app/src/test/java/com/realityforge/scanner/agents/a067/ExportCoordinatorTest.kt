package com.realityforge.scanner.agents.a067

import com.realityforge.scanner.export.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExportCoordinatorTest {
    private val project = UUID.randomUUID().toString()
    private val bytes = "{\"synthetic\":true,\"representation\":\"sparse_points\"}".toByteArray()
    private val identity = ArtifactIdentity(project, 4, "sparse-m10-r4-0123456789abcdef", "a".repeat(64))
    private val option = ExportOption("json", ExportFormat.JSON, AssetRepresentation.DIAGNOSTICS, bytes.size.toLong())
    private fun request() = ExportRequest(UUID.randomUUID().toString(), identity, option)
    private class Destination : ExportDestination {
        val bytes = ByteArrayOutputStream()
        var opened = false; var committed = false; var aborted = false; var failAfter = Int.MAX_VALUE; var cleanup = true
        override fun open(): OutputStream {
            opened = true
            return object : OutputStream() {
                override fun write(b: Int) { if (bytes.size() >= failAfter) throw java.io.IOException("short write"); bytes.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) { for (i in off until off+len) write(b[i].toInt()) }
            }
        }
        override fun commit() { committed = true }
        override fun abort(): Boolean { aborted = true; return cleanup }
    }
    private inner class Source : ExportSource {
        var current = true; var invalid = false; var changeAfterValidation = false; var materializations = 0
        override suspend fun isCurrent(identity: ArtifactIdentity) = current && identity == this@ExportCoordinatorTest.identity
        override suspend fun available(identity: ArtifactIdentity) = listOf(option)
        override fun materialize(request: ExportRequest, target: File, checkpoint: () -> Unit) { checkpoint(); materializations++; target.writeBytes(bytes) }
        override fun validate(request: ExportRequest, file: File) { check(!invalid); if (changeAfterValidation) current = false }
    }
    private fun temporary(block: (File) -> Unit) { val f = Files.createTempDirectory("export-test").toFile(); try { block(f) } finally { f.deleteRecursively() } }

    @Test fun completedOnlyAfterCheckedTransferAndNeverReconstructs() = temporary { root -> runBlocking {
        val source = Source(); val destination = Destination()
        val result = ExportCoordinator(source, root).execute(request(), destination)
        assertTrue(result is ExportOutcome.Completed); assertTrue(destination.committed)
        assertArrayEquals(bytes, destination.bytes.toByteArray()); assertEquals(1, source.materializations)
        assertEquals(0, root.listFiles()!!.size)
    } }
    @Test fun stalePickerRequestCannotFollowNewSelection() = temporary { root -> runBlocking {
        val source = Source().apply { current = false }; val destination = Destination()
        val result = ExportCoordinator(source, root).execute(request(), destination)
        assertTrue(result is ExportOutcome.Failed); assertFalse(destination.opened); assertEquals(0, source.materializations)
    } }
    @Test fun invalidStagedArtifactNeverOpensDestination() = temporary { root -> runBlocking {
        val destination = Destination()
        val result = ExportCoordinator(Source().apply { invalid = true }, root).execute(request(), destination)
        assertTrue(result is ExportOutcome.Failed); assertFalse(destination.opened)
    } }
    @Test fun revisionChangeDuringMaterializationNeverPublishes() = temporary { root -> runBlocking {
        val destination = Destination()
        val result = ExportCoordinator(Source().apply { changeAfterValidation = true }, root).execute(request(), destination)
        assertTrue(result is ExportOutcome.Failed); assertFalse(destination.opened)
    } }
    @Test fun shortWritesAttemptCleanupAndCannotComplete() = temporary { root -> runBlocking {
        val destination = Destination().apply { failAfter = 3; cleanup = false }
        val result = ExportCoordinator(Source(), root).execute(request(), destination)
        assertTrue(result is ExportOutcome.Failed); assertTrue((result as ExportOutcome.Failed).destinationMayBePartial)
        assertTrue(destination.aborted); assertFalse(destination.committed)
    } }
    @Test fun cancellationDuringCopyAbortsWithoutChangingSource() = temporary { root -> runBlocking {
        var cancel = false; val destination = Destination()
        val result = ExportCoordinator(Source(), root).execute(request(), destination, { cancel }, { _, _ -> cancel = true })
        assertTrue(result is ExportOutcome.Cancelled); assertTrue(destination.aborted); assertFalse(destination.committed)
    } }
    @Test fun byteBudgetRejectsBeforeMaterialization() = temporary { root -> runBlocking {
        val source = Source(); val destination = Destination()
        val result = ExportCoordinator(source, root, 8).execute(request(), destination)
        assertTrue(result is ExportOutcome.Failed); assertEquals(0, source.materializations); assertFalse(destination.opened)
    } }
    @Test fun pendingPickerSurvivesRecreationAndKeepsProjectArtifactAndOptions() = temporary { root ->
        val file = File(root, "picker"); val req = request()
        PendingExportStore(file).save(req)
        assertEquals(req, PendingExportStore(file).restore())
        try { PendingExportStore(file).save(request()); fail() } catch (_: IllegalStateException) {}
        try { PendingExportStore(file).clear(UUID.randomUUID().toString()); fail() } catch (_: IllegalArgumentException) {}
        assertTrue(file.exists()); PendingExportStore(file).clear(req.requestId); assertNull(PendingExportStore(file).restore())
    }
    @Test fun truncatedOrUnknownVersionPickerStateFailsClosed() = temporary { root ->
        val file = File(root, "picker"); PendingExportStore(file).save(request())
        val all = file.readBytes(); all[4] = (all[4].toInt() xor 1).toByte(); file.writeBytes(all)
        try { PendingExportStore(file).restore(); fail() } catch (_: IllegalArgumentException) {}
    }
    @Test fun invalidPendingStateCanBeExplicitlyQuarantinedWithoutLosingEvidence() = temporary { root ->
        val file = File(root,"picker").apply { writeText("broken") }
        val rejected = requireNotNull(PendingExportStore(file).quarantineInvalid())
        assertEquals("broken", rejected.readText()); assertFalse(file.exists())
        PendingExportStore(file).save(request())
        try { PendingExportStore(file).quarantineInvalid(); fail() } catch (_: IllegalStateException) {}
        assertTrue(file.exists())
    }
    @Test fun objectPackageRemainsRelocatableAndChecksContent() = temporary { root ->
        val model = File(root, "scan.obj").apply { writeText("mtllib scan.mtl\nv 0 0 0\n") }
        val mtl = File(root, "scan.mtl").apply { writeText("newmtl photograph\nmap_Kd atlas.png\n") }
        val atlas = File(root, "atlas.png").apply { writeBytes(byteArrayOf(1,2,3)) }
        val zip = File(root, "scan.zip")
        ExportPackage.write(mapOf("scan.obj" to model, "scan.mtl" to mtl, "atlas.png" to atlas), zip, 4096)
        val relocated = File(root, "other").apply { mkdirs() }
        ZipFile(zip).use { z -> z.entries().asSequence().forEach { e -> File(relocated,e.name).writeBytes(z.getInputStream(e).readBytes()) } }
        assertEquals(model.readText(), File(relocated, "scan.obj").readText())
        assertTrue(File(relocated, "atlas.png").isFile)
    }
    @Test fun objectPackageRejectsTraversalAndPreservesSources() = temporary { root ->
        val source = File(root, "source").apply { writeText("immutable master") }
        try { ExportPackage.write(mapOf("../escape" to source), File(root, "out.zip"), 4096); fail() } catch (_: IllegalArgumentException) {}
        assertEquals("immutable master", source.readText())
    }
    @Test fun pointCloudCannotBeOfferedAsStlAndUnknownScaleMustBeAcknowledged() {
        try { ExportOption("stl",ExportFormat.STL,AssetRepresentation.SPARSE_POINTS,84); fail() } catch (_: IllegalArgumentException) {}
        val stl = ExportOption("stl",ExportFormat.STL,AssetRepresentation.OBSERVED_SURFACE,84)
        try { ExportRequest(UUID.randomUUID().toString(), identity, stl); fail() } catch (_: IllegalArgumentException) {}
    }
}
