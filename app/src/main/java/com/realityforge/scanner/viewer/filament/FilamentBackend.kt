package com.realityforge.scanner.viewer.filament

import android.opengl.Matrix
import android.os.Looper
import android.view.Surface
import com.google.android.filament.*
import com.google.android.filament.gltfio.*
import com.realityforge.scanner.viewer.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Filament 1.56.0, main-thread confinement. No URI resolution or runtime asset downloads. */
class FilamentBackend private constructor(private val budget: RenderBudget) : RenderBackend {
    override val capabilities = setOf(InspectionMode.PHOTOGRAPHIC_UNLIT, InspectionMode.NEUTRAL_GEOMETRY)
    override var statistics = RenderStatistics()
        private set
    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var view: View? = null
    private var scene: Scene? = null
    private var camera: Camera? = null
    private var cameraEntity = 0
    private var lightEntity = 0
    private var materials: UbershaderProvider? = null
    private var loader: AssetLoader? = null
    private var resources: ResourceLoader? = null
    private var model: FilamentAsset? = null
    private var swapChain: SwapChain? = null
    private var closed = false
    private var width = 1
    private var height = 1
    private var yaw = 0.0
    private var pitch = 0.0
    private var zoom = 1.0
    private var panX = 0.0
    private var panY = 0.0
    private var modelGpuBytes = 0L

    companion object {
        fun create(budget: RenderBudget = RenderBudget()): FilamentBackend {
            check(Looper.myLooper() == Looper.getMainLooper()) { "Filament requires the owning main thread." }
            val backend = FilamentBackend(budget)
            try { backend.initialize(); return backend }
            catch (failure: Throwable) { backend.close(); throw failure }
        }
    }

    private fun checkOwner() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Filament called off its owning thread." }
        check(!closed) { "Renderer is closed." }
    }

    private fun initialize() {
        Filament.init(); Gltfio.init()
        val e = Engine.create(Engine.Backend.OPENGL).also { engine = it }
        renderer = e.createRenderer().apply {
            clearOptions = Renderer.ClearOptions().apply { clear = true; clearColor = floatArrayOf(.055f,.065f,.075f,1f) }
        }
        scene = e.createScene()
        cameraEntity = EntityManager.get().create()
        camera = e.createCamera(cameraEntity).apply { setExposure(1f) }
        view = e.createView().apply {
            setScene(scene); setCamera(camera)
            // Unlit photographic color is not passed through a filmic tone mapper.
            setPostProcessingEnabled(false); setShadowingEnabled(false)
            setAntiAliasing(View.AntiAliasing.NONE)
        }
        materials = UbershaderProvider(e)
        loader = AssetLoader(e, materials!!, EntityManager.get())
        resources = ResourceLoader(e, true)
        lightEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL).color(1f,1f,1f)
            .direction(-.3f,-.4f,-1f).intensity(100_000f).castShadows(false).build(e,lightEntity)
        scene!!.addEntity(lightEntity)
    }

    /** Validates the complete supported GLB before native gltfio sees any bytes. */
    fun load(bytes: ByteArray, mode: InspectionMode = InspectionMode.PHOTOGRAPHIC_UNLIT) {
        checkOwner()
        val started = System.nanoTime()
        require(bytes.size <= PhotographicGlb.MAX_FILE_BYTES)
        require(bytes.size >= 28)
        // Own one bounded snapshot so a caller cannot mutate validated bytes before native load.
        val source = bytes.copyOf()
        val header = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)
        // The safe parser checks counts, image dimensions, arithmetic and all resource URIs.
        val validated = PhotographicGlb.decode(source)
        val admission = budget.admit(bytes.size.toLong(), validated.positions.size / 3L,
            validated.indices.size.toLong(), validated.imageWidth, validated.imageHeight)
        val admittedGpuBytes = if (swapChain != null) budget.admitViewport(admission.estimatedGpuBytes,width,height) else admission.estimatedGpuBytes
        unload()
        val e = engine!!
        val prepared = materialVariant(source, header.getInt(12), mode)
        val direct = ByteBuffer.allocateDirect(prepared.size).order(ByteOrder.LITTLE_ENDIAN).apply { put(prepared); flip() }
        val candidate = requireNotNull(loader!!.createAsset(direct)) { "Filament rejected this validated GLB." }
        try {
            require(candidate.resourceUris.isEmpty()) { "External glTF resources are unsupported." }
            resources!!.loadResources(candidate)
            val tm = e.transformManager
            val transform = FloatArray(16)
            Matrix.setIdentityM(transform,0)
            val scale = 1f / validated.radius
            Matrix.scaleM(transform,0,scale,scale,scale)
            Matrix.translateM(transform,0,-validated.center[0],-validated.center[1],-validated.center[2])
            tm.setTransform(tm.getInstance(candidate.root),transform)
            scene!!.addEntities(candidate.entities)
            candidate.releaseSourceData()
            model = candidate
            modelGpuBytes = admission.estimatedGpuBytes
            statistics = admission.copy(estimatedGpuBytes=admittedGpuBytes,uploadNanos=System.nanoTime()-started)
            reset()
        } catch (failure: Throwable) {
            loader!!.destroyAsset(candidate); resources!!.evictResourceData(); throw failure
        }
    }

    private fun materialVariant(bytes: ByteArray, jsonBytes: Int, mode: InspectionMode): ByteArray {
        val root = JSONObject(String(bytes,20,jsonBytes,Charsets.UTF_8))
        val material = root.getJSONArray("materials").getJSONObject(0)
        if (mode == InspectionMode.PHOTOGRAPHIC_UNLIT) {
            val extensions = material.optJSONObject("extensions") ?: JSONObject().also { material.put("extensions",it) }
            extensions.put("KHR_materials_unlit",JSONObject())
            root.put("extensionsUsed",JSONArray().put("KHR_materials_unlit"))
        } else {
            material.remove("extensions")
            material.put("pbrMetallicRoughness",JSONObject().put("baseColorFactor",JSONArray("[0.65,0.65,0.65,1]"))
                .put("metallicFactor",0).put("roughnessFactor",1))
            material.put("alphaMode","OPAQUE")
        }
        val json = root.toString().toByteArray(Charsets.UTF_8)
        val aligned = (json.size + 3) and -4
        val binaryOffset = 20 + jsonBytes
        val total = Math.addExact(20 + aligned,bytes.size - binaryOffset)
        require(total <= PhotographicGlb.MAX_FILE_BYTES + 4096)
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546c67); putInt(2); putInt(total); putInt(aligned); putInt(0x4e4f534a)
            put(json); repeat(aligned-json.size) { put(32.toByte()) }; put(bytes,binaryOffset,bytes.size-binaryOffset)
        }.array()
    }

    override fun attach(surface: Surface, width: Int, height: Int) {
        checkOwner(); require(surface.isValid); detach()
        resize(width,height); swapChain = engine!!.createSwapChain(surface)
    }
    override fun detach() {
        checkOwner()
        swapChain?.let { engine!!.destroySwapChain(it); engine!!.flushAndWait() }
        swapChain = null
        statistics = statistics.copy(estimatedGpuBytes=modelGpuBytes)
    }
    override fun resize(width: Int, height: Int) {
        checkOwner(); require(width > 0 && height > 0)
        val gpuBytes = budget.admitViewport(modelGpuBytes,width,height)
        this.width = width; this.height = height
        statistics = statistics.copy(estimatedGpuBytes=gpuBytes)
        view!!.viewport = Viewport(0,0,width,height)
    }
    override fun orbit(horizontalDegrees: Float, verticalDegrees: Float) {
        checkOwner(); require(horizontalDegrees.isFinite() && verticalDegrees.isFinite())
        yaw = (yaw + horizontalDegrees) % 360.0; pitch = (pitch + verticalDegrees).coerceIn(-89.0,89.0)
    }
    override fun pan(horizontal: Float, vertical: Float) {
        checkOwner(); require(horizontal.isFinite() && vertical.isFinite())
        panX = (panX + horizontal).coerceIn(-2.0,2.0); panY = (panY + vertical).coerceIn(-2.0,2.0)
    }
    override fun magnify(factor: Float) { checkOwner(); require(factor.isFinite() && factor > 0); zoom = (zoom*factor).coerceIn(.35,5.0) }
    override fun reset() { checkOwner(); yaw=0.0; pitch=0.0; zoom=1.0; panX=0.0; panY=0.0 }
    override fun draw(frameTimeNanos: Long): Boolean {
        checkOwner()
        val chain = swapChain ?: return false
        if (model == null) return false
        val aspect = width.toDouble()/height
        val distance = 3.0/min(1.0,aspect)/zoom
        val y = Math.toRadians(yaw); val p = Math.toRadians(pitch)
        camera!!.setProjection(45.0,aspect,.01,100.0,Camera.Fov.VERTICAL)
        camera!!.lookAt(panX+distance*sin(y)*cos(p),panY+distance*sin(p),distance*cos(y)*cos(p),
            panX,panY,0.0,0.0,1.0,0.0)
        if (!renderer!!.beginFrame(chain,frameTimeNanos)) return false
        renderer!!.render(view!!); renderer!!.endFrame()
        statistics = statistics.copy(submittedFrames=statistics.submittedFrames+1)
        return true
    }
    internal fun cameraViewMatrix(): FloatArray {
        checkOwner(); return camera!!.getViewMatrix(FloatArray(16))
    }

    private fun unload() {
        model?.let { scene!!.removeEntities(it.entities); loader!!.destroyAsset(it) }
        model = null; modelGpuBytes=0L; resources?.evictResourceData()
    }
    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        engine?.let { e ->
            detach(); unload()
            resources?.destroy(); resources=null
            loader?.destroy(); loader=null
            materials?.destroyMaterials(); materials?.destroy(); materials=null
            if (lightEntity != 0) { scene?.remove(lightEntity); e.destroyEntity(lightEntity); EntityManager.get().destroy(lightEntity); lightEntity=0 }
            view?.let { e.destroyView(it) }; view=null
            camera?.let { e.destroyCameraComponent(cameraEntity) }; camera=null
            if (cameraEntity != 0) { EntityManager.get().destroy(cameraEntity); cameraEntity=0 }
            scene?.let { e.destroyScene(it) }; scene=null
            renderer?.let { e.destroyRenderer(it) }; renderer=null
            e.flushAndWait(); e.destroy(); engine=null
        }
        closed=true
    }
}
