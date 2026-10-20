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
import org.json.JSONObject
import kotlin.math.*

private data class CloudPoint(val x: Float, val y: Float, val z: Float, val color: Color)

/** Diagnostic orthographic point viewer. Production textured meshes will use Filament. */
@Composable
fun PointCloudViewer(result: JSONObject, modifier: Modifier = Modifier) {
    val points = remember(result) {
        val rows = result.getJSONArray("points")
        // Bounded diagnostic sampling only; the source and exported cloud retain every point.
        val stride=max(1,ceil(rows.length()/12000.0).toInt())
        val raw = (0 until rows.length() step stride).map { i ->
            val row = rows.getJSONArray(i)
            CloudPoint(row.getDouble(0).toFloat(), -row.getDouble(1).toFloat(), -row.getDouble(2).toFloat(), Color(row.getInt(3), row.getInt(4), row.getInt(5)))
        }
        if (raw.isEmpty()) emptyList() else {
            val cx = raw.map { it.x }.sorted()[raw.size / 2]
            val cy = raw.map { it.y }.sorted()[raw.size / 2]
            val cz = raw.map { it.z }.sorted()[raw.size / 2]
            val radii = raw.map { sqrt((it.x - cx).pow(2) + (it.y - cy).pow(2) + (it.z - cz).pow(2)) }.sorted()
            val radius = radii.last().coerceAtLeast(.001f)
            raw.map { it.copy(x = (it.x - cx) / radius, y = (it.y - cy) / radius, z = (it.z - cz) / radius) }
        }
    }
    val identity = remember(result) { result.toString().hashCode().toString() }
    var pose by rememberStudioPose(identity)
    val label = stringResource(if (result.optString("representation") == "dense_points") R.string.a070_dense_points else R.string.a070_sparse_points)
    Column(modifier) {
    Canvas(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color(0xFF0A1218))
        .studioSemantics(label,pose) { pose=it }
        .pointerInput(result) {
            detectTransformGestures { _, pan, gestureZoom, _ ->
                pose=pose.orbit(pan.x*.45f,pan.y*.45f).magnify(gestureZoom)
            }
        }) {
        val yaw=Math.toRadians(pose.yaw.toDouble()).toFloat()
        val pitch=Math.toRadians(pose.pitch.toDouble()).toFloat()
        val zoom=pose.zoom
        val scale = min(size.width, size.height) * .4f * zoom
        val ordered = points.map { p ->
            val x = cos(yaw) * p.x + sin(yaw) * p.z
            val z = -sin(yaw) * p.x + cos(yaw) * p.z
            val y = cos(pitch) * p.y - sin(pitch) * z
            val depth = sin(pitch) * p.y + cos(pitch) * z
            CloudPoint(x, y, depth, p.color)
        }.sortedBy { it.z }
        for (point in ordered) {
            val x = size.width / 2 + (point.x-pose.panX) * scale; val y = size.height / 2 - (point.y-pose.panY) * scale
            if (x in 0f..size.width && y in 0f..size.height) drawCircle(point.color, 1.8f, Offset(x, y))
        }
    }
    if(result.getJSONArray("points").length()>12000) Text(stringResource(R.string.a070_points_sampled))
    StudioControls(pose,{ pose=it })
    }
}
