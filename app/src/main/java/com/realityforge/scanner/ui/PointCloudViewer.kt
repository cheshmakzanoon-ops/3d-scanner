package com.realityforge.scanner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.json.JSONObject
import kotlin.math.*

private data class CloudPoint(val x: Float, val y: Float, val z: Float, val color: Color)

/** Diagnostic orthographic point viewer. Production textured meshes will use Filament. */
@Composable
fun PointCloudViewer(result: JSONObject, modifier: Modifier = Modifier) {
    val points = remember(result) {
        val rows = result.getJSONArray("points")
        val raw = (0 until rows.length()).map { i ->
            val row = rows.getJSONArray(i)
            CloudPoint(row.getDouble(0).toFloat(), -row.getDouble(1).toFloat(), -row.getDouble(2).toFloat(), Color(row.getInt(3), row.getInt(4), row.getInt(5)))
        }
        if (raw.isEmpty()) emptyList() else {
            val cx = raw.map { it.x }.sorted()[raw.size / 2]
            val cy = raw.map { it.y }.sorted()[raw.size / 2]
            val cz = raw.map { it.z }.sorted()[raw.size / 2]
            val radii = raw.map { sqrt((it.x - cx).pow(2) + (it.y - cy).pow(2) + (it.z - cz).pow(2)) }.sorted()
            val radius = radii[(radii.size * .95).toInt().coerceAtMost(radii.lastIndex)].coerceAtLeast(.001f)
            raw.map { it.copy(x = (it.x - cx) / radius, y = (it.y - cy) / radius, z = (it.z - cz) / radius) }
        }
    }
    var yaw by remember(result) { mutableFloatStateOf(0f) }
    var pitch by remember(result) { mutableFloatStateOf(0f) }
    var zoom by remember(result) { mutableFloatStateOf(1f) }
    Canvas(modifier.background(Color(0xFF0A1218)).semantics { contentDescription = "Sparse reconstructed 3D points, drag to orbit and pinch to zoom" }
        .pointerInput(result) {
            detectTransformGestures { _, pan, gestureZoom, _ ->
                yaw += pan.x * .008f; pitch = (pitch + pan.y * .008f).coerceIn(-1.5f, 1.5f)
                zoom = (zoom * gestureZoom).coerceIn(.3f, 5f)
            }
        }) {
        val scale = min(size.width, size.height) * .4f * zoom
        val ordered = points.map { p ->
            val x = cos(yaw) * p.x + sin(yaw) * p.z
            val z = -sin(yaw) * p.x + cos(yaw) * p.z
            val y = cos(pitch) * p.y - sin(pitch) * z
            val depth = sin(pitch) * p.y + cos(pitch) * z
            CloudPoint(x, y, depth, p.color)
        }.sortedBy { it.z }
        for (point in ordered) {
            val x = size.width / 2 + point.x * scale; val y = size.height / 2 - point.y * scale
            if (x in 0f..size.width && y in 0f..size.height) drawCircle(point.color, 1.8f, Offset(x, y))
        }
    }
}
