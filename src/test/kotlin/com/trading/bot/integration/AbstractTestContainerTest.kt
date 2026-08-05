package com.trading.bot.integration

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest
@Testcontainers
abstract class AbstractTestContainerTest {
    companion object {
        @Container
        val postgres =
            PostgreSQLContainer("postgres:15-alpine")
                .withDatabaseName("trading_bot")
                .withUsername("test")
                .withPassword("test")

        @Container
        val redis =
            GenericContainer("redis:7-alpine")
                .withExposedPorts(6379)

        @DynamicPropertySource
        @JvmStatic
        @Suppress("unused") // Вызывается рефлексивно Spring TestContext через @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)

            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/${postgres.databaseName}" }
            registry.add("spring.r2dbc.username", postgres::getUsername)
            registry.add("spring.r2dbc.password", postgres::getPassword)

            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379).toString() }
        }
    }
}
