package com.realityforge.scanner.agents.a070

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.MainActivity
import com.realityforge.scanner.R
import com.realityforge.scanner.ui.PointCloudViewer
import com.realityforge.scanner.data.reopening.PreviewSchemaValidator
import com.realityforge.scanner.data.reopening.ReopenResult
import com.realityforge.scanner.data.reopening.preparePreviewGeometry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic analytic points exercise real viewer buttons through Android accessibility. */
@RunWith(AndroidJUnit4::class)
class ViewerControlsTest {
    @Test fun zoomRotateAndFitWorkWithoutPinchOrDrag() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val context=inst.targetContext
        val artifactKey = "sparse-m19-r1-0123456789abcdef"
        val synthetic = JSONObject("""{"schema":1,"representation":"sparse_points","scale":"arbitrary",
            "revision":1,"artifactKey":"$artifactKey","registeredCameras":2,"pointCount":2,
            "points":[[0,0,0,255,0,0],[1,1,0,0,255,0]]}""")
        val validated = PreviewSchemaValidator.validate(synthetic, 1, artifactKey)
        assertTrue("Synthetic control fixture must pass the production schema validator", validated is ReopenResult.Ready)
        // Preparation runs off the UI thread through the same strict adapter used by the real studio.
        val geometry = runBlocking { preparePreviewGeometry((validated as ReopenResult.Ready).model).sparse }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a -> a.setContent {
                PointCloudViewer(geometry,Modifier.fillMaxSize())
            } }
            fun nodes(): List<AccessibilityNodeInfo> {
                val root=inst.uiAutomation.rootInActiveWindow ?: return emptyList()
                fun collect(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(n)+(0 until n.childCount).flatMap { i -> n.getChild(i)?.let(::collect) ?: emptyList() }
                return collect(root)
            }
            fun await(predicate: (List<AccessibilityNodeInfo>)->Boolean) {
                val deadline=SystemClock.elapsedRealtime()+10_000
                while(SystemClock.elapsedRealtime()<deadline) {
                    if(predicate(nodes())) return
                    SystemClock.sleep(50)
                }
                fail("Viewer accessibility action/state did not become available")
            }
            fun click(label: String) {
                await { all ->
                    val n=all.firstOrNull { it.text?.toString()==label && it.isClickable }
                        ?: all.firstOrNull { it.text?.toString()==label }?.parent?.takeIf { it.isClickable }
                    n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
                }
            }
            await { all -> all.any { it.contentDescription?.toString()=="Reconstructed 3D points" } }
            click(context.getString(R.string.a070_zoom_in))
            val zoomed=context.getString(R.string.a070_camera_state,0,0,120)
            await { all -> all.any { AccessibilityNodeInfoCompat.wrap(it).stateDescription?.toString()==zoomed } }
            click(context.getString(R.string.a070_orbit_right))
            val rotated=context.getString(R.string.a070_camera_state,15,0,120)
            await { all -> all.any { AccessibilityNodeInfoCompat.wrap(it).stateDescription?.toString()==rotated } }
            click(context.getString(R.string.a070_fit))
            val reset=context.getString(R.string.a070_camera_state,0,0,100)
            await { all -> all.any { AccessibilityNodeInfoCompat.wrap(it).stateDescription?.toString()==reset } }
        }
    }
}
