package com.trading.bot.config

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

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

    @Bean
    fun liquibaseDataSource(): DataSource =
        liquibaseDataSourceProperties().initializeDataSourceBuilder().build()

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource")
    fun liquibaseDataSourceProperties(): DataSourceProperties = DataSourceProperties()
}
