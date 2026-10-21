package com.realityforge.scanner.export

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption
import java.util.zip.ZipFile

/** Only caller-owned private staging paths reach JNI; no provider URI is a native path. */
object NativeAssetExports {
    init { System.loadLibrary("realityforge") }
    external fun writeObserved(source: String, destination: String, format: Int, sourceSha256: String, checkpoint: Runnable)
}

/** Injectable serializer permits transport/identity tests independently of Android's native loader. */
fun interface ObservedMeshSerializer {
    fun write(source: File, destination: File, format: Int, sourceSha256: String, checkpoint: () -> Unit)
}

class LegacyMeshDerivedExports(stagingRoot: File = File(System.getProperty("java.io.tmpdir")), private val serializer: ObservedMeshSerializer = ObservedMeshSerializer { source, target, format, hash, checkpoint ->
    NativeAssetExports.writeObserved(source.absolutePath, target.absolutePath, format, hash, Runnable { checkpoint() })
}) {
    // Resolve only the caller-owned staging root, never an untrusted source or target.
    // Android may expose app cache through /data/user/0 -> /data/data aliases.
    private val stagingRoot = stagingRoot.canonicalFile

    fun describe(source: File, id: String, checkpoint: () -> Unit = {}): ExportOption = staged(source, id, checkpoint) { result ->
        ExportOption(id, format(id), AssetRepresentation.OBSERVED_SURFACE, result.length(), arbitraryScale = true,
            contentSha256 = ExportCoordinator.digest(result, checkpoint))
    }

    fun materialize(source: File, request: ExportRequest, target: File, checkpoint: () -> Unit) {
        require(request.option.id in IDS && request.option.format == format(request.option.id))
        require(request.options.editIdentity == null && request.options.lodIdentity == null) { "VARIANT_UNAVAILABLE" }
        require(request.option.format != ExportFormat.STL || request.options.acknowledgedArbitraryScale) { "SCALE_ACKNOWLEDGMENT_REQUIRED" }
        // The coordinator supplies an empty private file. Refuse originals, aliases,
        // symlinks and nonempty targets before any serializer or truncate operation.
        require(target.canonicalFile != source.canonicalFile && Files.isRegularFile(target.toPath(), NOFOLLOW_LINKS) && target.length() == 0L) { "UNSAFE_EXPORT_TARGET" }
        require(target.toPath().toAbsolutePath().normalize().toFile() == target.canonicalFile) { "UNSAFE_EXPORT_TARGET" }
        staged(source, request.option.id, checkpoint) { result ->
            require(result.length() == request.option.bytes && ExportCoordinator.digest(result, checkpoint) == request.option.contentSha256) { "ARTIFACT_BYTES_CHANGED" }
            Files.newOutputStream(target.toPath(), StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { output ->
                result.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { checkpoint(); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
                }
                output.flush()
            }
        }
    }

    fun validate(option: ExportOption, file: File) {
        require(option.id in IDS && option.format == format(option.id) && option.representation == AssetRepresentation.OBSERVED_SURFACE && option.arbitraryScale)
        require(file.length() == option.bytes && file.length() in 1..MAX_BYTES && ExportCoordinator.digest(file) == option.contentSha256) { "ARTIFACT_BYTES_CHANGED" }
        if (option.format == ExportFormat.OBJ_PACKAGE) ZipFile(file).use { zip ->
            require(zip.size() == 2 && zip.getEntry("model.obj") != null && zip.getEntry("README.txt") != null)
        } else file.inputStream().use { input ->
            val header = ByteArray(84); require(input.read(header) == 84)
            val count = (0..3).fold(0L) { n, i -> n or ((header[80 + i].toLong() and 255) shl (8 * i)) }
            require(count in 1..400_000 && file.length() == 84 + 50 * count)
        }
    }

    private fun <T> staged(source: File, id: String, checkpoint: () -> Unit, use: (File) -> T): T {
        val format = format(id)
        require(Files.isRegularFile(source.toPath(), NOFOLLOW_LINKS) && source.canonicalFile == source.absoluteFile && source.length() in 1..MAX_BYTES) { "UNSAFE_MESH_SOURCE" }
        checkpoint()
        val sourceHash = ExportCoordinator.digest(source, checkpoint)
        val directory = Files.createTempDirectory(stagingRoot.toPath(), ".derived-export-").toFile()
        try {
            val geometry = File(directory, if (format == ExportFormat.STL) "model.stl" else "model.obj")
            serializer.write(source, geometry, if (format == ExportFormat.STL) 1 else 0, sourceHash, checkpoint)
            checkpoint()
            require(geometry.length() in 1..MAX_BYTES && ExportCoordinator.digest(source, checkpoint) == sourceHash) { "ARTIFACT_BYTES_CHANGED" }
            val result = if (format == ExportFormat.STL) geometry else {
                val notes = File(directory, "README.txt")
                notes.writeText("RealityForge observed open surface.\nArbitrary scale; no metric reference.\nNo photographic texture. Vertex colors and observation metadata are omitted by OBJ.\nUnknown regions remain open; printability is not established.\nSource SHA-256: $sourceHash\nCoordinates: x-right, y-up, z-backward; original winding preserved.\n", Charsets.US_ASCII)
                File(directory, "model.zip").also { ExportPackage.write(mapOf("model.obj" to geometry, "README.txt" to notes), it, MAX_BYTES, checkpoint) }
            }
            return use(result)
        } finally { directory.deleteRecursively() }
    }

    companion object {
        val IDS = setOf("mesh.obj.zip", "mesh.stl")
        const val MAX_BYTES = 64L * 1024 * 1024
        fun format(id: String): ExportFormat = when (id) {
            "mesh.obj.zip" -> ExportFormat.OBJ_PACKAGE
            "mesh.stl" -> ExportFormat.STL
            else -> error("OPTION_UNAVAILABLE")
        }
    }
}
