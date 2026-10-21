package com.realityforge.scanner.engine.pipeline

import com.realityforge.scanner.engine.artifacts.RepresentationKind
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class FeatureMatchRecord(val rows: DoubleArray, val firstKeys: IntArray, val secondKeys: IntArray) {
    init {
        require(firstKeys.size == secondKeys.size && firstKeys.size <= 4000 && rows.size == firstKeys.size * 7)
        require(firstKeys.all { it in 0..3999 } && secondKeys.all { it in 0..3999 })
        require(firstKeys.toSet().size == firstKeys.size && secondKeys.toSet().size == secondKeys.size)
        rows.forEachIndexed { index, value ->
            require(value.isFinite() && if (index % 7 < 4) value in -10.0..10.0 else value in 0.0..255.0)
        }
    }
}

/** Reciprocally matched feature indices/rays are reused only under their exact optics/settings identity. */
class FeatureMatchCache(private val directory: File, private val identity: PipelineStageIdentity,
    private val photoCount: Int) {
    private val maximumPairs = photoCount * (photoCount - 1) / 2
    val file = File(directory, "matches-${identity.digest}.rfmg")
    private val checksum = File(directory, "${file.name}.sha256")
    init { require(identity.stage == PipelineStage.MATCHING && photoCount in 2..12) }

    fun load(checkActive: () -> Unit = {}): Map<Pair<Int, Int>, FeatureMatchRecord>? = try {
        checkActive()
        require(!Files.isSymbolicLink(file.toPath()) && !Files.isSymbolicLink(checksum.toPath()))
        require(file.isFile && file.length() in 24..MAX_BYTES && checksum.isFile && checksum.length() == 64L)
        require(PipelineArtifactSession.hashFile(file).hex == checksum.readText())
        DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == MAGIC && input.readUTF() == identity.digest && input.readInt() == photoCount)
            val count = input.readInt(); require(count in 0..maximumPairs)
            val result = linkedMapOf<Pair<Int, Int>, FeatureMatchRecord>()
            repeat(count) {
                checkActive()
                val first = input.readInt(); val second = input.readInt()
                require(first in 0 until photoCount && second in first + 1 until photoCount)
                val size = input.readInt(); require(size in 0..4000)
                val left = IntArray(size); val right = IntArray(size); val rows = DoubleArray(size * 7)
                repeat(size) { index ->
                    if (index % 128 == 0) checkActive()
                    left[index] = input.readInt(); right[index] = input.readInt()
                    repeat(7) { column -> rows[index * 7 + column] = input.readDouble() }
                }
                require(result.put(first to second, FeatureMatchRecord(rows, left, right)) == null)
            }
            require(input.read() == -1)
            result
        }
    } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled
    } catch (_: Exception) { null }

    fun save(records: Map<Pair<Int, Int>, FeatureMatchRecord>, checkActive: () -> Unit = {}): List<PipelineStageFile> {
        checkActive()
        require(records.size <= maximumPairs)
        require(!Files.isSymbolicLink(file.toPath()) && !Files.isSymbolicLink(checksum.toPath()))
        val temporary = File.createTempFile("matches-", ".partial", directory)
        try {
            FileOutputStream(temporary).use { output ->
                val writer = DataOutputStream(output.buffered())
                writer.writeInt(MAGIC); writer.writeUTF(identity.digest); writer.writeInt(photoCount); writer.writeInt(records.size)
                records.toSortedMap(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second }).forEach { (pair, record) ->
                    checkActive()
                    require(pair.first in 0 until photoCount && pair.second in pair.first + 1 until photoCount)
                    writer.writeInt(pair.first); writer.writeInt(pair.second); writer.writeInt(record.firstKeys.size)
                    record.firstKeys.indices.forEach { index ->
                        if (index % 128 == 0) checkActive()
                        writer.writeInt(record.firstKeys[index]); writer.writeInt(record.secondKeys[index])
                        repeat(7) { column -> writer.writeDouble(record.rows[index * 7 + column]) }
                    }
                }
                writer.flush(); output.fd.sync()
            }
            require(temporary.length() <= MAX_BYTES)
            checkActive()
            val digest = PipelineArtifactSession.hashFile(temporary).hex.toByteArray(Charsets.US_ASCII)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            val checksumTemporary = File.createTempFile("matches-hash-", ".partial", directory)
            try {
                FileOutputStream(checksumTemporary).use { output -> output.write(digest); output.fd.sync() }
                Files.move(checksumTemporary.toPath(), checksum.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { checksumTemporary.delete() }
        } finally { temporary.delete() }
        require(load(checkActive) != null) { "Committed match graph failed its bounded round-trip validation." }
        return listOf(PipelineStageFile(file, RepresentationKind.MATCH_GRAPH),
            PipelineStageFile(checksum, RepresentationKind.DIAGNOSTIC_REPORT, "text/plain"))
    }

    companion object {
        // Version 3 admits the complete <=12-photo graph, never larger scenes.
        private const val MAGIC = 0x52464d33
        private const val MAX_BYTES = 128L + 66L * (12L + 4000L * 64L)
    }
}
