package com.trading.bot.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.util.UUID

class UuidV7Test {
    @Test
    fun `uuid has version 7 and variant bits set`() {
        val uuid = UuidV7.uuid()
        assertEquals(7, uuid.version())
        assertEquals(2, uuid.variant()) // RFC 4122 variant (10xx)
    }

    @RepeatedTest(50)
    fun `uuids are unique`() {
        val a = UuidV7.uuid()
        val b = UuidV7.uuid()
        assertTrue(a != b)
    }

    @Test
    fun `uuidString is parseable`() {
        val parsed = UUID.fromString(UuidV7.uuidString())
        assertEquals(7, parsed.version())
    }

    @Test
    fun `uuids are monotonically increasing`() {
        val first = UuidV7.uuid()
        Thread.sleep(5)
        val second = UuidV7.uuid()
        assertTrue(second > first)
    }
}
