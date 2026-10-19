package com.realityforge.scanner.agents.a005

import java.io.File
import java.util.Base64
import org.junit.Test

/** Temporary branch-only probe removed after exact compiler/source receipts are committed. */
class SchemaExportCaptureTest {
    @Test
    fun captureGeneratedVersion2SchemaAndManifestForReview() {
        val start = File(System.getProperty("user.dir") ?: ".").canonicalFile
        val roots = generateSequence(start) { it.parentFile }.take(6).toList()
        val schema = locate(
            roots,
            "schemas/com.realityforge.scanner.data.ScanDatabase/2.json",
        )
        val manifest = locate(roots, "SOURCE_MANIFEST.sha256")
        val encoder = Base64.getEncoder()
        throw AssertionError(
            "A005_ROOM_SCHEMA_V2_BASE64_BEGIN\n" +
                encoder.encodeToString(schema.readBytes()) +
                "\nA005_ROOM_SCHEMA_V2_BASE64_END\n" +
                "A005_SOURCE_MANIFEST_BASE64_BEGIN\n" +
                encoder.encodeToString(manifest.readBytes()) +
                "\nA005_SOURCE_MANIFEST_BASE64_END\n" +
                "schema=${schema.path}\nmanifest=${manifest.path}",
        )
    }

    private fun locate(roots: List<File>, relative: String): File {
        val candidates = roots.flatMap { root ->
            listOf(File(root, relative), File(root, "app/$relative"))
        }
        return candidates.firstOrNull(File::isFile)
            ?: throw AssertionError("A005 could not locate $relative. Searched: " + candidates.joinToString())
    }
}
