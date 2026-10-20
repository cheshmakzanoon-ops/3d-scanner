package com.realityforge.scanner.studio.editing

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections
import kotlin.math.abs
import kotlin.math.hypot

data class EditPoint(val x: Double, val y: Double, val z: Double) {
    init { require(x.isFinite() && y.isFinite() && z.isFinite()) }
}

sealed interface EditOperation {
    data class Transform(val matrix: List<Double>) : EditOperation
    data class Crop(val min: EditPoint, val max: EditPoint) : EditOperation
    data class KeepComponents(val componentIds: Set<Int>) : EditOperation
    /** User supplied known distance, never inferred from arbitrary reconstruction units. */
    data class MetricReference(
        val start: EditPoint, val end: EditPoint, val knownMeters: Double,
        val uncertaintyMeters: Double, val label: String,
    ) : EditOperation
}

data class EditRecipe(
    val parentAssetId: String,
    val sourceSha256: String,
    val operations: List<EditOperation> = emptyList(),
    val schemaVersion: Int = 1,
)

/** Pure reversible controller. Save this blob through owner 005 with owner 009's parent identity. */
class EditController(initial: EditRecipe) {
    private val parent = initial.parentAssetId
    private val source = initial.sourceSha256
    private val history = mutableListOf<EditOperation>()
    private var cursor = 0

    init {
        require(initial.schemaVersion == 1) { "Unsupported edit schema" }
        require(parent.isNotBlank() && parent.toByteArray(Charsets.UTF_8).size <= 256)
        require(source.matches(Regex("[0-9a-f]{64}"))) { "Missing immutable source hash" }
        require(initial.operations.size <= MAX_OPERATIONS)
        initial.operations.forEach { history += validated(it) }
        require(history.sumOf(::encodedBudget) + 2048 <= MAX_BYTES) { "Edit history byte budget exceeded" }
        cursor = history.size
    }

    val recipe: EditRecipe @Synchronized get() = EditRecipe(parent, source, Collections.unmodifiableList(history.take(cursor)))
    val canUndo: Boolean @Synchronized get() = cursor > 0
    val canRedo: Boolean @Synchronized get() = cursor < history.size

    @Synchronized fun apply(operation: EditOperation) {
        require(cursor < MAX_OPERATIONS) { "Edit operation budget exceeded" }
        val accepted = validated(operation)
        require(history.take(cursor).sumOf(::encodedBudget) + encodedBudget(accepted) + 2048 <= MAX_BYTES) {
            "Edit history byte budget exceeded"
        }
        while (history.size > cursor) history.removeAt(history.lastIndex)
        history += accepted
        cursor++
    }
    @Synchronized fun undo(): Boolean = if (cursor == 0) false else { cursor--; true }
    @Synchronized fun redo(): Boolean = if (cursor == history.size) false else { cursor++; true }

    /** Active recipe only, excluding redo history; owner 009 hashes this as a descendant parameter. */
    @Synchronized fun materializationBytes(): ByteArray = EditController(recipe).save()

    /** Versioned, bounded, checksummed history preserves both undo and redo across reopen. */
    @Synchronized fun save(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeInt(1); out.writeUTF(parent); out.writeUTF(source)
            out.writeInt(history.size); out.writeInt(cursor)
            history.forEach { op ->
                when (op) {
                    is EditOperation.Transform -> { out.writeByte(1); op.matrix.forEach(out::writeDouble) }
                    is EditOperation.Crop -> { out.writeByte(2); out.point(op.min); out.point(op.max) }
                    is EditOperation.KeepComponents -> {
                        out.writeByte(3); out.writeInt(op.componentIds.size); op.componentIds.sorted().forEach(out::writeInt)
                    }
                    is EditOperation.MetricReference -> {
                        out.writeByte(4); out.point(op.start); out.point(op.end)
                        out.writeDouble(op.knownMeters); out.writeDouble(op.uncertaintyMeters); out.writeUTF(op.label)
                    }
                }
            }
        }
        val payload = bytes.toByteArray()
        require(payload.size + 32 <= MAX_BYTES) { "Edit history byte budget exceeded" }
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    companion object {
        private const val MAGIC = 0x52464531 // RFE1
        private const val MAX_OPERATIONS = 256
        private const val MAX_BYTES = 128 * 1024

        fun restore(bytes: ByteArray): EditController {
            require(bytes.size in 48..MAX_BYTES) { "Invalid edit history size" }
            val payload = bytes.copyOfRange(0, bytes.size - 32)
            require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload), bytes.copyOfRange(payload.size, bytes.size))) {
                "Edit history checksum mismatch"
            }
            return DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.readInt() == MAGIC && input.readInt() == 1) { "Unsupported edit history" }
                val parent = input.readUTF(); val source = input.readUTF()
                val count = input.readInt(); val cursor = input.readInt()
                require(count in 0..MAX_OPERATIONS && cursor in 0..count)
                val operations = List(count) {
                    when (input.readUnsignedByte()) {
                        1 -> EditOperation.Transform(List(16) { input.readDouble() })
                        2 -> EditOperation.Crop(input.point(), input.point())
                        3 -> {
                            val size = input.readInt(); require(size in 1..1024)
                            val ids = List(size) { input.readInt() }; require(ids.distinct().size == size)
                            EditOperation.KeepComponents(ids.toSet())
                        }
                        4 -> EditOperation.MetricReference(input.point(), input.point(), input.readDouble(), input.readDouble(), input.readUTF())
                        else -> error("Unknown edit operation")
                    }
                }
                require(input.available() == 0) { "Trailing edit history data" }
                EditController(EditRecipe(parent, source, operations)).also { it.cursor = cursor }
            }
        }

        private fun validated(op: EditOperation): EditOperation = when (op) {
            is EditOperation.Transform -> {
                val m = op.matrix.toList(); require(m.size == 16 && m.all(Double::isFinite))
                require(m[12] == 0.0 && m[13] == 0.0 && m[14] == 0.0 && m[15] == 1.0)
                for (i in 0..2) for (j in 0..2) {
                    val dot = (0..2).sumOf { k -> m[k * 4 + i] * m[k * 4 + j] }
                    require(abs(dot - if (i == j) 1.0 else 0.0) <= 1e-9) { "Orientation must be rigid; scale requires a known reference" }
                }
                EditOperation.Transform(Collections.unmodifiableList(m))
            }
            is EditOperation.Crop -> {
                require(op.min.x <= op.max.x && op.min.y <= op.max.y && op.min.z <= op.max.z); op.copy()
            }
            is EditOperation.KeepComponents -> {
                require(op.componentIds.size in 1..1024 && op.componentIds.all { it >= 0 })
                EditOperation.KeepComponents(Collections.unmodifiableSet(op.componentIds.toSet()))
            }
            is EditOperation.MetricReference -> {
                val distance = hypot(hypot(op.start.x - op.end.x, op.start.y - op.end.y), op.start.z - op.end.z)
                require(distance.isFinite() && distance > 0 && op.knownMeters.isFinite() && op.knownMeters > 0)
                require(op.uncertaintyMeters.isFinite() && op.uncertaintyMeters >= 0)
                require((op.knownMeters / distance).let { it.isFinite() && it > 0 })
                require((op.uncertaintyMeters / op.knownMeters).isFinite())
                require(op.label.isNotBlank() && op.label.toByteArray(Charsets.UTF_8).size <= 256)
                op.copy()
            }
        }
        private fun DataOutputStream.point(p: EditPoint) { writeDouble(p.x); writeDouble(p.y); writeDouble(p.z) }
        private fun DataInputStream.point() = EditPoint(readDouble(), readDouble(), readDouble())
        // Conservative upper bound includes modified UTF encoding and record tags.
        private fun encodedBudget(op: EditOperation): Int = when (op) {
            is EditOperation.Transform -> 129
            is EditOperation.Crop -> 49
            is EditOperation.KeepComponents -> 5 + op.componentIds.size * 4
            is EditOperation.MetricReference -> 67 + op.label.toByteArray(Charsets.UTF_8).size * 3
        }
    }
}
