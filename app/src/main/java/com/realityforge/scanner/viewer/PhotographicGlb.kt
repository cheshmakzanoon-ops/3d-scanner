package com.realityforge.scanner.viewer

import org.json.JSONTokener
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException
import com.realityforge.scanner.viewer.loading.GlbStructure
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** Validated subset emitted by RealityForge, not a general-purpose glTF importer. */
@ConsistentCopyVisibility
data class PhotographicAsset internal constructor(
    val positions: FloatArray, val uv: FloatArray, val indices: IntArray,
    val png: ByteArray, val imageWidth: Int, val imageHeight: Int,
    val alphaCutoff: Float, val center: FloatArray, val radius: Float,
)

/** No URI resolution. All ranges/budgets are checked before allocating attribute/image buffers. */
object PhotographicGlb {
    const val MAX_FILE_BYTES = 32 * 1024 * 1024
    const val MAX_IMAGE_PIXELS = 4_500_000L
    private const val MAX_VERTICES = 240_000
    private const val MAX_INDICES = 240_000

    fun read(file: File): PhotographicAsset = read(file) { false }

    fun read(file: File, cancelled: () -> Boolean): PhotographicAsset {
        require(file.isFile && file.length() in 28L..MAX_FILE_BYTES.toLong()) { "Model file exceeds the preview budget or is missing." }
        // Bound the read itself: a replaced/growing file must not bypass the size check.
        val bytes = file.inputStream().use { input ->
            val expected = file.length().toInt()
            require(expected in 28..MAX_FILE_BYTES)
            val data = ByteArray(expected)
            var offset = 0
            while (offset < data.size) {
                if (cancelled()) throw CancellationException("Model load cancelled.")
                val count = input.read(data, offset, minOf(65536, data.size - offset))
                require(count > 0) { "Truncated GLB file." }
                offset += count
            }
            require(input.read() == -1) { "GLB changed while loading." }
            data
        }
        return decode(bytes, cancelled)
    }

    fun decode(bytes: ByteArray): PhotographicAsset = decode(bytes) { false }

    fun decode(bytes: ByteArray, cancelled: () -> Boolean): PhotographicAsset {
        if (cancelled()) throw CancellationException("Model load cancelled.")
        GlbStructure.validate(bytes, 4, cancelled)
        require(bytes.size in 28..MAX_FILE_BYTES) { "Invalid GLB size." }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun uint(offset: Int): Long = b.getInt(offset).toLong() and 0xffffffffL
        require(uint(0) == 0x46546c67L && uint(4) == 2L && uint(8) == bytes.size.toLong()) { "Invalid GLB header." }
        val jsonSize = uint(12)
        require(jsonSize in 2..262144 && jsonSize % 4 == 0L && uint(16) == 0x4e4f534aL)
        require(20L + jsonSize + 8 <= bytes.size)
        val binHeader = 20 + jsonSize.toInt()
        val binSize = uint(binHeader)
        require(binSize % 4 == 0L && uint(binHeader + 4) == 0x004e4942L)
        val binStart = binHeader + 8
        require(binStart.toLong() + binSize == bytes.size.toLong()) { "Unexpected/truncated GLB chunks." }
        val json = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 20, jsonSize.toInt())).toString()
        checkJsonDepth(json)
        val tokener = JSONTokener(json)
        val root = tokener.nextValue() as? JSONObject ?: error("Expected GLB object.")
        require(tokener.nextClean() == '\u0000') { "Trailing JSON data." }
        require(root.getJSONObject("asset").getString("version") == "2.0")
        // Required/used extensions were checked by the shared structural validator.
        for (key in listOf("animations", "skins", "extensions")) require(!root.has(key)) { "Unsupported model feature: $key" }
        val buffer = single(root, "buffers")
        require(!buffer.has("uri")) { "External model data is not permitted." }
        val declared = integer(buffer, "byteLength", MAX_FILE_BYTES)
        require(declared.toLong() <= binSize && binSize - declared <= 3)
        val scene = single(root, "scenes")
        require(integer(root, "scene", 0, 0) == 0 && scene.getJSONArray("nodes").length() == 1)
        require(scene.getJSONArray("nodes").get(0) == 0)
        val node = single(root, "nodes")
        require(integer(node, "mesh", 0) == 0)
        for (key in listOf("matrix", "translation", "rotation", "scale", "children", "skin", "extensions"))
            require(!node.has(key)) { "Transformed/instanced models are not supported in this preview." }
        val primitive = single(single(root, "meshes"), "primitives")
        require(integer(primitive, "mode", 4, 4) == 4 && integer(primitive, "material", 0) == 0)
        require(!primitive.has("targets") && !primitive.has("extensions"))
        val views = root.getJSONArray("bufferViews")
        val accessors = root.getJSONArray("accessors")
        require(views.length() in 1..16 && accessors.length() in 1..8)
        data class Range(val offset: Int, val size: Int, val stride: Int)
        fun view(index: Int): Range {
            require(index in 0 until views.length())
            val v = views.getJSONObject(index)
            require(integer(v, "buffer", 0, 0) == 0 && !v.has("extensions"))
            val start = integer(v, "byteOffset", declared, 0)
            val size = integer(v, "byteLength", declared)
            require(size > 0 && start.toLong() + size <= declared)
            val stride = integer(v, "byteStride", 252, 0)
            require(stride == 0 || (stride >= 4 && stride % 4 == 0))
            return Range(binStart + start, size, stride)
        }
        // Validate even unused ranges, not just the ones drawn by this renderer.
        for (i in 0 until views.length()) view(i)
        data class Attribute(val offset: Int, val count: Int, val stride: Int, val component: Int)
        fun attribute(index: Int, type: String, components: Int, limit: Int, floatOnly: Boolean): Attribute {
            require(index in 0 until accessors.length())
            val a = accessors.getJSONObject(index)
            require(a.getString("type") == type && !a.has("sparse") && !a.optBoolean("normalized", false))
            require(!a.has("extensions"))
            val component = integer(a, "componentType", 5126)
            require(if (floatOnly) component == 5126 else component in listOf(5121, 5123, 5125))
            val unit = when (component) { 5121 -> 1; 5123 -> 2; else -> 4 }
            val count = integer(a, "count", limit)
            require(count > 0)
            val r = view(integer(a, "bufferView", views.length() - 1))
            val offset = integer(a, "byteOffset", r.size, 0)
            val element = unit * components
            val stride = if (r.stride == 0) element else r.stride
            require(stride >= element && stride % unit == 0 && (r.offset - binStart + offset) % unit == 0)
            if (!floatOnly) require(r.stride == 0) { "Interleaved index data is unsupported." }
            require(offset.toLong() + (count - 1L) * stride + element <= r.size) { "Accessor exceeds buffer view." }
            return Attribute(r.offset + offset, count, stride, component)
        }
        val attrs = primitive.getJSONObject("attributes")
        require(attrs.keys().asSequence().all { it in setOf("POSITION", "NORMAL", "TEXCOORD_0") })
        val pos = attribute(integer(attrs, "POSITION", accessors.length() - 1), "VEC3", 3, MAX_VERTICES, true)
        val tex = attribute(integer(attrs, "TEXCOORD_0", accessors.length() - 1), "VEC2", 2, MAX_VERTICES, true)
        val normal = attribute(integer(attrs, "NORMAL", accessors.length() - 1), "VEC3", 3, MAX_VERTICES, true)
        require(pos.count == tex.count && pos.count == normal.count)
        val ind = attribute(integer(primitive, "indices", accessors.length() - 1), "SCALAR", 1, MAX_INDICES, false)
        require(ind.count % 3 == 0)
        val positions = FloatArray(pos.count * 3)
        val uv = FloatArray(tex.count * 2)
        val lo = FloatArray(3) { Float.POSITIVE_INFINITY }
        val hi = FloatArray(3) { Float.NEGATIVE_INFINITY }
        for (i in 0 until pos.count) {
            if (i % 1024 == 0 && cancelled()) throw CancellationException("Model load cancelled.")
            for (j in 0..2) {
                val value = b.getFloat(pos.offset + i * pos.stride + j * 4)
                require(value.isFinite() && abs(value) <= 1e9f) { "Invalid model coordinate." }
                positions[i * 3 + j] = value
                lo[j] = minOf(lo[j], value); hi[j] = maxOf(hi[j], value)
                require(b.getFloat(normal.offset + i * normal.stride + j * 4).isFinite())
            }
            val nx = b.getFloat(normal.offset + i * normal.stride).toDouble()
            val ny = b.getFloat(normal.offset + i * normal.stride + 4).toDouble()
            val nz = b.getFloat(normal.offset + i * normal.stride + 8).toDouble()
            require(abs(nx * nx + ny * ny + nz * nz - 1.0) <= 1e-3) { "Invalid model normal." }
            for (j in 0..1) {
                val value = b.getFloat(tex.offset + i * tex.stride + j * 4)
                require(value.isFinite() && value in 0f..1f) { "Invalid atlas coordinate." }
                uv[i * 2 + j] = value
            }
        }
        val indices = IntArray(ind.count) { i ->
            if (i % 1024 == 0 && cancelled()) throw CancellationException("Model load cancelled.")
            val at = ind.offset + i * ind.stride
            val value = when (ind.component) {
                5121 -> (b.get(at).toInt() and 255).toLong()
                5123 -> (b.getShort(at).toInt() and 65535).toLong()
                else -> uint(at)
            }
            require(value < pos.count) { "Model index is out of bounds." }; value.toInt()
        }
        for (i in indices.indices step 3) require(indices[i] != indices[i+1] && indices[i] != indices[i+2] && indices[i+1] != indices[i+2])
        val center = FloatArray(3) { (lo[it].toDouble() + hi[it]) .times(.5).toFloat() }
        val radius = sqrt((0..2).sumOf { val d = hi[it].toDouble() - lo[it]; d * d }).times(.5).toFloat()
        require(radius.isFinite() && radius > 1e-12f) { "Model has no measurable extent." }
        val material = single(root, "materials")
        require(material.optBoolean("doubleSided", false))
        if (material.has("extensions")) {
            val extensions = material.getJSONObject("extensions")
            require(extensions.keys().asSequence().all { it == "KHR_materials_unlit" })
            require(extensions.getJSONObject("KHR_materials_unlit").length() == 0)
        }
        for (key in listOf("normalTexture", "occlusionTexture", "emissiveTexture", "emissiveFactor"))
            require(!material.has(key)) { "Unsupported material feature: $key" }
        val mode = material.optString("alphaMode", "OPAQUE")
        require(mode == "MASK" || mode == "OPAQUE")
        val cutoff = if (mode == "MASK") material.optDouble("alphaCutoff", .5) else 0.0
        require(cutoff.isFinite() && cutoff in 0.0..1.0)
        val pbr = material.getJSONObject("pbrMetallicRoughness")
        require(!pbr.has("baseColorFactor") && !pbr.has("extensions") && !pbr.has("metallicRoughnessTexture"))
        require(pbr.optDouble("metallicFactor", 1.0) == 0.0 && pbr.optDouble("roughnessFactor", 1.0) == 1.0)
        val baseTexture = pbr.getJSONObject("baseColorTexture")
        require(integer(baseTexture, "index", 0) == 0 && integer(baseTexture, "texCoord", 0, 0) == 0 && !baseTexture.has("extensions"))
        val texture = single(root, "textures")
        require(integer(texture, "source", 0) == 0 && integer(texture, "sampler", 0) == 0 && !texture.has("extensions"))
        val sampler = single(root, "samplers")
        require(integer(sampler, "magFilter", 9729) == 9729 && integer(sampler, "minFilter", 9729) == 9729)
        require(integer(sampler, "wrapS", 33071) == 33071 && integer(sampler, "wrapT", 33071) == 33071)
        val image = single(root, "images")
        require(!image.has("uri") && !image.has("extensions") && image.getString("mimeType") == "image/png")
        val imageView = view(integer(image, "bufferView", views.length() - 1))
        require(imageView.stride == 0 && imageView.size <= 20 * 1024 * 1024)
        val png = bytes.copyOfRange(imageView.offset, imageView.offset + imageView.size)
        val (width, height) = validatePng(png, cancelled)
        return PhotographicAsset(positions, uv, indices, png, width, height, cutoff.toFloat(), center, radius)
    }

    private fun single(objectValue: JSONObject, key: String): JSONObject {
        val array = objectValue.getJSONArray(key)
        require(array.length() == 1) { "Only a single embedded photographic mesh is supported ($key)." }
        return array.getJSONObject(0)
    }
    private fun integer(o: JSONObject, key: String, max: Int, default: Int? = null): Int {
        if (!o.has(key) && default != null) return default
        val value = o.get(key)
        require(value is Number) { "Expected numeric $key." }
        val n = value.toDouble()
        require(n.isFinite() && n >= 0 && n <= max && n == floor(n)) { "Out-of-range $key." }
        return n.toInt()
    }
    private fun checkJsonDepth(json: String) {
        var depth = 0; var quoted = false; var escaped = false
        for (c in json) {
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else if (c == '"') quoted = true
            else if (c == '{' || c == '[') { depth++; require(depth <= 32) { "Excessive JSON nesting." } }
            else if (c == '}' || c == ']') { depth--; require(depth >= 0) }
        }
        require(depth == 0 && !quoted)
    }
    internal fun validatePng(png: ByteArray, cancelled: () -> Boolean = { false }): Pair<Int, Int> {
        require(png.size >= 57 && png.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
        val b = ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN)
        var at = 8; var width = 0; var height = 0; var idat = false; var afterIdat = false; var end = false; var chunks = 0; var channels = 0
        val dataRanges = mutableListOf<IntRange>()
        while (at < png.size) {
            if (cancelled()) throw CancellationException("Model load cancelled.")
            require(++chunks <= 10000 && at.toLong() + 12 <= png.size)
            val length = b.getInt(at).toLong() and 0xffffffffL
            require(length <= png.size && at + 12L + length <= png.size)
            val n = length.toInt()
            val type = String(png, at + 4, 4, Charsets.US_ASCII)
            require(type.all { it in 'A'..'Z' || it in 'a'..'z' } && (png[at+6].toInt() and 32) == 0) { "Invalid PNG chunk type." }
            val crc = CRC32().apply { update(png, at + 4, n + 4) }.value
            require(crc == (b.getInt(at + 8 + n).toLong() and 0xffffffffL)) { "Corrupt PNG checksum." }
            if (at == 8) {
                require(type == "IHDR" && n == 13)
                width = b.getInt(at + 8); height = b.getInt(at + 12)
                require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= MAX_IMAGE_PIXELS) { "Texture exceeds preview budget." }
                require(png[at+16].toInt() == 8 && png[at+17].toInt() in listOf(2,6))
                channels = if (png[at+17].toInt() == 6) 4 else 3
                require(png[at+18].toInt() == 0 && png[at+19].toInt() == 0 && png[at+20].toInt() == 0)
            } else when (type) {
                "IDAT" -> { require(n > 0 && !afterIdat); idat = true; dataRanges.add((at + 8) until (at + 8 + n)) }
                "IEND" -> { require(n == 0 && idat && at + 12 == png.size); end = true }
                else -> {
                    require(type !in listOf("IHDR", "acTL", "fcTL", "fdAT") && (png[at+4].toInt() and 32) != 0) { "Unsupported PNG chunk." }
                    if (idat) afterIdat = true
                }
            }
            at += n + 12
        }
        require(end)
        // Validate the compressed stream with one bounded scratch buffer, before
        // BitmapFactory can allocate. Exactly one filter byte + row pixels is
        // accepted per row; excess expansion and invalid filter types fail closed.
        val rowBytes = width * channels + 1
        val expected = rowBytes.toLong() * height
        val scratch = ByteArray(16384)
        val inflater = Inflater()
        var produced = 0L
        try {
            for (range in dataRanges) {
                require(!inflater.finished()) { "Trailing PNG compressed data." }
                inflater.setInput(png, range.first, range.last - range.first + 1)
                while (!inflater.needsInput() && !inflater.finished()) {
                    if (cancelled()) throw CancellationException("Model load cancelled.")
                    val count = inflater.inflate(scratch)
                    require(count > 0 || inflater.finished()) { "Invalid PNG compressed stream." }
                    require(produced + count <= expected) { "PNG decompression exceeds image dimensions." }
                    for (i in 0 until count) if ((produced + i) % rowBytes == 0L)
                        require((scratch[i].toInt() and 255) <= 4) { "Invalid PNG row filter." }
                    produced += count
                }
                require(inflater.remaining == 0) { "Trailing PNG compressed data." }
            }
            require(inflater.finished() && produced == expected) { "Truncated PNG pixel data." }
        } finally { inflater.end() }
        return width to height
    }
}
