package com.realityforge.scanner.data.operations

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class AtomicProjectFileWriterImpl(private val paths: ProjectPathPolicy) : AtomicProjectFileWriter {
    override fun write(file: File, text: String) {
        val owned = paths.requireOwnedFile(file)
        val parent = owned.file.parentFile ?: error("Project file has no parent directory.")
        require(parent.isDirectory || parent.mkdirs()) { "Cannot create project artifact directory." }
        paths.requireOwnedFile(owned.file)
        val temporary = File(parent, ".${owned.file.name}.${UUID.randomUUID()}.partial")
        paths.requireOwnedFile(temporary)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            moveReplacing(temporary, owned.file)
        } finally {
            temporary.delete()
        }
    }

    companion object {
        fun moveReplacing(source: File, destination: File) {
            try {
                Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
