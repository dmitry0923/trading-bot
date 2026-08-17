plugins {
    alias(libs.plugins.springBoot)
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSpring)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
}

group = "com.trading.bot"
version = "2.0.0"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    implementation(platform(libs.springBootBom))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc") // Liquibase requires JDBC at startup only
    implementation("org.springframework.boot:spring-boot-starter-liquibase")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-aspectj")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework.boot:spring-boot-starter-amqp")

    implementation("io.micrometer:micrometer-core")
    implementation("io.micrometer:micrometer-registry-prometheus")

    implementation(libs.jacksonKotlin)
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j")
    implementation(libs.kotlinLogging)
    implementation(libs.logstashLogbackEncoder)
    implementation(libs.minio)

    implementation(libs.resilience4jSpringBoot4)
    implementation(libs.resilience4jKotlin)

    implementation(libs.catboostPrediction)

    implementation(libs.jjwtApi)
    runtimeOnly(libs.jjwtImpl)
    runtimeOnly(libs.jjwtJackson)

    implementation(libs.jacksonYaml)

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.postgresql:r2dbc-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation(libs.mockitoKotlin)
    testImplementation(libs.testcontainersJunitJupiter)
    testImplementation(libs.testcontainersPostgresql)
    testImplementation(libs.testcontainersRabbitmq)
    testImplementation(libs.testcontainers)
    testImplementation(libs.archunitJunit5)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

configurations.all {
    resolutionStrategy {
        force("org.bouncycastle:bcprov-jdk18on:1.81.1")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("-Xshare:off", "-XX:+EnableDynamicAgentLoading")
    // Проброс system property в JVM тестов: полномасштабный нагрузочный прогон
    // (roadmap 13.3.4) запускается `./gradlew.bat test -Dload.full=true`.
    systemProperty("load.full", System.getProperty("load.full", ""))
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

    // ВНИМАНИЕ: НЕ переносить правила ktlint в additionalEditorconfig!
    // Эмпирически проверено: additionalEditorconfig применяется как EditorConfigOverride
    // и меняет эффективную конфигурацию (дает сотни ложных нарушений argument-list-wrapping).
    // Правила ktlint остаются дефолтными (ktlint_official, все standard-правила включены).
}

// Отдельная задача для генерации baseline с правильными правилами
tasks.register("generateKtlintBaseline") {
    group = "verification"
    description = "Generate ktlint baseline with all enabled rules"
    dependsOn("ktlintBaseline")
}

// Проверка перед коммитом
tasks.named("check") {
    dependsOn("ktlintCheck", "koverVerify")
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
kover {
    reports {
        verify {
            rule {
                minBound(70)
            }
        }
    }
}
