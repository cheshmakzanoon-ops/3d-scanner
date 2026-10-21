package com.realityforge.scanner.agents.a009

import com.realityforge.scanner.engine.artifacts.SparseFrontierCheckpointStore
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.FileTime
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SparseFrontierCheckpointStoreTest {
    private fun project(block: (File, File) -> Unit) {
        val root = Files.createTempDirectory("frontier-store-").toFile().canonicalFile
        try { block(root, File(root, SparseFrontierCheckpointStore.DIRECTORY_NAME)) } finally { Files.walk(root.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
    private fun stage(index: Int) = index.toString(16).padStart(64, '0')
    private fun record(cache: File, index: Int) = File(cache, "${stage(index)}.frontier")
    private fun cancel(block: () -> Unit) { try { block();fail("Cancellation was swallowed") } catch (_: CancellationException) { } }
    private fun partials(cache: File) = cache.listFiles().orEmpty().filter { ".partial-" in it.name }

    @Test fun exactEnvelopeRoundTripAndTwoStageRetentionPreserveOriginals() = project { root, cache ->
        val store = SparseFrontierCheckpointStore(root, 1024)
        val a = ByteArray(90) { it.toByte() }; val b = ByteArray(120) { (it + 7).toByte() }
        assertNull(store.load(stage(1)) { false });assertFalse(cache.exists())
        assertTrue(store.publish(stage(1), a) { false })
        val original = File(cache, "original.jpg").apply { writeText("original") }
        val model = File(cache, "model.glb").apply { writeText("model") }
        assertTrue(store.publish(stage(2), b) { false })
        Files.setLastModifiedTime(record(cache, 1).toPath(), FileTime.fromMillis(1000))
        Files.setLastModifiedTime(record(cache, 2).toPath(), FileTime.fromMillis(2000))
        assertArrayEquals(a, store.load(stage(1)) { false });assertArrayEquals(b, store.load(stage(2)) { false })
        assertTrue(store.publish(stage(3), a) { false })
        assertNull(store.load(stage(1)) { false });assertArrayEquals(b, store.load(stage(2)) { false })
        assertArrayEquals(a, store.load(stage(3)) { false })
        assertEquals("original", original.readText());assertEquals("model", model.readText())
        assertEquals(2, cache.listFiles().orEmpty().count { it.name.endsWith(".frontier") })
        assertTrue(partials(cache).isEmpty())
    }

    @Test fun corruptWrongVersionWrongIdentityTruncatedAppendedAndMaliciousLengthsMiss() = project { root, cache ->
        val store = SparseFrontierCheckpointStore(root)
        val bytes = ByteArray(180) { it.toByte() }
        assertTrue(store.publish(stage(1), bytes) { false });val valid = record(cache, 1).readBytes()
        for (length in listOf(0, 7, 111, 112, valid.size - 1)) {
            record(cache, 1).writeBytes(valid.copyOf(length));assertNull(store.load(stage(1)) { false })
        }
        val changes = listOf<Pair<Int, ByteArray>>(
            0 to byteArrayOf('X'.code.toByte()), 8 to ByteBuffer.allocate(4).putInt(2).array(),
            12 to stage(2).toByteArray(), 76 to ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array(),
            76 to ByteBuffer.allocate(4).putInt(-1).array(), 80 to ByteArray(32),
            112 to byteArrayOf(7),
        )
        for ((offset, replacement) in changes) {
            val damaged = valid.clone();replacement.copyInto(damaged, offset);record(cache, 1).writeBytes(damaged)
            assertNull("Corruption at $offset was accepted", store.load(stage(1)) { false })
        }
        record(cache, 1).writeBytes(valid + 1.toByte());assertNull(store.load(stage(1)) { false })
        record(cache, 2).writeBytes(valid);assertNull(store.load(stage(2)) { false })
        record(cache, 1).writeBytes(valid);assertArrayEquals(bytes, store.load(stage(1)) { false })
        assertFalse(store.publish("../original", bytes) { false });assertNull(store.load("A".repeat(64)) { false })
    }

    @Test fun symlinkRecordDirectoryAndLockAreRejectedWithoutTouchingTargets() = project { root, cache ->
        val store = SparseFrontierCheckpointStore(root)
        assertTrue(store.publish(stage(1), byteArrayOf(1)) { false })
        val outside = File(root, "original.bin").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        record(cache, 1).delete();Files.createSymbolicLink(record(cache, 1).toPath(), outside.toPath())
        assertNull(store.load(stage(1)) { false });assertFalse(store.publish(stage(1), byteArrayOf(2)) { false })
        assertTrue(store.publish(stage(2), byteArrayOf(3)) { false })
        assertTrue(Files.isSymbolicLink(record(cache, 1).toPath()));assertArrayEquals(byteArrayOf(9,8,7), outside.readBytes())
        File(cache, ".frontier-store-v1.lock").delete()
        Files.createSymbolicLink(File(cache, ".frontier-store-v1.lock").toPath(), outside.toPath())
        assertFalse(store.publish(stage(3), byteArrayOf(4)) { false })
        assertArrayEquals(byteArrayOf(9,8,7), outside.readBytes())
        cache.deleteRecursively();Files.createSymbolicLink(cache.toPath(), root.toPath())
        assertNull(store.load(stage(1)) { false });assertFalse(store.publish(stage(1), byteArrayOf(2)) { false })
        Files.delete(cache.toPath())
    }

    @Test fun cancellationBeforeDuringWriteDuringReadAndAtAtomicBoundaryPropagates() = project { root, cache ->
        val store = SparseFrontierCheckpointStore(root)
        val old = byteArrayOf(1, 2, 3)
        assertTrue(store.publish(stage(1), old) { false })
        cancel { store.load(stage(1)) { true } };cancel { store.publish(stage(2), old) { true } }
        val payload = ByteArray(256 * 1024) { (it % 239).toByte() }
        cancel { store.publish(stage(1), payload) { partials(cache).any { it.length() > 112 } } }
        assertArrayEquals(old, store.load(stage(1)) { false });assertTrue(partials(cache).isEmpty())
        assertTrue(store.publish(stage(2), payload) { false })
        var calls = 0
        cancel { store.load(stage(2)) { ++calls >= 5 } }
        cancel { store.publish(stage(1), payload) { partials(cache).any { it.length() == payload.size + 112L } } }
        assertArrayEquals(old, store.load(stage(1)) { false });assertTrue(partials(cache).isEmpty())
    }

    @Test fun actualAtomicRenameFailureCleansTemporaryAndPreservesEarlierAcceptedRecord() = project { root, cache ->
        val store = SparseFrontierCheckpointStore(root)
        val old = byteArrayOf(1, 2, 3);val next = ByteArray(1000) { 5 }
        assertTrue(store.publish(stage(1), old) { false })
        var blocked = false
        val stored = store.publish(stage(2), next) {
            if (!blocked && partials(cache).any { it.length() == next.size + 112L }) {
                blocked = true
                record(cache, 2).mkdir()
                File(record(cache, 2), "obstacle").writeText("nonempty target forces real atomic rename failure")
            }
            false
        }
        assertTrue(blocked);assertFalse(stored);assertTrue(partials(cache).isEmpty())
        assertArrayEquals(old, store.load(stage(1)) { false });assertNull(store.load(stage(2)) { false })
    }

    @Test fun byteAdmissionBoundsPayloadOrphansAndExclusiveWriterContention() = project { root, cache ->
        val store = SparseFrontierCheckpointStore(root, 300)
        assertTrue(store.publish(stage(1), ByteArray(100)) { false })
        assertTrue(store.publish(stage(2), ByteArray(100) { 2 }) { false })
        assertFalse(record(cache, 1).exists());assertTrue(record(cache, 2).exists())
        assertTrue(cache.listFiles().orEmpty().filter { it.name.endsWith(".frontier") }.sumOf { it.length() } <= 300)
        assertFalse(store.publish(stage(3), ByteArray(189)) { false })
        assertFalse(store.publish(stage(3), ByteArray(0)) { false })
        assertFalse(store.publish(stage(3), ByteArray(SparseFrontierCheckpointStore.MAX_PAYLOAD_BYTES + 1)) { false })
        val orphan = File(cache, "${stage(4)}.frontier.partial-${"a".repeat(32)}").apply { writeText("interrupted") }
        val unknown = File(cache, "unowned.partial-${"a".repeat(32)}").apply { writeText("preserve") }
        assertTrue(store.publish(stage(3), ByteArray(1)) { false });assertFalse(orphan.exists());assertTrue(unknown.exists())
        FileChannel.open(File(cache, ".frontier-store-v1.lock").toPath(), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            channel.lock().use {
                val started = System.nanoTime()
                assertFalse(store.publish(stage(4), byteArrayOf(1)) { false })
                assertTrue((System.nanoTime() - started) < 2_000_000_000L)
                cancel { store.publish(stage(4), byteArrayOf(1)) { true } }
            }
        }
        val oversized = record(cache, 8)
        RandomAccessFile(oversized, "rw").use { it.setLength(SparseFrontierCheckpointStore.MAX_PAYLOAD_BYTES + 113L) }
        assertNull(store.load(stage(8)) { false })
        assertTrue(store.publish(stage(8), byteArrayOf(8)) { false });assertEquals(113L, oversized.length())
    }
}
