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
import com.realityforge.scanner.data.reopening.PreparedPointCloud
import kotlin.math.*

private data class CloudPoint(val x: Float, val y: Float, val z: Float, val color: Color)

/** Diagnostic orthographic point viewer. Production textured meshes will use Filament. */
@Composable
fun PointCloudViewer(geometry: PreparedPointCloud, modifier: Modifier = Modifier) {
    var yaw by remember(geometry) { mutableFloatStateOf(0f) }
    var pitch by remember(geometry) { mutableFloatStateOf(0f) }
    var zoom by remember(geometry) { mutableFloatStateOf(1f) }
    Canvas(modifier.background(Color(0xFF0A1218)).semantics { contentDescription = "Reconstructed 3D points, drag to orbit and pinch to zoom" }
        .pointerInput(geometry) {
            detectTransformGestures { _, pan, gestureZoom, _ ->
                yaw += pan.x * .008f; pitch = (pitch + pan.y * .008f).coerceIn(-1.5f, 1.5f)
                zoom = (zoom * gestureZoom).coerceIn(.3f, 5f)
            }
        }) {
        val scale = min(size.width, size.height) * .4f * zoom
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val ordered = List(geometry.pointCount) { index ->
            val x = cy * geometry.x(index) + sy * geometry.z(index)
            val z = -sy * geometry.x(index) + cy * geometry.z(index)
            val y = cp * geometry.y(index) - sp * z
            val depth = sp * geometry.y(index) + cp * z
            CloudPoint(x, y, depth, Color(0xFF000000.toInt() or geometry.rgb(index)))
        }.sortedBy { it.z }
        for (point in ordered) {
            val x = size.width / 2 + point.x * scale; val y = size.height / 2 - point.y * scale
            if (x in 0f..size.width && y in 0f..size.height) drawCircle(point.color, 1.8f, Offset(x, y))
        }
    }
}
