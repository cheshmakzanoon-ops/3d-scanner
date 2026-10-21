package com.realityforge.scanner.export

import com.realityforge.scanner.domain.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

class LegacyMeshDerivedExportsTest {
    private fun temporary(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("derived-export-test").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
    private val serializer = ObservedMeshSerializer { _, destination, format, hash, checkpoint ->
        checkpoint(); check(!destination.exists())
        if (format == 0) destination.writeText("# synthetic serializer fixture $hash\nv 1 -2 -3\nv 3 -2 -3\nv 1 -5 -3\nf 1 2 3\n")
        else destination.writeBytes(ByteArray(134).apply { this[80] = 1 })
    }
    private fun identity() = ArtifactIdentity(UUID.randomUUID().toString(), 4, "result", "a".repeat(64))
    private fun request(option: ExportOption, ack: Boolean = false) = ExportRequest(UUID.randomUUID().toString(), identity(), option,
        ExportOptions(acknowledgedArbitraryScale = ack))
    @Test fun exactDeterministicZipAndNoSourceMutation() = temporary { root ->
        val source = File(root, "source.ply").apply { writeText("synthetic source") }; val original = source.readBytes()
        val exporter = LegacyMeshDerivedExports(root, serializer)
        val first = exporter.describe(source, "mesh.obj.zip"); val second = exporter.describe(source, "mesh.obj.zip")
        assertEquals(first, second)
        val target = File.createTempFile("stage", ".partial", root)
        exporter.materialize(source, request(first), target) {}
        exporter.validate(first, target)
        ZipFile(target).use { zip ->
            assertEquals(2, zip.size()); assertNotNull(zip.getEntry("model.obj")); assertNotNull(zip.getEntry("README.txt"))
            assertTrue(zip.getInputStream(zip.getEntry("README.txt")).bufferedReader().readText().contains("Arbitrary scale"))
        }
        assertArrayEquals(original, source.readBytes())
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".derived-export-") })
    }
    @Test fun trustedCacheAliasIsResolvedBeforeStagingButInputAliasesStillFailClosed() = temporary { root ->
        val cache = File(root, "cache").apply { mkdir() }.canonicalFile
        val alias = File(root, "android-cache-alias")
        Files.createSymbolicLink(alias.toPath(), cache.toPath())
        val source = File(cache, "source.ply").apply { writeText("synthetic source") }
        val original = source.readBytes()
        val exporter = LegacyMeshDerivedExports(alias, ObservedMeshSerializer { input, output, format, hash, checkpoint ->
            assertEquals(output.canonicalFile, output.absoluteFile)
            serializer.write(input, output, format, hash, checkpoint)
        })
        val option = exporter.describe(source, "mesh.obj.zip")
        // Match production: resolve the trusted root before allocating the empty target.
        val target = File.createTempFile("stage", ".partial", alias.canonicalFile)
        exporter.materialize(source, request(option), target) {}
        exporter.validate(option, target)
        assertThrows(IllegalArgumentException::class.java) {
            exporter.describe(File(alias, source.name), "mesh.obj.zip")
        }
        val empty = File.createTempFile("empty", ".partial", cache)
        assertThrows(IllegalArgumentException::class.java) {
            exporter.materialize(source, request(option), File(alias, empty.name)) {}
        }
        val sourceLink = File(cache, "source-link.ply")
        Files.createSymbolicLink(sourceLink.toPath(), source.toPath())
        assertThrows(IllegalArgumentException::class.java) { exporter.describe(sourceLink, "mesh.obj.zip") }
        val targetLink = File(cache, "target-link.partial")
        Files.createSymbolicLink(targetLink.toPath(), empty.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            exporter.materialize(source, request(option), targetLink) {}
        }
        assertEquals(0L, empty.length())
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun scaleConsentRequiredAndPersistedInBoundPickerIdentity() = temporary { root ->
        val source = File(root, "source.ply").apply { writeText("synthetic source") }
        val exporter = LegacyMeshDerivedExports(root, serializer); val option = exporter.describe(source, "mesh.stl")
        assertThrows(IllegalArgumentException::class.java) { request(option) }
        val exact = request(option, true)
        val target = File.createTempFile("stage", ".partial", root)
        exporter.materialize(source, exact, target) {}; exporter.validate(option, target)
        val selected = DerivedExportSelection(exact.artifact.sha256, option.bytes, option.contentSha256!!, true)
        val bound = BoundExternalRequest.ArtifactExport(RequestId(exact.requestId), ProjectRevision(exact.artifact.projectId, 4), 1, 1,
            exact.artifact.artifactKey, 4, option.id, selected)
        assertEquals(bound, BoundExternalRequestCodec.decode(BoundExternalRequestCodec.encode(bound)))
        val old = bound.copy(derivedSelection = null, extension = "mesh.ply")
        assertEquals(old, BoundExternalRequestCodec.decode(BoundExternalRequestCodec.encode(old)))
    }
    @Test fun staleBytesCancellationAndOriginalOverwriteFailClosed() = temporary { root ->
        val source = File(root, "source.ply").apply { writeText("synthetic source") }
        val exporter = LegacyMeshDerivedExports(root, serializer); val option = exporter.describe(source, "mesh.obj.zip")
        val target = File.createTempFile("stage", ".partial", root)
        source.appendText(" changed")
        assertThrows(IllegalArgumentException::class.java) { exporter.materialize(source, request(option), target) {} }
        assertEquals(0, target.length()); val original = source.readBytes()
        assertThrows(IllegalArgumentException::class.java) { exporter.materialize(source, request(option), source) {} }
        assertArrayEquals(original, source.readBytes())
        assertThrows(ExportCancelled::class.java) { exporter.describe(source, "mesh.obj.zip") { throw ExportCancelled() } }
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".derived-export-") })
    }
}
