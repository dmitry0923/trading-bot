package com.trading.bot.config

import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Явный JDBC [DataSource] только для Liquibase-миграций.
 *
 * Spring Boot 3.2 отключает `DataSourceAutoConfiguration`, когда в контексте есть
 * R2DBC `ConnectionFactory` (`@ConditionalOnMissingBean(type = "io.r2dbc.spi.ConnectionFactory")`),
 * а Liquibase (`LiquibaseAutoConfiguration`) требует бин `DataSource`. Без этого бина
 * миграции схемы не выполняются. Все запросы приложения идут через R2DBC.
 */
@Configuration
class DatabaseConfig {
    /**
     * JDBC-пул нужен только Liquibase на старте. Держим его минимальным
     * (2 соединения, idle=0), чтобы на Managed PostgreSQL не занимать лишние
     * коннекты после миграций.
     */
    @Bean
    fun liquibaseDataSource(): HikariDataSource {
        val dataSource =
            liquibaseDataSourceProperties()
                .initializeDataSourceBuilder()
                .type(HikariDataSource::class.java)
                .build()
        return dataSource.apply {
            poolName = "liquibase-pool"
            maximumPoolSize = 2
            minimumIdle = 0
            idleTimeout = 30_000L
            maxLifetime = 1_800_000L
            connectionTimeout = 5_000L
        }
    }

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource")
    fun liquibaseDataSourceProperties(): DataSourceProperties = DataSourceProperties()
}
