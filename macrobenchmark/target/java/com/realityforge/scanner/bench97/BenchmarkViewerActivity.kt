package com.realityforge.scanner.bench97

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.realityforge.scanner.ui.PhotographicModelViewer
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Benchmark variant only: feeds labelled synthetic GLBs to the actual production parser/viewer. */
class BenchmarkViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("fixture") ?: "small"
        require(name in setOf("small", "large"))
        val bytes = assets.open("a097-$name.glb").use { it.readBytes() }
        val expected = JSONObject(assets.open("a097-fixtures.json").bufferedReader().use { it.readText() })
            .getJSONObject("fixtures").getJSONObject(name).getString("sha256")
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(actual == expected) { "Benchmark fixture checksum mismatch" }
        val file = File(cacheDir, "a097-$name.glb").apply { writeBytes(bytes) }
        setContent { MaterialTheme { PhotographicModelViewer(file) { finish() } } }
    }
}
