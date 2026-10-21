package com.realityforge.scanner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.realityforge.scanner.R
import com.realityforge.scanner.ui.studio.*
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
    val identity = remember(geometry) { java.util.UUID.randomUUID().toString() }
    var pose by rememberStudioPose(identity)
    val label = "Reconstructed 3D points"
    Column(modifier) {
    Canvas(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color(0xFF0A1218))
        .studioSemantics(label,pose) { pose=it }
        .pointerInput(geometry) {
            detectTransformGestures { _, pan, gestureZoom, _ ->
                pose=pose.orbit(pan.x*.45f,pan.y*.45f).magnify(gestureZoom)
            }
        }) {
        val yaw=Math.toRadians(pose.yaw.toDouble()).toFloat()
        val pitch=Math.toRadians(pose.pitch.toDouble()).toFloat()
        val zoom=pose.zoom
        val scale = min(size.width, size.height) * .4f * zoom
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val stride = ceil(geometry.pointCount / 12000.0).toInt().coerceAtLeast(1)
        val ordered = (0 until geometry.pointCount step stride).map { index ->
            val x = cy * geometry.x(index) + sy * geometry.z(index)
            val z = -sy * geometry.x(index) + cy * geometry.z(index)
            val y = cp * geometry.y(index) - sp * z
            val depth = sp * geometry.y(index) + cp * z
            CloudPoint(x, y, depth, Color(0xFF000000.toInt() or geometry.rgb(index)))
        }.sortedBy { it.z }
        for (point in ordered) {
            val x = size.width / 2 + (point.x-pose.panX) * scale; val y = size.height / 2 - (point.y-pose.panY) * scale
            if (x in 0f..size.width && y in 0f..size.height) drawCircle(point.color, 1.8f, Offset(x, y))
        }
    }
    if(geometry.pointCount>12000) Text(stringResource(R.string.a070_points_sampled))
    StudioControls(pose,{ pose=it })
    }
}
