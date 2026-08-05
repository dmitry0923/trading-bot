plugins {
    alias(libs.plugins.springBoot)
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSpring)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
}

group = "com.trading.bot"
version = "2.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(libs.springBootBom))
    runtimeOnly(platform(libs.springBootBom))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
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

    implementation(libs.resilience4jSpringBoot3)
    implementation(libs.resilience4jKotlin)
    implementation("org.springframework.boot:spring-boot-starter-aop")

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
    jvmArgs("-Xshare:off", "-XX:+EnableDynamicAgentLoading")
    testLogging {
        events("passed", "skipped", "failed")
        showExceptions = true
        showStackTraces = true
        showCauses = true
    }
}

// НАСТРОЙКА KTLINT
ktlint {
    version.set(libs.versions.ktlint.get())
    outputToConsole.set(true)
    verbose.set(true)

    // Игнорируем сгенерированные файлы
    filter {
        exclude { it.file.path.contains("/generated/") }
        exclude { it.file.path.contains("/build/") }
    }

    // Дополнительные настройки через .editorconfig
    // все настройки читаются из .editorconfig
}

// Отдельная задача для генерации baseline с правильными правилами
tasks.register("generateKtlintBaseline") {
    group = "verification"
    description = "Generate ktlint baseline with all enabled rules"
    dependsOn("ktlintBaseline")
}

// Проверка перед коммитом (можно добавить в pre-commit)
tasks.named("check") {
    dependsOn("ktlintCheck")
}

// Авто-исправление стиля
tasks.named("ktlintFormat") {
    // Форматирует код в соответствии с правилами
}

// Дополнительная задача для проверки неиспользуемого кода
tasks.register("checkUnused") {
    group = "verification"
    description = "Check for unused imports, parameters, and members"
    dependsOn("ktlintCheck")
}

// НАСТРОЙКА KOVER (покрытие тестами)
// Отчёт: build/reports/kover/ (html/xml/verify)
// Проверка порога: ./gradlew koverVerify (подключена к check)
koverReport {
    defaults {
        verify {
            onCheck = true
            rule("Минимальное покрытие строк") {
                // План повышения до 100% — см. docs/13-roadmap.md
                minBound(50)
            }
        }
    }
}
