package com.realityforge.scanner.agents.a005

import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Temporary branch-only probe used to capture the exact KSP-generated Room schema.
 * This test is removed once the generated baseline is committed and verified.
 */
class SchemaExportCaptureTest {
    @Test
    fun captureGeneratedBaselineSchemaForReview() {
        val relative = "schemas/com.realityforge.scanner.data.ScanDatabase/1.json"
        val start = File(System.getProperty("user.dir")).canonicalFile
        val roots = generateSequence(start) { it.parentFile }.take(6).toList()
        val candidates = roots.flatMap { root ->
            listOf(File(root, relative), File(root, "app/$relative"))
        }
        val schema = candidates.firstOrNull(File::isFile)
            ?: throw AssertionError(
                "A005 could not locate generated Room schema. Searched: " +
                    candidates.joinToString { it.path },
            )
        val encoded = Base64.getEncoder().encodeToString(schema.readBytes())
        throw AssertionError(
            "A005_ROOM_SCHEMA_V1_BASE64_BEGIN\n$encoded\n" +
                "A005_ROOM_SCHEMA_V1_BASE64_END\npath=${schema.path}",
        )
    }
}
