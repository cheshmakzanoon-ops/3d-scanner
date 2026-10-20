package com.realityforge.scanner.viewer.filament

import android.content.Context
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.realityforge.scanner.viewer.*

/** An explicitly selected backend. Caller shows the verified GLES path if failure is reported. */
class FilamentStudioView(
    context: Context,
    private val localGlb: ByteArray,
    private val mode: InspectionMode = InspectionMode.PHOTOGRAPHIC_UNLIT,
    private val budget: RenderBudget = RenderBudget(),
    private val onFailure: (String) -> Unit,
) : SurfaceView(context), SurfaceHolder.Callback, Choreographer.FrameCallback, RenderControls, StudioRenderSurface {
    override val view: android.view.View get() = this
    override val controls: RenderControls get() = this
    private var backend: FilamentBackend? = null
    private var resumed = true
    private var scheduled = false
    private var closed = false
    private var pendingYaw = 0f
    private var pendingPitch = 0f
    private var pendingZoom = 1f
    private var pendingPanX = 0f
    private var pendingPanY = 0f
    val renderStatistics: RenderStatistics get() = backend?.statistics ?: RenderStatistics()
    internal fun cameraViewMatrix(): FloatArray = checkNotNull(backend).cameraViewMatrix()
    internal fun displayModelMatrix(): FloatArray = checkNotNull(backend).displayModelMatrix()

    init {
        require(localGlb.size <= PhotographicGlb.MAX_FILE_BYTES)
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (closed) return
        try {
            val current = backend ?: FilamentBackend.create(budget).also {
                backend = it; it.load(localGlb,mode)
            }
            current.reset(); current.orbit(pendingYaw,pendingPitch)
            current.magnify(pendingZoom); current.pan(pendingPanX,pendingPanY)
            current.attach(holder.surface,width.coerceAtLeast(1),height.coerceAtLeast(1))
            schedule()
        } catch (failure: Exception) { failed(failure.message ?: "Filament could not load this model.") }
        catch (failure: LinkageError) { failed("Filament native library is unavailable for this device.") }
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (closed || width <= 0 || height <= 0) return
        try { backend?.resize(width,height) }
        catch (failure: Exception) { failed(failure.message ?: "Filament viewport is unavailable.") }
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        unschedule(); backend?.detach()
    }
    override fun resumeRendering() { if (!closed) { resumed=true; schedule() } }
    override fun pauseRendering() { resumed=false; unschedule() }
    override fun close() = dispose()
    fun dispose() {
        if (closed) return
        closed=true; unschedule(); holder.removeCallback(this)
        backend?.close(); backend=null
    }
    private fun failed(message: String) {
        dispose(); onFailure(message)
    }
    private fun schedule() {
        if (!closed && resumed && holder.surface.isValid && backend != null && !scheduled) {
            scheduled=true; Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private fun unschedule() {
        if (scheduled) Choreographer.getInstance().removeFrameCallback(this)
        scheduled=false
    }
    override fun doFrame(frameTimeNanos: Long) {
        scheduled=false
        if (closed || !resumed || !holder.surface.isValid) return
        try { backend?.draw(frameTimeNanos); schedule() }
        catch (failure: Exception) { failed(failure.message ?: "Filament frame failed.") }
    }
    override fun orbit(horizontalDegrees: Float, verticalDegrees: Float) {
        require(horizontalDegrees.isFinite() && verticalDegrees.isFinite())
        pendingYaw=(pendingYaw+horizontalDegrees)%360f
        pendingPitch=(pendingPitch+verticalDegrees).coerceIn(-89f,89f)
        backend?.orbit(horizontalDegrees,verticalDegrees)
    }
    override fun pan(horizontal: Float, vertical: Float) {
        require(horizontal.isFinite() && vertical.isFinite())
        pendingPanX=(pendingPanX+horizontal).coerceIn(-2f,2f)
        pendingPanY=(pendingPanY+vertical).coerceIn(-2f,2f)
        backend?.pan(horizontal,vertical)
    }
    override fun magnify(factor: Float) {
        require(factor.isFinite() && factor>0)
        pendingZoom=(pendingZoom*factor).coerceIn(.35f,5f); backend?.magnify(factor)
    }
    override fun reset() {
        pendingYaw=0f; pendingPitch=0f; pendingZoom=1f; pendingPanX=0f; pendingPanY=0f
        backend?.reset()
    }
    override fun onDetachedFromWindow() { dispose(); super.onDetachedFromWindow() }
}
