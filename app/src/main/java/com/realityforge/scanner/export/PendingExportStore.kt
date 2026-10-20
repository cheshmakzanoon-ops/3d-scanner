package com.realityforge.scanner.export

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** One durable picker request per store. Restore is independent of selected project and never launches work. */
class PendingExportStore(private val file: File) {
    private val gate = locks.computeIfAbsent(file.canonicalPath) { Any() }
    fun save(request: ExportRequest) { synchronized(gate) {
        check(!file.exists()) { "PICKER_ALREADY_PENDING" }
        require(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(1); out.writeUTF(request.requestId)
                with(request.artifact) { out.writeUTF(projectId); out.writeLong(revision); out.writeUTF(artifactKey); out.writeUTF(sha256) }
                with(request.option) { out.writeUTF(id); out.writeUTF(format.name); out.writeUTF(representation.name); out.writeLong(bytes); out.writeBoolean(arbitraryScale); out.writeBoolean(photographic) }
                out.writeUTF(request.options.editIdentity.orEmpty()); out.writeUTF(request.options.lodIdentity.orEmpty()); out.writeBoolean(request.options.acknowledgedArbitraryScale)
            }
        }.toByteArray()
        val temporary = File.createTempFile("pending-export-", ".partial", file.parentFile)
        try {
            FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.write(MessageDigest.getInstance("SHA-256").digest(bytes)); stream.fd.sync() }
            check(temporary.renameTo(file)) { "PENDING_SAVE_FAILED" }
        } finally { temporary.delete() }
    } }
    fun restore(): ExportRequest? = synchronized(gate) {
        if (!file.exists()) return@synchronized null
        require(file.length() in 33..8192) { "INVALID_PENDING_REQUEST" }
        val all = file.inputStream().use { it.readNBytes(8193) }
        require(all.size <= 8192)
        val bytes = all.copyOfRange(0, all.size - 32)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(bytes), all.copyOfRange(all.size - 32, all.size)))
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 1)
            val id = input.readUTF()
            val identity = ArtifactIdentity(input.readUTF(), input.readLong(), input.readUTF(), input.readUTF())
            val option = ExportOption(input.readUTF(), ExportFormat.valueOf(input.readUTF()), AssetRepresentation.valueOf(input.readUTF()), input.readLong(), input.readBoolean(), input.readBoolean())
            val options = ExportOptions(input.readUTF().ifEmpty { null }, input.readUTF().ifEmpty { null }, input.readBoolean())
            require(input.read() == -1)
            ExportRequest(id, identity, option, options)
        }
    }
    fun clear(requestId: String) { synchronized(gate) {
        val request = restore() ?: return
        require(request.requestId == requestId) { "PICKER_ID_MISMATCH" }
        check(file.delete()) { "PENDING_DELETE_FAILED" }
    } }
    companion object { private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>() }
}
