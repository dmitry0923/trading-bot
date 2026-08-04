plugins {
    alias(libs.plugins.springBoot)
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSpring)
    alias(libs.plugins.ktlint)
}

group = "com.trading.bot"
version = "2.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

// BOM Spring Boot через нативный Gradle platform(), а не io.spring.dependency-management.
// Это ограничивает версии (в т.ч. Kotlin 1.9.21) только конфигурациями приложения,
// а изолированная конфигурация ktlint* получает собственный совместимый Kotlin (2.2.x).
dependencies {
    implementation(platform(libs.springBootBom))
    runtimeOnly(platform(libs.springBootBom))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    // R2DBC: реактивный доступ к PostgreSQL из всех репозиториев приложения
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    // spring-boot-starter-jdbc остаётся ТОЛЬКО для Liquibase-миграций (Liquibase не поддерживает R2DBC)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.liquibase:liquibase-core")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")

    implementation("io.micrometer:micrometer-core")
    implementation("io.micrometer:micrometer-registry-prometheus")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation(libs.kotlinLogging)

    // Resilience4j: Circuit Breaker, Rate Limiter, Retry (программное использование с корутинами)
    implementation(libs.resilience4jSpringBoot3)
    implementation(libs.resilience4jKotlin)
    implementation("org.springframework.boot:spring-boot-starter-aop")

    // Jackson YAML — для PromptRegistry (чтение prompts/*.yml)
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.postgresql:r2dbc-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation(libs.testcontainersJunitJupiter)
    testImplementation(libs.testcontainersPostgresql)
    testImplementation(libs.testcontainersCore)

    // JUnit Platform Launcher для запуска тестов
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs += "-Xjsr305=strict"
        jvmTarget = "21"
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showExceptions = true
        showStackTraces = true
        showCauses = true
    }
}

ktlint {
    version.set(libs.versions.ktlint.get())
    outputToConsole.set(true)
    verbose.set(true)
    filter {
        exclude { it.file.path.contains("/generated/") }
    }
}

tasks.named("check") {
    dependsOn("ktlintCheck")
}
