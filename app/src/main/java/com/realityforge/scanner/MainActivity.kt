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

    // UI host events do not own the foreground runner's lifetime. The application controller
    // retains its durable job/attempt; platform service interruption follows the C7 policy.
    override fun onStop() {
        model.onHostStopped()
        super.onStop()
    }
}
