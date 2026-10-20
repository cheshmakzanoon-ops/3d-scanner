package com.realityforge.scanner.ui.studio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.realityforge.scanner.R

private val PoseSaver = listSaver<StudioPose,Float>(
    save={ listOf(it.yaw,it.pitch,it.zoom,it.panX,it.panY) },
    restore={ runCatching { StudioPose(it[0],it[1],it[2],it[3],it[4]) }.getOrDefault(StudioPose()) },
)

@Composable
fun rememberStudioPose(identity: String): MutableState<StudioPose> =
    rememberSaveable(identity,stateSaver=PoseSaver) { mutableStateOf(StudioPose()) }

/** Every gesture has labelled buttons and matching semantic custom actions. */
@Composable
fun StudioControls(pose: StudioPose, onChange: (StudioPose) -> Unit, modifier: Modifier=Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            PoseButton(R.string.a070_orbit_left) { onChange(pose.orbit(-15f,0f)) }
            PoseButton(R.string.a070_orbit_right) { onChange(pose.orbit(15f,0f)) }
            PoseButton(R.string.a070_orbit_up) { onChange(pose.orbit(0f,15f)) }
            PoseButton(R.string.a070_orbit_down) { onChange(pose.orbit(0f,-15f)) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            PoseButton(R.string.a070_zoom_in) { onChange(pose.magnify(1.2f)) }
            PoseButton(R.string.a070_zoom_out) { onChange(pose.magnify(1/1.2f)) }
            PoseButton(R.string.a070_fit) { onChange(StudioPose()) }
            PoseButton(R.string.a070_top) { onChange(StudioPose(pitch=89f)) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            PoseButton(R.string.a070_pan_left) { onChange(pose.pan(-.15f,0f)) }
            PoseButton(R.string.a070_pan_right) { onChange(pose.pan(.15f,0f)) }
            PoseButton(R.string.a070_pan_up) { onChange(pose.pan(0f,.15f)) }
            PoseButton(R.string.a070_pan_down) { onChange(pose.pan(0f,-.15f)) }
        }
    }
}

@Composable
private fun PoseButton(label: Int, onClick: () -> Unit) {
    TextButton(onClick=onClick,modifier=Modifier.defaultMinSize(minHeight=48.dp)) { Text(stringResource(label)) }
}

@Composable
fun StudioFeatureControls(actions: StudioFeatureActions) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        actions.edit?.let { PoseButton(R.string.a070_edit,it) }
        actions.measure?.let { PoseButton(R.string.a070_measure,it) }
        actions.chooseLod?.let { PoseButton(R.string.a070_lod,it) }
        actions.export?.let { PoseButton(R.string.a070_export,it) }
    }
}

@Composable
fun Modifier.studioSemantics(label: String, pose: StudioPose, onChange: (StudioPose) -> Unit): Modifier {
    val left=stringResource(R.string.a070_orbit_left); val right=stringResource(R.string.a070_orbit_right)
    val zoomIn=stringResource(R.string.a070_zoom_in); val zoomOut=stringResource(R.string.a070_zoom_out)
    val fit=stringResource(R.string.a070_fit)
    val viewState=stringResource(R.string.a070_camera_state,pose.yaw.toInt(),pose.pitch.toInt(),(pose.zoom*100).toInt())
    return semantics {
        contentDescription=label; stateDescription=viewState
        customActions=listOf(
            CustomAccessibilityAction(left) { onChange(pose.orbit(-15f,0f)); true },
            CustomAccessibilityAction(right) { onChange(pose.orbit(15f,0f)); true },
            CustomAccessibilityAction(zoomIn) { onChange(pose.magnify(1.2f)); true },
            CustomAccessibilityAction(zoomOut) { onChange(pose.magnify(1/1.2f)); true },
            CustomAccessibilityAction(fit) { onChange(StudioPose()); true },
        )
    }
}
