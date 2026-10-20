package com.realityforge.scanner.agents.a070

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.MainActivity
import com.realityforge.scanner.PhotographicViewerTest
import com.realityforge.scanner.ui.PhotoGlView
import com.realityforge.scanner.ui.PhotographicModelViewer
import com.realityforge.scanner.viewer.RenderControls
import com.realityforge.scanner.viewer.StudioRenderSurface
import com.realityforge.scanner.viewer.loading.LoadedPhotographicModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Test-only failed backend; success requires the real replacement GLES view to draw. */
@RunWith(AndroidJUnit4::class)
class RendererFallbackTest {
    @Test fun asynchronousAlternativeFailureActuallyMountsAndDrawsGlesFallback() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val bytes=PhotographicViewerTest().photographicFixtureBytes()
        val file=File(inst.targetContext.cacheDir,"a070-fallback-fixture.glb").apply { writeBytes(bytes) }
        val closed=AtomicBoolean(false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    val factory=remember {
                        { context: Context, _: LoadedPhotographicModel, failure: (String)->Unit ->
                            SyntheticFailingSurface(context,failure,closed) as StudioRenderSurface
                        }
                    }
                    PhotographicModelViewer(file,surfaceFactory=factory,onClose={})
                } }
                fun find(view: View): PhotoGlView? {
                    if(view is PhotoGlView) return view
                    if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                    return null
                }
                val deadline=SystemClock.elapsedRealtime()+30_000
                var rendered=false
                while(SystemClock.elapsedRealtime()<deadline && !rendered) {
                    inst.runOnMainSync {
                        val view=WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find)
                        if(view!=null) { assertNull(view.renderFailure); rendered=view.renderedFrames>0 }
                    }
                    if(!rendered) SystemClock.sleep(50)
                }
                assertTrue("Closed alternative view was retained instead of mounting GLES",rendered)
                assertTrue("Old renderer was not released",closed.get())
                assertArrayEquals(bytes,file.readBytes())
            }
        } finally { file.delete() }
    }

    private class SyntheticFailingSurface(
        context: Context,
        private val failure: (String)->Unit,
        private val closed: AtomicBoolean,
    ): View(context),StudioRenderSurface {
        override val view: View get()=this
        private val fired=AtomicBoolean(false)
        override val controls=object: RenderControls {
            override fun orbit(horizontalDegrees: Float,verticalDegrees: Float) {}
            override fun pan(horizontal: Float,vertical: Float) {}
            override fun magnify(factor: Float) {}
            override fun reset() {}
        }
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if(fired.compareAndSet(false,true)) post { failure("synthetic alternative backend failure") }
        }
        override fun resumeRendering() {}
        override fun pauseRendering() {}
        override fun close() { closed.set(true) }
    }
}
