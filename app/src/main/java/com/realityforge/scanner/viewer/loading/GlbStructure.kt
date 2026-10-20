package com.realityforge.scanner.viewer.loading

import com.realityforge.scanner.viewer.PhotographicGlb
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.floor

/** Structural validation shared by exports and the photographic renderer subset.
 * This never resolves URIs or silently ignores additional scenes/primitives.
 * Summary counts describe the validated bytes; units remain unknown unless explicit.
 */
data class GlbSummary(
    val primitiveMode: Int,
    val vertexCount: Int,
    val indexCount: Int,
    val representation: String?,
    val units: String?,
    val hasPhotographicTexture: Boolean,
    val estimatedResidentBytes: Long,
)

object GlbStructure {
    private const val MAX_VERTICES = 2_000_000
    private const val MAX_INDICES = 12_000_000
    const val MAX_RESIDENT_BYTES = 192L * 1024 * 1024

    fun validate(file: File, expectedPrimitiveMode: Int? = null): GlbSummary =
        validate(readBounded(file) { false }, expectedPrimitiveMode)

    fun readBounded(file: File, cancelled: () -> Boolean): ByteArray {
        require(file.isFile && file.length() in 28L..PhotographicGlb.MAX_FILE_BYTES.toLong()) { "Missing model or file budget exceeded." }
        return file.inputStream().use { input ->
            val size = file.length()
            require(size in 28L..PhotographicGlb.MAX_FILE_BYTES.toLong())
            val bytes = ByteArray(size.toInt())
            var at = 0
            while (at < bytes.size) {
                if (cancelled()) throw CancellationException("Model load cancelled.")
                val n = input.read(bytes, at, minOf(65536, bytes.size - at))
                require(n > 0) { "Truncated model." }; at += n
            }
            require(input.read() == -1) { "Model changed while loading." }
            bytes
        }
    }

    fun validate(bytes: ByteArray, expectedPrimitiveMode: Int? = null, cancelled: () -> Boolean = { false }): GlbSummary {
        fun poll() { if (cancelled()) throw CancellationException("Model load cancelled.") }
        poll()
        require(bytes.size in 28..PhotographicGlb.MAX_FILE_BYTES)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun uint(at: Int): Long = b.getInt(at).toLong() and 0xffffffffL
        require(uint(0) == 0x46546c67L && uint(4) == 2L && uint(8) == bytes.size.toLong())
        val jsonLength = uint(12)
        require(jsonLength in 2L..262144L && jsonLength % 4 == 0L && uint(16) == 0x4e4f534aL)
        require(28L + jsonLength <= bytes.size)
        val binHeader = 20 + jsonLength.toInt()
        val binLength = uint(binHeader)
        val binStart = binHeader + 8
        require(binLength % 4 == 0L && uint(binHeader + 4) == 0x004e4942L && binStart.toLong() + binLength == bytes.size.toLong())
        val json = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 20, jsonLength.toInt())).toString()
        StrictJson.check(json)
        val tokener = JSONTokener(json)
        val root = tokener.nextValue() as? JSONObject ?: error("Expected GLB object.")
        require(tokener.nextClean() == '\u0000')
        fun integer(o: JSONObject, key: String, max: Int, default: Int? = null): Int {
            if (!o.has(key) && default != null) return default
            val value = o.get(key)
            require(value is Number)
            val d = value.toDouble()
            require(d.isFinite() && d >= 0 && d <= max && d == floor(d)) { "Invalid $key." }
            return d.toInt()
        }
        fun single(o: JSONObject, key: String): JSONObject = o.getJSONArray(key).let {
            require(it.length() == 1) { "Expected one $key." }; it.getJSONObject(0)
        }
        fun noExtensions(o: JSONObject) { require(!o.has("extensions")) { "Unsupported extensions." } }
        val asset = root.getJSONObject("asset"); noExtensions(asset)
        require(asset.getString("version") == "2.0" && (!asset.has("minVersion") || asset.getString("minVersion") == "2.0"))
        for (key in listOf("animations", "skins", "cameras", "extensions")) require(!root.has(key)) { "Unsupported $key." }
        for (key in listOf("extensionsUsed", "extensionsRequired")) if (root.has(key)) {
            val extensions = root.getJSONArray(key)
            require(extensions.length() <= 1)
            for (i in 0 until extensions.length()) require(extensions.getString(i) == "KHR_materials_unlit") { "Unsupported required/used extension." }
        }
        val buffer = single(root, "buffers"); noExtensions(buffer)
        require(!buffer.has("uri")) { "External URI resolution is prohibited." }
        val declared = integer(buffer, "byteLength", PhotographicGlb.MAX_FILE_BYTES)
        require(declared > 0 && declared <= binLength && binLength - declared <= 3)
        val scene = single(root, "scenes"); noExtensions(scene)
        require(integer(root, "scene", 0, 0) == 0 && scene.getJSONArray("nodes").length() == 1 && scene.getJSONArray("nodes").getInt(0) == 0)
        val node = single(root, "nodes"); noExtensions(node)
        require(integer(node, "mesh", 0) == 0)
        for (key in listOf("matrix", "translation", "rotation", "scale", "children", "skin", "camera", "weights")) require(!node.has(key))
        val mesh = single(root, "meshes"); noExtensions(mesh); require(!mesh.has("weights"))
        val primitive = single(mesh, "primitives"); noExtensions(primitive); require(!primitive.has("targets"))
        val mode = integer(primitive, "mode", 4, 4)
        require(mode == 0 || mode == 4) { "Only POINTS and TRIANGLES are supported." }
        if (expectedPrimitiveMode != null) require(mode == expectedPrimitiveMode) { "Unexpected asset representation." }
        val views = root.getJSONArray("bufferViews")
        val accessors = root.getJSONArray("accessors")
        require(views.length() in 1..16 && accessors.length() in 1..8)
        data class View(val start: Int, val size: Int, val stride: Int, val target: Int)
        val ranges = (0 until views.length()).map { i ->
            val v = views.getJSONObject(i); noExtensions(v)
            require(integer(v, "buffer", 0, 0) == 0)
            val start = integer(v, "byteOffset", declared, 0)
            val size = integer(v, "byteLength", declared)
            val stride = integer(v, "byteStride", 252, 0)
            require(size > 0 && start % 4 == 0 && start.toLong() + size <= declared && (stride == 0 || stride >= 4 && stride % 4 == 0))
            val target = integer(v, "target", 34963, 0)
            require(target in listOf(0,34962,34963))
            View(start, size, stride, target)
        }
        val ordered = ranges.sortedBy { it.start }
        require(ordered.zipWithNext().all { (a, c) -> a.start.toLong() + a.size <= c.start }) { "Overlapping buffer views are unsupported." }
        data class Accessor(val view: Int, val start: Int, val count: Int, val stride: Int, val unit: Int, val components: Int, val component: Int, val type: String, val normalized: Boolean)
        val parsed = (0 until accessors.length()).map { i ->
            val a = accessors.getJSONObject(i); noExtensions(a); require(!a.has("sparse"))
            val type = a.getString("type")
            val components = when (type) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; else -> error("Unsupported accessor type.") }
            val component = integer(a, "componentType", 5126)
            val unit = when (component) { 5121 -> 1; 5123 -> 2; 5125, 5126 -> 4; else -> error("Unsupported component type.") }
            val viewIndex = integer(a, "bufferView", ranges.size - 1)
            val v = ranges[viewIndex]
            val count = integer(a, "count", if (type == "SCALAR") MAX_INDICES else MAX_VERTICES)
            val offset = integer(a, "byteOffset", v.size, 0)
            val stride = if (v.stride == 0) unit * components else v.stride
            require(count > 0 && stride >= unit * components && stride % unit == 0 && (v.start + offset) % unit == 0)
            require(offset.toLong() + (count - 1L) * stride + components * unit <= v.size)
            if (a.has("normalized")) require(a.get("normalized") is Boolean)
            val normalized = a.optBoolean("normalized", false)
            require(!normalized || component in listOf(5121, 5123))
            Accessor(viewIndex, binStart + v.start + offset, count, stride, unit, components, component, type, normalized)
        }
        // Conservatively reject aliasing between distinct accessors. Shared
        // accessor indices are valid; distinct interleaved streams must not alias.
        for (i in parsed.indices) for (j in 0 until i) {
            val a = parsed[i]; val c = parsed[j]
            if (a.view != c.view) continue
            val ae = a.start.toLong() + (a.count - 1L) * a.stride + a.unit * a.components
            val ce = c.start.toLong() + (c.count - 1L) * c.stride + c.unit * c.components
            if (ae <= c.start || ce <= a.start) continue
            require(a.stride == c.stride)
            val delta = ((a.start - c.start) % a.stride + a.stride) % a.stride
            require(delta >= c.unit * c.components && a.stride - delta >= a.unit * a.components) { "Aliased accessor data." }
        }
        val attrs = primitive.getJSONObject("attributes")
        val semantics = attrs.keys().asSequence().map { it as? String ?: error("Invalid attribute name.") }.toSet()
        require(semantics.all { it in setOf("POSITION", "NORMAL", "TEXCOORD_0", "COLOR_0") } && "POSITION" in semantics)
        val referenced = mutableSetOf<Int>()
        fun attr(name: String): Accessor {
            val index = integer(attrs, name, parsed.size - 1); referenced.add(index); return parsed[index]
        }
        val position = attr("POSITION")
        require(position.type == "VEC3" && position.component == 5126 && !position.normalized)
        for (semantic in semantics) {
            val a = attr(semantic)
            require(a.count == position.count && a.start % 4 == 0 && a.stride % 4 == 0 && ranges[a.view].target in listOf(0,34962))
            if (semantic == "NORMAL") require(a.type == "VEC3" && a.component == 5126 && !a.normalized)
            if (semantic == "TEXCOORD_0") require(a.type == "VEC2" && a.component == 5126 && !a.normalized)
            if (semantic == "COLOR_0") require((a.type == "VEC3" || a.type == "VEC4") && (a.component == 5126 && !a.normalized || a.component in listOf(5121,5123) && a.normalized))
            for (i in 0 until a.count) {
                if (i % 1024 == 0) poll()
                if (a.component == 5126) for (j in 0 until a.components) {
                    val value = b.getFloat(a.start + i * a.stride + j * 4)
                    require(value.isFinite() && (semantic != "POSITION" || abs(value) <= 1e9f))
                    if (semantic == "TEXCOORD_0" || semantic == "COLOR_0") require(value in 0f..1f)
                }
            }
        }
        val posJson = accessors.getJSONObject(integer(attrs, "POSITION", parsed.size - 1))
        if (posJson.has("min") || posJson.has("max")) {
            val lo = posJson.getJSONArray("min"); val hi = posJson.getJSONArray("max")
            require(lo.length() == 3 && hi.length() == 3)
            for (j in 0..2) {
                val min = lo.getDouble(j); val max = hi.getDouble(j)
                require(min.isFinite() && max.isFinite() && min <= max)
                for (i in 0 until position.count) {
                    val value = b.getFloat(position.start + i * position.stride + j * 4).toDouble()
                    // JSON float bounds can round by one final decimal digit.
                    val tolerance = maxOf(1e-6, abs(value) * 1e-7)
                    require(value >= min - tolerance && value <= max + tolerance) { "Invalid accessor bounds." }
                }
            }
        }
        var indexCount = 0
        if (primitive.has("indices")) {
            val index = integer(primitive, "indices", parsed.size - 1); referenced.add(index)
            val a = parsed[index]
            require(a.type == "SCALAR" && a.component in listOf(5121,5123,5125) && !a.normalized && ranges[a.view].stride == 0 && ranges[a.view].target in listOf(0,34963))
            indexCount = a.count
            if (mode == 4) require(a.count % 3 == 0)
            var first = -1L; var second = -1L
            for (i in 0 until a.count) {
                if (i % 1024 == 0) poll()
                val at = a.start + i * a.stride
                val value = when (a.component) {5121 -> (b.get(at).toInt() and 255).toLong();5123 -> (b.getShort(at).toInt() and 65535).toLong();else -> uint(at)}
                require(value < position.count) { "Index out of range." }
                if (mode == 4) when (i % 3) {0 -> first = value;1 -> {require(value != first);second = value};2 -> require(value != first && value != second)}
            }
        } else if (mode == 4) require(position.count % 3 == 0)
        require(referenced.size == parsed.size) { "Unused unsupported accessor data." }
        val photo = root.has("images") || root.has("textures")
        var imagePixels = 0L
        if (photo) {
            require("TEXCOORD_0" in semantics)
            val image = single(root, "images"); noExtensions(image)
            require(!image.has("uri") && image.getString("mimeType") == "image/png")
            val v = ranges[integer(image, "bufferView", ranges.size - 1)]
            require(v.stride == 0 && v.target == 0 && v.size <= 20 * 1024 * 1024)
            val (width, height) = PhotographicGlb.validatePng(bytes.copyOfRange(binStart + v.start, binStart + v.start + v.size), cancelled)
            imagePixels = width.toLong() * height
            val texture = single(root, "textures"); noExtensions(texture)
            require(integer(texture, "source", 0) == 0 && integer(texture, "sampler", 0) == 0)
            val sampler = single(root, "samplers"); noExtensions(sampler)
            require(integer(sampler, "magFilter", 9729, 9729) == 9729 && integer(sampler, "minFilter", 9729, 9729) == 9729)
            require(integer(sampler, "wrapS", 33071, 33071) == 33071 && integer(sampler, "wrapT", 33071, 33071) == 33071)
        }
        if (primitive.has("material")) {
            require(integer(primitive, "material", 0) == 0)
            val material = single(root, "materials")
            if (material.has("extensions")) {
                val extensions = material.getJSONObject("extensions")
                require(extensions.keys().asSequence().toSet() == setOf("KHR_materials_unlit") && extensions.getJSONObject("KHR_materials_unlit").length() == 0)
            }
            for (key in listOf("normalTexture", "occlusionTexture", "emissiveTexture", "emissiveFactor")) require(!material.has(key))
            val pbr = material.optJSONObject("pbrMetallicRoughness")
            if (pbr != null) {
                noExtensions(pbr); require(!pbr.has("metallicRoughnessTexture"))
                for (key in listOf("metallicFactor", "roughnessFactor")) if (pbr.has(key)) {
                    require(pbr.get(key) is Number)
                    val value = pbr.getDouble(key); require(value.isFinite() && value in 0.0..1.0)
                }
                if (pbr.has("baseColorFactor")) {
                    val factor = pbr.getJSONArray("baseColorFactor"); require(factor.length() == 4)
                    for (i in 0..3) { require(factor.get(i) is Number); val value = factor.getDouble(i); require(value.isFinite() && value in 0.0..1.0) }
                }
                if (photo) {
                    val bt = pbr.getJSONObject("baseColorTexture"); noExtensions(bt)
                    require(integer(bt, "index", 0) == 0 && integer(bt, "texCoord", 0, 0) == 0)
                } else require(!pbr.has("baseColorTexture"))
            } else require(!photo)
        } else require(!photo && !root.has("materials"))
        val representation = root.optJSONObject("extras")?.optString("representation")?.takeIf { it.isNotEmpty() }
        val units = root.optJSONObject("extras")?.optString("units")?.takeIf { it.isNotEmpty() }
        if (representation in listOf("sparse_points", "dense_points")) require(mode == 0 && !photo)
        if (representation in listOf("observed_surface", "photographic_surface")) require(mode == 4)
        if (representation == "photographic_surface") require(photo)
        val resident = bytes.size.toLong() * 2 + position.count.toLong() * 64 + indexCount.toLong() * 8 + imagePixels * 12
        require(resident <= MAX_RESIDENT_BYTES) { "Model CPU/GPU residency budget exceeded." }
        poll()
        return GlbSummary(mode, position.count, indexCount, representation, units, photo, resident)
    }
}

/** Reject JSONTokener extensions, duplicate names and malformed Unicode before parsing. */
internal object StrictJson {
    fun check(text: String) {
        var at = 0
        fun space() { while (at < text.length && text[at] in " \t\r\n") at++ }
        fun string(): String {
            require(at < text.length && text[at++] == '"')
            val out = StringBuilder()
            while (at < text.length) {
                val c = text[at++]
                if (c == '"') return out.toString()
                require(c >= ' ')
                if (c != '\\') { out.append(c); continue }
                require(at < text.length)
                when (val escaped = text[at++]) {
                    '"', '\\', '/' -> out.append(escaped)
                    'b' -> out.append('\b'); 'f' -> out.append('\u000c'); 'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                    'u' -> {
                        require(at + 4 <= text.length)
                        val value = text.substring(at, at+4).toIntOrNull(16) ?: error("Invalid Unicode escape.")
                        out.append(value.toChar()); at += 4
                    }
                    else -> error("Invalid JSON escape.")
                }
            }
            error("Unterminated JSON string.")
        }
        fun value(depth: Int) {
            require(depth <= 32); space(); require(at < text.length)
            when (text[at]) {
                '{' -> {
                    at++; space(); val keys = mutableSetOf<String>()
                    if (at < text.length && text[at] == '}') { at++; return }
                    while (true) {
                        space(); require(keys.add(string())) { "Duplicate JSON property." }; space()
                        require(at < text.length && text[at++] == ':'); value(depth+1); space(); require(at < text.length)
                        val c = text[at++]; if (c == '}') return; require(c == ',')
                    }
                }
                '[' -> {
                    at++; space(); if (at < text.length && text[at] == ']') { at++; return }
                    while (true) {value(depth+1);space();require(at < text.length);val c=text[at++];if(c==']')return;require(c==',')}
                }
                '"' -> { string() }
                't', 'f', 'n' -> { val token = when(text[at]) {'t' -> "true";'f' -> "false";else -> "null"};require(text.startsWith(token,at));at+=token.length }
                else -> {
                    val start = at
                    if (text[at] == '-') at++
                    require(at < text.length && text[at].isDigit())
                    if (text[at] == '0') at++ else {require(text[at] in '1'..'9');while(at < text.length && text[at] in '0'..'9')at++}
                    if (at < text.length && text[at] == '.') {at++;val digits=at;while(at<text.length&&text[at] in '0'..'9')at++;require(at>digits)}
                    if (at < text.length && text[at] in "eE") {at++;if(at<text.length&&text[at] in "+-")at++;val digits=at;while(at<text.length&&text[at] in '0'..'9')at++;require(at>digits)}
                    require(at > start)
                }
            }
        }
        value(0); space(); require(at == text.length)
    }
}
