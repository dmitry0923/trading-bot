package com.trading.bot.config.security

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Счётчик неудачных логинов: после [MAX_FAILURES] ошибок за [WINDOW] ключ
 * (username|client-IP) блокируется до конца окна. In-memory — на единственной
 * VM этого достаточно; при горизонтальном масштабировании переносить в Redis.
 *
 * Ключ обязательно содержит ОБА измерения ([keyFor]): только `username` позволял
 * неаутентифицированному атакующему отправить 10 неудачных логинов за оператора
 * и заблокировать его (429), включая аварийные пути (force-close/emergency-stop).
 * IP берётся из `HttpServletRequest.remoteAddr` (не из клиентских заголовков) —
 * иначе троттлинг обходился бы подделкой `X-Forwarded-For`. Если перед приложением
 * появится обратный прокси, включить `server.forward-headers-strategy` — тогда
 * `remoteAddr` уже содержит реальный IP клиента.
 *
 * Ёмкость ограничена [MAX_KEYS]: на пределе протухшие записи вычищаются (не чаще
 * раза в [SWEEP_INTERVAL], чтобы флуд не превращался в O(n²)), а из оставшихся
 * удаляются самые ранние. Без этого каждый новый username оставлял ключ навсегда
 * (неограниченный рост памяти). Плата за жёсткий кап: при одновременном флуде
 * больше [MAX_KEYS] уникальными username счётчик может вытеснить сам себя — для
 * single-VM это осознанный компромисс.
 */
@Component
class LoginAttemptTracker(
    private val clock: Clock = Clock.systemUTC(),
) {
    private val failures = ConcurrentHashMap<String, MutableList<Instant>>()

    @Volatile
    private var nextSweepAt: Instant = Instant.EPOCH

    /** Ключ блокировки: имя пользователя + IP клиента (оба измерения обязательны). */
    fun keyFor(
        username: String,
        clientIp: String?,
    ): String = "$username|${clientIp?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_IP}"

    fun check(key: String) {
        val recent = failures[key] ?: return
        val cutoff = Instant.now(clock).minus(WINDOW)
        if (recent.count { it.isAfter(cutoff) } >= MAX_FAILURES) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts. Try again later.")
        }
    }

    fun recordFailure(key: String) {
        val now = Instant.now(clock)
        val cutoff = now.minus(WINDOW)
        ensureCapacity(now, cutoff, key)
        failures.compute(key) { _, list ->
            val current = mutableListOf<Instant>()
            list?.filterTo(current) { it.isAfter(cutoff) }
            current.add(now)
            current
        }
    }

    fun reset(key: String) {
        failures.remove(key)
    }

    /** Число отслеживаемых ключей (для проверки ограниченности памяти). */
    internal fun trackedKeys(): Int = failures.size

    /**
     * Держит [failures] в пределах [MAX_KEYS]: на пределе сначала выбрасывает
     * записи, все неудачи которых уже вне окна, затем — самые ранние из оставшихся.
     */
    private fun ensureCapacity(
        now: Instant,
        cutoff: Instant,
        incomingKey: String,
    ) {
        if (failures.size < MAX_KEYS || failures.containsKey(incomingKey)) return
        if (!now.isBefore(nextSweepAt)) {
            nextSweepAt = now.plus(SWEEP_INTERVAL)
            failures.entries.removeAll { (_, list) -> list.none { it.isAfter(cutoff) } }
        }
        val iterator = failures.keys.iterator()
        while (failures.size >= MAX_KEYS && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    private companion object {
        const val MAX_FAILURES = 10
        const val MAX_KEYS = 10_000
        const val UNKNOWN_IP = "unknown"
        val WINDOW: Duration = Duration.ofMinutes(5)
        val SWEEP_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
