package com.realityforge.scanner.data.operations

import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.data.ScanDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.coroutineContext

class PreviewReopeningServiceImpl(
    private val dao: ScanDao,
    private val paths: ProjectPathPolicy,
) : PreviewReopeningService {
    override suspend fun currentPreview(projectId: String): JSONObject? = withContext(Dispatchers.IO) {
        val before = dao.project(projectId) ?: return@withContext null
        if (before.resultRevision == null || before.resultRevision != before.revision) return@withContext null
        val key = requireNotNull(before.resultKey) { "Completed scan has no artifact identity." }
        check(paths.artifactRevision(key) == before.revision) { "Saved model revision does not match this project." }
        val file = paths.artifact(projectId, key, "json")
        val bytes = file.inputStream().use { input ->
            val size = file.length()
            require(size in 2..ProjectRepository.MAX_PREVIEW_BYTES) { "Saved preview exceeds the memory limit." }
            val data = ByteArray(size.toInt())
            var cursor = 0
            while (cursor < data.size) {
                coroutineContext.ensureActive()
                val count = input.read(data, cursor, minOf(BUFFER_BYTES, data.size - cursor))
                check(count > 0) { "Saved preview is truncated." }
                cursor += count
            }
            check(input.read() == -1) { "Saved preview changed during loading." }
            data
        }
        val text = bytes.toString(Charsets.UTF_8)
        validateJsonNesting(text)
        val reader = JSONTokener(text)
        val json = reader.nextValue() as? JSONObject ?: error("Saved preview is not an object.")
        require(reader.nextClean() == '\u0000') { "Unexpected data after preview." }
        ProjectRepository.validatePreviewIdentity(json, before.revision, key)
        val after = dao.project(projectId)
        if (
            after == null || after.revision != before.revision ||
            after.resultRevision != before.resultRevision || after.resultKey != key
        ) return@withContext null
        json
    }

    private fun validateJsonNesting(text: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '\"') quoted = false
            } else if (character == '\"') quoted = true
            else if (character == '{' || character == '[') {
                depth++
                require(depth <= MAX_JSON_DEPTH) { "Saved preview JSON is too deeply nested." }
            } else if (character == '}' || character == ']') {
                depth--
                require(depth >= 0) { "Saved preview JSON is malformed." }
            }
        }
        require(depth == 0 && !quoted) { "Saved preview JSON is incomplete." }
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private const val MAX_JSON_DEPTH = 32
    }
}
