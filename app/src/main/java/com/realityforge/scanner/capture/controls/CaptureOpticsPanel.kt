package com.realityforge.scanner.capture.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.realityforge.scanner.R
import com.realityforge.scanner.capture.session.BoundCaptureFeatures
import java.util.UUID
import kotlinx.coroutines.launch

@Composable
fun CaptureOpticsPanel(features: BoundCaptureFeatures, sessionToken: String) {
    val state by features.optics.state.collectAsState()
    val scope = rememberCoroutineScope()
    val capabilities = features.capabilities
    val pending = listOf(state.focus.status, state.aeLock.status, state.awbLock.status,
        state.zoom.status).any { it == ControlStatus.PENDING }
    fun submit(focus: FocusRequest = FocusRequest.KeepCurrent,
        ae: LockDirective = LockDirective.KEEP, awb: LockDirective = LockDirective.KEEP) {
        scope.launch {
            features.optics.submit(OpticalControlRequest(CaptureSessionToken(sessionToken),
                OpticalCommandId(UUID.randomUUID().toString()), capabilities.camera,
                focus = focus, aeLock = ae, awbLock = awb))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = capabilities.focusMeteringSupported && !pending,
                onClick = { submit(focus = FocusRequest.MeterAt(NormalizedPoint(.5f, .5f))) }) {
                Text(stringResource(R.string.a014_focus_center))
            }
            OutlinedButton(enabled = capabilities.aeLockAvailable && !pending,
                onClick = { submit(ae = if (state.metadata?.aeLocked == true) LockDirective.UNLOCK else LockDirective.LOCK) }) {
                Text(stringResource(if (state.metadata?.aeLocked == true) R.string.a014_unlock_exposure else R.string.a014_lock_exposure))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = capabilities.awbLockAvailable && !pending,
                onClick = { submit(awb = if (state.metadata?.awbLocked == true) LockDirective.UNLOCK else LockDirective.LOCK) }) {
                Text(stringResource(if (state.metadata?.awbLocked == true) R.string.a014_unlock_white_balance else R.string.a014_lock_white_balance))
            }
        }
        if (pending || state.aeLock.status == ControlStatus.ACKNOWLEDGED_UNCONFIRMED ||
            state.awbLock.status == ControlStatus.ACKNOWLEDGED_UNCONFIRMED) {
            Text(stringResource(R.string.a014_optics_confirming))
        }
        state.diagnosticDetail?.let { Text(it) }
    }
}
