package com.realityforge.scanner.agents.a024

import com.realityforge.scanner.data.reopening.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class PreparedPreviewGeometryTest {
    @Test fun immutablePreparedBuffersPreserveColorsConventionAndBoundIdentity(): Unit = runBlocking {
        val points = mutableListOf(DisplayPoint(-2.0, 1.0, -3.0, 12, 128, 255),
            DisplayPoint(0.0, 0.0, 0.0, 30, 40, 50), DisplayPoint(2.0, -1.0, 3.0, 60, 70, 80))
        val source = model(points).copy(previousResult = true)
        val prepared = preparePreviewGeometry(source)
        assertEquals(source.artifactKey, prepared.artifactKey)
        assertEquals(3L, prepared.revision)
        assertTrue(prepared.previousResult)
        assertEquals(3, prepared.sparse.pointCount)
        assertEquals((-2 / sqrt(14.0)).toFloat(), prepared.sparse.x(0), 0.000001f)
        assertEquals((-1 / sqrt(14.0)).toFloat(), prepared.sparse.y(0), 0.000001f)
        assertEquals((3 / sqrt(14.0)).toFloat(), prepared.sparse.z(0), 0.000001f)
        assertEquals(0x0c80ff, prepared.sparse.rgb(0))
        assertNull(prepared.dense); assertNull(prepared.mesh)
        points.clear()
        assertEquals(3, prepared.sparse.pointCount)
        assertEquals(0x0c80ff, prepared.sparse.rgb(0))
        assertThrows(UnsupportedOperationException::class.java) {
            (prepared.capabilities as MutableSet<ScanRepresentation>).clear()
        }
    }

    @Test fun normalizationKeepsGeometryUnderTranslationAndPositiveScale() = runBlocking {
        val points = listOf(DisplayPoint(-2.0, 1.0, -3.0, 12, 128, 255),
            DisplayPoint(0.0, 0.0, 0.0, 30, 40, 50), DisplayPoint(2.0, -1.0, 3.0, 60, 70, 80))
        val original = preparePreviewGeometry(model(points)).sparse
        val transformed = preparePreviewGeometry(model(points.map {
            it.copy(x = it.x * 5 + 40, y = it.y * 5 - 7, z = it.z * 5 + 13)
        })).sparse
        for (index in points.indices) {
            assertEquals(original.x(index), transformed.x(index), 0.000001f)
            assertEquals(original.y(index), transformed.y(index), 0.000001f)
            assertEquals(original.z(index), transformed.z(index), 0.000001f)
            assertEquals(original.rgb(index), transformed.rgb(index))
        }
    }

    @Test fun surfacePreviewSamplesOffMainWithoutDiscardingTheFullValidatedSource() = runBlocking {
        val vertices = listOf(DisplayPoint(0.0, 0.0, 0.0, 1, 2, 3), DisplayPoint(1.0, 0.0, 0.0, 4, 5, 6),
            DisplayPoint(0.0, 1.0, 0.0, 7, 8, 9), DisplayPoint(1.0, 1.0, 0.0, 10, 11, 12))
        val faces = List(12_001) { if (it % 2 == 0) DisplayTriangle(0, 1, 2) else DisplayTriangle(1, 3, 2) }
        val source = model(vertices).copy(densePoints = vertices, meshVertices = vertices, triangles = faces,
            capabilities = setOf(ScanRepresentation.SPARSE_POINTS, ScanRepresentation.DENSE_POINTS, ScanRepresentation.OBSERVED_MESH))
        val surface = requireNotNull(preparePreviewGeometry(source).mesh)
        assertEquals(12_001, source.triangles.size)
        assertEquals(12_001, surface.sourceTriangleCount)
        assertEquals(6_001, surface.triangleCount)
        assertEquals(4, surface.vertices.pointCount)
        for (index in 0 until surface.triangleCount) {
            assertEquals(0, surface.index(index, 0)); assertEquals(1, surface.index(index, 1)); assertEquals(2, surface.index(index, 2))
        }
    }

    @Test fun invalidProgrammaticModelsAndCancellationCannotProducePreparedGeometry() = runBlocking {
        val points = listOf(DisplayPoint(0.0, 0.0, 0.0, 1, 2, 3))
        for (invalid in listOf(model(points.map { it.copy(x = Double.NaN) }),
            model(points).copy(meshVertices = points, triangles = listOf(DisplayTriangle(0, 1, 2))),
            model(List(PreviewSchemaValidator.MAX_POINTS + 1) { points.single() }))) {
            try { preparePreviewGeometry(invalid); fail("Invalid model was prepared") } catch (_: IllegalArgumentException) { }
        }
        try {
            withContext(Job().apply { cancel() }) { preparePreviewGeometry(model(points)) }
            fail("Cancelled preparation completed")
        } catch (_: CancellationException) { }
    }

    private fun model(points: List<DisplayPoint>) = ScanDisplayModel(1, 3, "sparse-m19-r3-0123456789abcdef",
        points, emptyList(), emptyList(), emptyList(), setOf(ScanRepresentation.SPARSE_POINTS),
        mapOf("dense" to "No supported depth", "mesh" to "No supported mesh", "texture" to "No supported texture"))
}
