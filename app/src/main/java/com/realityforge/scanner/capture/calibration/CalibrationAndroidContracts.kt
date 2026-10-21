package com.realityforge.scanner.capture.calibration

import android.net.Uri

/** Android document-provider source bound to immutable metadata supplied by capture/import owners. */
data class CalibrationImageInput(
    val uri: Uri,
    val identity: CalibrationInputIdentity,
)
