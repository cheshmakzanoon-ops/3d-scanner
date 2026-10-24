package com.realityforge.scanner.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.realityforge.scanner.engine.SurfaceTopologySummary
import com.realityforge.scanner.engine.surfaceTopologyQualification

/** Always visible next to surface inspection/export, independently of recommendation caps. */
@Composable
internal fun SurfaceTopologyQualification(summary: SurfaceTopologySummary) {
    surfaceTopologyQualification(summary).forEach { line ->
        Text(line, style = MaterialTheme.typography.bodySmall)
    }
}
