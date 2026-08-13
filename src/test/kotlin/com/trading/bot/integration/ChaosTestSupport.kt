package com.trading.bot.integration

import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Общие хелперы chaos-тестов (roadmap 13.3.3).
 *
 * Контейнеры поднимаются на фиксированных host-портах ([setPortBindings]), чтобы
 * адрес зависимости не менялся. Авария и восстановление:
 *
 *  - PostgreSQL «выключается» через `docker pause`/`unpause` ([pauseContainer]/
 *    [unpauseContainer]): данные и схема не теряются, а контейнер не пересоздаётся
 *    (перезапуск через stop()/start() в Testcontainers 2.0.5 ненадёжен — повторно
 *    использованный LogMessageWaitStrategy не видит логи нового контейнера);
 *  - Redis, напротив, перезапускается (redis — stateless, потеря данных не влияет
 *    на проверку восстановления).
 */
fun chaosPostgres(hostPort: Int) =
    PostgreSQLContainer(
        DockerImageName
            .parse("timescale/timescaledb:2.17.2-pg15")
            .asCompatibleSubstituteFor("postgres"),
    ).withDatabaseName("trading_bot")
        .withUsername("test")
        .withPassword("test")
        .also { it.setPortBindings(listOf("$hostPort:5432")) }

fun chaosRedis(hostPort: Int) = GenericContainer("redis:7-alpine").also { it.setPortBindings(listOf("$hostPort:6379")) }

/**
 * «Выключает» контейнер через `docker pause`: процессы замораживаются, порт остаётся
 * занят, данные сохраняются. Соединения от приложения при этом не отвечают.
 */
fun pauseContainer(container: GenericContainer<*>) {
    container.dockerClient.pauseContainerCmd(container.containerId).exec()
}

/** «Включает» ранее замороженный [pauseContainer] контейнер. */
fun unpauseContainer(container: GenericContainer<*>) {
    container.dockerClient.unpauseContainerCmd(container.containerId).exec()
}

/**
 * Ожидает выполнения [condition] с повторами — для проверки восстановления
 * зависимости (пулы переподключаются не мгновенно).
 */
fun awaitUntil(
    description: String,
    timeoutMs: Long = 60_000,
    pollMs: Long = 500,
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (runCatching { condition() }.getOrDefault(false)) return
        Thread.sleep(pollMs)
    }
    throw AssertionError("Timed out waiting for: $description")
}
