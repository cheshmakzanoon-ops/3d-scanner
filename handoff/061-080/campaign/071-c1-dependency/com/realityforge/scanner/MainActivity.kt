package com.realityforge.scanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.realityforge.scanner.ui.ScannerApp

class MainActivity : ComponentActivity() {
    private val model: ScannerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ScannerApp(model) }
    }

    // M01 intentionally does not claim unlimited background reconstruction. Native pair calls
    // are bounded; cancellation is applied on return and before artifacts become current.
    // Agent 008 owns the durable runner/service gate required before this policy can be removed.
    override fun onStop() {
        model.onHostStopped()
        super.onStop()
    }
}
