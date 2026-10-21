package com.realityforge.scanner.data.publication

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object PublicationCodec {
    private const val MANIFEST_MAGIC = 0x5246504d // RFPM
    private const val JOURNAL_MAGIC = 0x5246504a // RFPJ
    private const val ENVELOPE_VERSION = 1
    private const val MAX_MANIFEST_FILE_BYTES = 512 * 1024
    private const val MAX_JOURNAL_FILE_BYTES = 128 * 1024

    fun createManifest(
        identity: PublicationIdentity,
        createdAtEpochMillis: Long,
        entries: List<ArtifactManifestEntry>,
    ): ArtifactSetManifest {
        val payload = encodeManifestPayload(identity, createdAtEpochMillis, entries)
        return ArtifactSetManifest(
            schemaVersion = MANIFEST_SCHEMA_VERSION,
            identity = identity,
            createdAtEpochMillis = createdAtEpochMillis,
            entries = entries,
            setId = sha256(payload),
        )
    }

    fun encodeManifest(manifest: ArtifactSetManifest): ByteArray {
        val payload = encodeManifestPayload(manifest.identity, manifest.createdAtEpochMillis, manifest.entries)
        check(sha256(payload) == manifest.setId) { "Manifest setId does not match its canonical payload." }
        return envelope(MANIFEST_MAGIC, payload)
    }

    fun decodeManifest(bytes: ByteArray): ArtifactSetManifest {
        val payload = unwrap(MANIFEST_MAGIC, bytes, MAX_MANIFEST_FILE_BYTES)
        val input = DataInputStream(ByteArrayInputStream(payload))
        val schema = input.readInt()
        require(schema == MANIFEST_SCHEMA_VERSION) { "Unsupported manifest schema $schema." }
        val identity = readIdentity(input)
        val createdAt = input.readLong()
        val count = input.readInt()
        require(count in 1..MAX_ARTIFACT_COUNT) { "Manifest artifact count is invalid." }
        val entries = ArrayList<ArtifactManifestEntry>(count)
        repeat(count) {
            val declaration = ArtifactDeclaration(
                logicalName = input.readBoundedString(128),
                relativePath = input.readBoundedString(512),
                representation = input.readBoundedString(128),
                required = input.readBoolean(),
                maxBytes = input.readLong(),
            )
            val presence = input.readEnum<ArtifactPresence>()
            val byteCount = input.readLong()
            val digest = input.readNullableString(64)
            entries += ArtifactManifestEntry(declaration, presence, byteCount, digest)
        }
        require(input.available() == 0) { "Unexpected trailing manifest payload." }
        return ArtifactSetManifest(schema, identity, createdAt, entries, sha256(payload))
    }

    fun encodeJournal(journal: PublicationJournal): ByteArray {
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(journal.schemaVersion)
                writeIdentity(output, journal.identity)
                output.writeInt(journal.state.ordinal)
                output.writeLong(journal.expectedMetadataVersion)
                output.writeNullableString(journal.expectedCurrentResultSetId)
                output.writeNullableString(journal.setId)
                output.writeBoundedString(journal.stageRelativeDirectory)
                output.writeNullableString(journal.finalRelativeDirectory)
                writeDurability(output, journal.durability)
                output.writeLong(journal.sequence)
                output.writeLong(journal.updatedAtEpochMillis)
                output.writeNullableString(journal.detail)
            }
            bytes.toByteArray()
        }
        return envelope(JOURNAL_MAGIC, payload)
    }

    fun decodeJournal(bytes: ByteArray): PublicationJournal {
        val payload = unwrap(JOURNAL_MAGIC, bytes, MAX_JOURNAL_FILE_BYTES)
        val input = DataInputStream(ByteArrayInputStream(payload))
        val schema = input.readInt()
        require(schema == JOURNAL_SCHEMA_VERSION) { "Unsupported journal schema $schema." }
        val identity = readIdentity(input)
        val state = input.readEnum<PublicationJournalState>()
        val expectedMetadataVersion = input.readLong()
        val expectedCurrent = input.readNullableString(64)
        val setId = input.readNullableString(64)
        val stageDirectory = input.readBoundedString(512)
        val finalDirectory = input.readNullableString(512)
        val durability = readDurability(input)
        val sequence = input.readLong()
        val updatedAt = input.readLong()
        val detail = input.readNullableString(512)
        require(input.available() == 0) { "Unexpected trailing journal payload." }
        return PublicationJournal(
            schemaVersion = schema,
            identity = identity,
            state = state,
            expectedMetadataVersion = expectedMetadataVersion,
            expectedCurrentResultSetId = expectedCurrent,
            setId = setId,
            stageRelativeDirectory = stageDirectory,
            finalRelativeDirectory = finalDirectory,
            durability = durability,
            sequence = sequence,
            updatedAtEpochMillis = updatedAt,
            detail = detail,
        )
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun encodeManifestPayload(
        identity: PublicationIdentity,
        createdAtEpochMillis: Long,
        entries: List<ArtifactManifestEntry>,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(MANIFEST_SCHEMA_VERSION)
            writeIdentity(output, identity)
            output.writeLong(createdAtEpochMillis)
            output.writeInt(entries.size)
            for (entry in entries) {
                val declaration = entry.declaration
                output.writeBoundedString(declaration.logicalName)
                output.writeBoundedString(declaration.relativePath)
                output.writeBoundedString(declaration.representation)
                output.writeBoolean(declaration.required)
                output.writeLong(declaration.maxBytes)
                output.writeInt(entry.presence.ordinal)
                output.writeLong(entry.bytes)
                output.writeNullableString(entry.sha256)
            }
        }
        bytes.toByteArray()
    }

    private fun envelope(magic: Int, payload: ByteArray): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(magic)
            output.writeInt(ENVELOPE_VERSION)
            output.writeInt(payload.size)
            output.write(payload)
            output.write(MessageDigest.getInstance("SHA-256").digest(payload))
        }
        bytes.toByteArray()
    }

    private fun unwrap(magic: Int, bytes: ByteArray, maximumBytes: Int): ByteArray {
        require(bytes.size in 12 + 32..maximumBytes) { "Publication metadata file size is invalid." }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == magic) { "Publication metadata magic is invalid." }
        require(input.readInt() == ENVELOPE_VERSION) { "Publication metadata envelope version is unsupported." }
        val payloadSize = input.readInt()
        require(payloadSize in 0..maximumBytes - 44 && payloadSize == input.available() - 32) {
            "Publication metadata payload length is invalid."
        }
        val payload = ByteArray(payloadSize)
        input.readFully(payload)
        val storedDigest = ByteArray(32)
        input.readFully(storedDigest)
        require(input.available() == 0) { "Unexpected trailing publication metadata." }
        val actualDigest = MessageDigest.getInstance("SHA-256").digest(payload)
        require(MessageDigest.isEqual(storedDigest, actualDigest)) { "Publication metadata checksum mismatch." }
        return payload
    }

    private fun writeIdentity(output: DataOutputStream, identity: PublicationIdentity) {
        output.writeInt(identity.purpose.ordinal)
        output.writeBoundedString(identity.projectId)
        output.writeLong(identity.projectRevision)
        output.writeBoundedString(identity.operationId)
        output.writeLong(identity.attempt)
        output.writeBoundedString(identity.inputIdentitySha256)
        output.writeBoundedString(identity.settingsIdentitySha256)
        output.writeBoundedString(identity.engineVersion)
    }

    private fun readIdentity(input: DataInputStream): PublicationIdentity = PublicationIdentity(
        purpose = input.readEnum(),
        projectId = input.readBoundedString(128),
        projectRevision = input.readLong(),
        operationId = input.readBoundedString(128),
        attempt = input.readLong(),
        inputIdentitySha256 = input.readBoundedString(64),
        settingsIdentitySha256 = input.readBoundedString(64),
        engineVersion = input.readBoundedString(128),
    )

    private fun writeDurability(output: DataOutputStream, durability: PublicationDurability) {
        output.writeBoolean(durability.artifactContentsSynced)
        output.writeBoolean(durability.manifestSynced)
        output.writeBoolean(durability.atomicDirectoryPromotion)
        output.writeBoolean(durability.stagingDirectorySynced)
        output.writeBoolean(durability.destinationDirectorySynced)
    }

    private fun readDurability(input: DataInputStream) = PublicationDurability(
        artifactContentsSynced = input.readBoolean(),
        manifestSynced = input.readBoolean(),
        atomicDirectoryPromotion = input.readBoolean(),
        stagingDirectorySynced = input.readBoolean(),
        destinationDirectorySynced = input.readBoolean(),
    )

    private fun DataOutputStream.writeBoundedString(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeBoundedString(value)
    }

    private fun DataInputStream.readBoundedString(maximumBytes: Int): String {
        val length = readInt()
        require(length in 0..maximumBytes) { "Encoded string length is invalid." }
        val encoded = ByteArray(length)
        readFully(encoded)
        val value = encoded.toString(StandardCharsets.UTF_8)
        require(value.toByteArray(StandardCharsets.UTF_8).contentEquals(encoded)) { "Encoded string is not canonical UTF-8." }
        return value
    }

    private fun DataInputStream.readNullableString(maximumBytes: Int): String? =
        if (readBoolean()) readBoundedString(maximumBytes) else null

    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T {
        val values = enumValues<T>()
        val ordinal = readInt()
        require(ordinal in values.indices) { "Enum ordinal is invalid." }
        return values[ordinal]
    }
}
