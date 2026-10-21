package com.realityforge.scanner.engine.artifacts

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal class CanonicalWriter(private val maxBytes: Int) {
    private val bytes = ByteArrayOutputStream()
    private val output = DataOutputStream(bytes)

    fun boolean(value: Boolean) {
        output.writeByte(if (value) 1 else 0)
        checkSize()
    }

    fun int(value: Int) {
        output.writeInt(value)
        checkSize()
    }

    fun long(value: Long) {
        output.writeLong(value)
        checkSize()
    }

    fun double(value: Double) {
        require(value.isFinite()) { "Non-finite values cannot be serialized" }
        output.writeLong(value.toBits())
        checkSize()
    }

    fun hash(value: Sha256) {
        raw(value.toByteArray())
    }

    fun string(value: String, maximumBytes: Int) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= maximumBytes) { "String exceeds its wire-format bound" }
        int(encoded.size)
        raw(encoded)
    }

    fun nullableHash(value: Sha256?) {
        boolean(value != null)
        if (value != null) hash(value)
    }

    fun count(value: Int, maximum: Int, label: String) {
        require(value in 0..maximum) { "$label count is out of bounds" }
        int(value)
    }

    fun raw(value: ByteArray) {
        require(bytes.size().toLong() + value.size.toLong() <= maxBytes.toLong()) { "Serialized artifact contract exceeds $maxBytes bytes" }
        output.write(value)
        checkSize()
    }

    fun toByteArray(): ByteArray {
        output.flush()
        checkSize()
        return bytes.toByteArray()
    }

    private fun checkSize() {
        require(bytes.size() <= maxBytes) { "Serialized artifact contract exceeds $maxBytes bytes" }
    }
}

internal class CanonicalReader(
    bytes: ByteArray,
    private val maximumBytes: Int,
) {
    private val input: DataInputStream
    private var remaining: Int

    init {
        if (bytes.size > maximumBytes) {
            throw ArtifactFormatException("Encoded artifact contract exceeds $maximumBytes bytes")
        }
        input = DataInputStream(ByteArrayInputStream(bytes))
        remaining = bytes.size
    }

    fun boolean(label: String): Boolean = when (val value = byte(label).toInt() and 0xff) {
        0 -> false
        1 -> true
        else -> throw ArtifactFormatException("$label has an invalid boolean value: $value")
    }

    fun byte(label: String): Byte {
        requireRemaining(1, label)
        remaining -= 1
        return input.readByte()
    }

    fun int(label: String): Int {
        requireRemaining(4, label)
        remaining -= 4
        return input.readInt()
    }

    fun long(label: String): Long {
        requireRemaining(8, label)
        remaining -= 8
        return input.readLong()
    }

    fun double(label: String): Double {
        val value = Double.fromBits(long(label))
        if (!value.isFinite()) throw ArtifactFormatException("$label is non-finite")
        return value
    }

    fun hash(label: String): Sha256 = Sha256.fromBytes(raw(32, 32, label))

    fun nullableHash(label: String): Sha256? = if (boolean("$label presence")) hash(label) else null

    fun string(maximumBytes: Int, label: String): String {
        val length = int("$label length")
        if (length !in 0..maximumBytes) {
            throw ArtifactFormatException("$label length is out of bounds: $length")
        }
        val encoded = raw(length, maximumBytes, label)
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(encoded)).toString()
        } catch (failure: Exception) {
            throw ArtifactFormatException("$label is not valid UTF-8", failure)
        }
    }

    fun count(maximum: Int, minimumBytesPerItem: Int, label: String): Int {
        val value = int("$label count")
        if (value !in 0..maximum) {
            throw ArtifactFormatException("$label count is out of bounds: $value")
        }
        if (minimumBytesPerItem > 0 && value.toLong() * minimumBytesPerItem > remaining.toLong()) {
            throw ArtifactFormatException("$label count cannot fit in the remaining payload")
        }
        return value
    }

    fun raw(length: Int, maximumLength: Int, label: String): ByteArray {
        if (length !in 0..maximumLength) {
            throw ArtifactFormatException("$label length is out of bounds: $length")
        }
        requireRemaining(length, label)
        val result = ByteArray(length)
        input.readFully(result)
        remaining -= length
        return result
    }

    fun remainingBytes(): Int = remaining

    fun finish(label: String) {
        if (remaining != 0) throw ArtifactFormatException("$label contains $remaining trailing bytes")
    }

    private fun requireRemaining(length: Int, label: String) {
        if (length < 0 || remaining < length) {
            throw ArtifactFormatException("$label is truncated")
        }
    }
}

internal object WireEnvelope {
    private const val HEADER_BYTES = 4 + 4 + 4 + 32

    fun encode(magic: String, wireVersion: Int, payload: ByteArray, maximumPayloadBytes: Int): ByteArray {
        require(magic.toByteArray(StandardCharsets.US_ASCII).size == 4) { "Envelope magic must contain four ASCII bytes" }
        require(payload.size <= maximumPayloadBytes) { "Envelope payload exceeds $maximumPayloadBytes bytes" }
        val writer = CanonicalWriter(HEADER_BYTES + maximumPayloadBytes)
        writer.raw(magic.toByteArray(StandardCharsets.US_ASCII))
        writer.int(wireVersion)
        writer.int(payload.size)
        writer.hash(Sha256.digest(payload))
        writer.raw(payload)
        return writer.toByteArray()
    }

    fun decode(
        encoded: ByteArray,
        expectedMagic: String,
        supportedWireVersion: Int,
        maximumPayloadBytes: Int,
    ): ByteArray {
        if (encoded.size > HEADER_BYTES + maximumPayloadBytes) {
            throw ArtifactFormatException("Envelope exceeds its maximum encoded size")
        }
        val reader = CanonicalReader(encoded, HEADER_BYTES + maximumPayloadBytes)
        val magic = reader.raw(4, 4, "envelope magic").toString(StandardCharsets.US_ASCII)
        if (magic != expectedMagic) throw ArtifactFormatException("Unexpected envelope magic: $magic")
        val version = reader.int("wire version")
        if (version != supportedWireVersion) {
            throw ArtifactFormatException("Unsupported $expectedMagic wire version: $version")
        }
        val payloadLength = reader.int("payload length")
        if (payloadLength !in 0..maximumPayloadBytes) {
            throw ArtifactFormatException("Payload length is out of bounds: $payloadLength")
        }
        val expectedHash = reader.hash("payload SHA-256")
        if (reader.remainingBytes() != payloadLength) {
            throw ArtifactFormatException("Envelope payload length does not match the encoded bytes")
        }
        val payload = reader.raw(payloadLength, maximumPayloadBytes, "payload")
        reader.finish("envelope")
        val actualHash = Sha256.digest(payload)
        if (!MessageDigest.isEqual(expectedHash.toByteArray(), actualHash.toByteArray())) {
            throw ArtifactFormatException("Envelope payload SHA-256 mismatch")
        }
        return payload
    }
}

internal fun CanonicalWriter.binding(value: ArtifactBinding) {
    string(value.projectId, 128)
    long(value.projectRevision)
    string(value.jobId, 128)
    long(value.attempt)
}

internal fun CanonicalReader.binding(): ArtifactBinding = ArtifactBinding(
    projectId = string(128, "project ID"),
    projectRevision = long("project revision"),
    jobId = string(128, "job ID"),
    attempt = long("job attempt"),
)


internal inline fun <T> decodeArtifactContract(label: String, block: () -> T): T = try {
    block()
} catch (failure: ArtifactFormatException) {
    throw failure
} catch (failure: IllegalArgumentException) {
    throw ArtifactFormatException("$label rejected: ${failure.message ?: failure.javaClass.simpleName}", failure)
}
