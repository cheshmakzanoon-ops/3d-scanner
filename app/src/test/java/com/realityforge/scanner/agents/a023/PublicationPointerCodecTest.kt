package com.realityforge.scanner.agents.a023

import com.realityforge.scanner.data.publication.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class PublicationPointerCodecTest {
    @Test fun persistenceRoundtripRetainsEveryIdentityAndDurabilityField() {
        val pointer = PublicationPointer(PublicationPurpose.RESULT_SET, "project-1", 8, "operation-2", 3,
            "a".repeat(64), "b".repeat(64), "sets/project-1/${"a".repeat(64)}", 1234,
            PublicationDurability(true, true, true, false, true))
        assertEquals(pointer, PublicationPointerCodec.decode(PublicationPointerCodec.encode(pointer)))
    }

    @Test fun corruptTrailingOrUnboundedRecordsCannotBecomePointers() {
        val pointer = PublicationPointer(PublicationPurpose.RESULT_SET, "project-1", 8, "operation-2", 3,
            "a".repeat(64), "b".repeat(64), "sets/project-1/${"a".repeat(64)}", 1234,
            PublicationDurability(true, true, true, false, true))
        val encoded = PublicationPointerCodec.encode(pointer)
        val bytes = Base64.getDecoder().decode(encoded)
        assertThrows(IllegalArgumentException::class.java) {
            PublicationPointerCodec.decode(Base64.getEncoder().encodeToString(bytes + byteArrayOf(1)))
        }
        assertThrows(IllegalArgumentException::class.java) { PublicationPointerCodec.decode("a".repeat(4097)) }
        assertThrows(IllegalArgumentException::class.java) { PublicationPointerCodec.decode("not valid base64!") }
    }
}
