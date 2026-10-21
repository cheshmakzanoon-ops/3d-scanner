package com.realityforge.scanner.capture.guidance

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.realityforge.scanner.R

/** Image-pair advice stays visibly separate from any claim about a complete object model. */
@Composable
fun CaptureGuidancePanel(observation: GuidanceObservation) {
    Column {
        Text(stringResource(R.string.a017_saved_pair_title), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(when (observation.imageOverlap) {
            ImageOverlapState.UNKNOWN -> R.string.a017_overlap_unknown
            ImageOverlapState.INSUFFICIENT -> R.string.a017_overlap_insufficient
            ImageOverlapState.USEFUL -> R.string.a017_overlap_observed
            ImageOverlapState.VERY_HIGH_REPEATED_VIEW -> R.string.a017_overlap_repeated
        }), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(when (observation.actionCode) {
            GuidanceActionCode.WAIT_FOR_MATCH_EVIDENCE -> R.string.a017_wait
            GuidanceActionCode.ADD_OVERLAPPING_VIEW -> R.string.a017_more_overlap
            GuidanceActionCode.HOLD_STEADY_AND_RETAKE -> R.string.a017_hold_steady
            GuidanceActionCode.KEEP_OBJECT_STILL_AND_RETAKE -> R.string.a017_keep_still
            GuidanceActionCode.MOVE_SIDEWAYS_AROUND_OBJECT -> R.string.a017_move_sideways
            GuidanceActionCode.CHANGE_VIEWPOINT -> R.string.a017_change_viewpoint
            GuidanceActionCode.CAPTURE_THIS_VIEW -> R.string.a017_useful_view
        }), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(if (observation.localPairGeometryValidated) R.string.a017_pair_geometry
            else R.string.a017_pose_unknown), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.a017_coverage_unknown), style = MaterialTheme.typography.bodySmall)
    }
}
