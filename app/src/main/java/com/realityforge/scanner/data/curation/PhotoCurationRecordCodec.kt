package com.realityforge.scanner.data.curation

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * Versioned durable per-photo curation. The record binds its ID, original hash and ordered
 * position; Room's included/order columns are checked projections, not another source of truth.
 * No paths, original bytes or transient undo capabilities are serialized here.
 */
object PhotoCurationRecordCodec {
    private const val PREFIX = "rf-curation-v1:"
    private const val MAX_BYTES = 4_096
    const val MAX_RECORD_CHARS = 5_500

    fun encode(photo: CuratedPhoto): String {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(PHOTO_CURATION_SCHEMA_VERSION)
            out.field(photo.id)
            out.field(photo.sha256)
            out.writeInt(photo.position)
            out.field(photo.state.name)
            out.reason(photo.exclusion)
            out.writeBoolean(photo.pendingRemoval != null)
            photo.pendingRemoval?.let { removal ->
                out.field(removal.requestId)
                out.writeLong(removal.requestedAtEpochMillis)
                out.field(removal.reason.code.name)
                out.nullableField(removal.reason.note)
                out.field(removal.previousState.name)
                out.reason(removal.previousExclusion)
            }
        }
        val bytes = buffer.toByteArray()
        require(bytes.size <= MAX_BYTES)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decode(record: String): CuratedPhoto {
        require(record.length <= MAX_RECORD_CHARS && record.startsWith(PREFIX)) {
            "Unsupported or oversized photo curation record."
        }
        val bytes = Base64.getUrlDecoder().decode(record.substring(PREFIX.length))
        require(bytes.size in 1..MAX_BYTES)
        val photo = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == PHOTO_CURATION_SCHEMA_VERSION)
            val id = input.field()
            val hash = input.field()
            val position = input.readInt()
            val state = enumValueOf<CuratedPhotoState>(input.field())
            val reason = input.reason()
            val pending = if (!input.flag()) null else PendingPhotoRemoval(
                input.field(), input.readLong(),
                PhotoRemovalReason(enumValueOf(input.field()), input.nullableField()),
                enumValueOf(input.field()), input.reason(),
            )
            require(input.available() == 0) { "Trailing photo curation bytes." }
            CuratedPhoto(id, hash, position, state, reason, pending)
        }
        require(encode(photo) == record) { "Photo curation record is not canonical." }
        return photo
    }

    private fun DataOutputStream.field(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1_024)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.field(): String {
        val size = readInt()
        require(size in 0..1_024 && size <= available()) { "Invalid curation field size." }
        val bytes = ByteArray(size)
        readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun DataOutputStream.nullableField(value: String?) {
        writeBoolean(value != null)
        if (value != null) field(value)
    }

    private fun DataInputStream.flag() = when (val value = readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> throw IllegalArgumentException("Invalid curation flag: $value")
    }

    private fun DataInputStream.nullableField(): String? = if (flag()) field() else null
    private fun DataOutputStream.reason(value: PhotoCurationReason?) {
        writeBoolean(value != null)
        if (value != null) { field(value.code.name); nullableField(value.note) }
    }
    private fun DataInputStream.reason(): PhotoCurationReason? =
        if (flag()) PhotoCurationReason(enumValueOf(field()), nullableField()) else null
}
