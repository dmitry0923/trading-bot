package com.trading.bot.config.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Unit-тесты [LoginAttemptTracker]: ключ блокировки — пара username|IP, карта
 * неудач ограничена по размеру и очищается по окну.
 *
 * Регрессии, ради которых тесты написаны:
 *  - ключ был только `username` → 10 неудачных логинов за `admin` с любого IP
 *    блокировали оператора (429), включая аварийные пути;
 *  - `failures` не чистилась → каждый новый username оставлял ключ навсегда.
 */
class LoginAttemptTrackerTest {
    @Test
    fun `failures from one ip do not block the same username from another ip`() {
        val tracker = LoginAttemptTracker(TestClock(Instant.parse("2026-10-26T09:00:00Z")))
        val attacker = tracker.keyFor("admin", "203.0.113.7")
        repeat(10) { tracker.recordFailure(attacker) }

        assertThrows(ResponseStatusException::class.java) { tracker.check(attacker) }
        // Оператор с другого IP: своя история пуста — блокировки нет.
        tracker.check(tracker.keyFor("admin", "10.0.0.5"))
        tracker.recordFailure(tracker.keyFor("admin", "10.0.0.5"))
        assertEquals(2, tracker.trackedKeys(), "ключ включает и username, и IP")
    }

    @Test
    fun `tracked keys stay bounded after many unique usernames`() {
        val tracker = LoginAttemptTracker(TestClock(Instant.parse("2026-10-26T09:00:00Z")))
        repeat(3 * MAX_TRACKED) { index ->
            tracker.recordFailure(tracker.keyFor("user-$index", "10.0.0.1"))
        }

        val tracked = tracker.trackedKeys()
        assertTrue(tracked <= MAX_TRACKED, "карта неудач выросла до $tracked ключей (кап $MAX_TRACKED)")
        assertTrue(tracked > 0, "счётчик не должен вырождаться в пустой")
    }

    @Test
    fun `stale entries are evicted at the cap instead of living forever`() {
        val clock = TestClock(Instant.parse("2026-10-26T09:00:00Z"))
        val tracker = LoginAttemptTracker(clock)
        repeat(MAX_TRACKED) { index ->
            tracker.recordFailure(tracker.keyFor("old-$index", "10.0.0.1"))
        }
        assertEquals(MAX_TRACKED, tracker.trackedKeys())
        clock.advance(Duration.ofMinutes(6))

        // Новый неудачный логин за другим ключом вычищает протухшие записи,
        // а не вытесняет случайную свежую (TTL, а не только hard cap).
        tracker.recordFailure(tracker.keyFor("fresh", "10.0.0.1"))

        assertEquals(1, tracker.trackedKeys())
    }

    @Test
    fun `operator recovers after the failure window expires`() {
        val clock = TestClock(Instant.parse("2026-10-26T09:00:00Z"))
        val tracker = LoginAttemptTracker(clock)
        val key = tracker.keyFor("operator", "10.0.0.1")
        repeat(10) { tracker.recordFailure(key) }
        assertThrows(ResponseStatusException::class.java) { tracker.check(key) }

        clock.advance(Duration.ofMinutes(5) + Duration.ofSeconds(1))

        tracker.check(key) // окно истекло — блокировка снята
        tracker.recordFailure(key)
        tracker.check(key) // 1 неудача в новом окне — ещё не блокировка
    }

    @Test
    fun `blank ip is normalised to a single placeholder`() {
        val tracker = LoginAttemptTracker(TestClock(Instant.parse("2026-10-26T09:00:00Z")))

        assertEquals("admin|unknown", tracker.keyFor("admin", null))
        assertEquals("admin|unknown", tracker.keyFor("admin", "  "))

        val key = tracker.keyFor("admin", "")
        repeat(10) { tracker.recordFailure(key) }
        assertThrows(ResponseStatusException::class.java) { tracker.check(key) }
        tracker.reset(key)
        tracker.check(key)
    }

    private class TestClock(
        private var current: Instant,
    ) : Clock() {
        fun advance(duration: Duration) {
            current = current.plus(duration)
        }

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current
    }

    private companion object {
        /** Дублирует `LoginAttemptTracker.MAX_KEYS` (private) — контракт «не растёт бесконечно». */
        const val MAX_TRACKED = 10_000
    }
}
