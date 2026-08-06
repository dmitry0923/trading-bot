package com.trading.bot.config.security

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Счётчик неудачных логинов: после [MAX_FAILURES] ошибок за [WINDOW] ключ
 * (username|client-IP) блокируется до конца окна. In-memory — на единственной
 * VM этого достаточно; при горизонтальном масштабировании переносить в Redis.
 */
@Component
class LoginAttemptTracker {
    private val failures = ConcurrentHashMap<String, MutableList<Instant>>()

    fun check(key: String) {
        val recent = failures[key] ?: return
        val valid = recent.filter { it.isAfter(Instant.now().minus(WINDOW)) }
        if (valid.size >= MAX_FAILURES) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts. Try again later.")
        }
    }

    fun recordFailure(key: String) {
        failures.compute(key) { _, list ->
            val current = (list?.filter { it.isAfter(Instant.now().minus(WINDOW)) } ?: mutableListOf<Instant>()).toMutableList()
            current.add(Instant.now())
            current
        }
    }

    fun reset(key: String) {
        failures.remove(key)
    }

    private companion object {
        const val MAX_FAILURES = 10
        val WINDOW: Duration = Duration.ofMinutes(5)
    }
}
