# Build stage
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY gradle gradle
COPY gradlew .
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY src src
COPY docker/certs /tmp/cbr-certs
RUN ./gradlew bootJar --no-daemon
# www.cbr.ru отдаёт неполную TLS-цепочку: JDK не строит её до HARICA TLS RSA
# Root CA 2021. Добавляем оба публичных сертификата в копию cacerts, которую
# runtime использует через -Djavax.net.ssl.trustStore.
RUN cp "$JAVA_HOME/lib/security/cacerts" /tmp/cacerts \
    && keytool -importcert -noprompt -alias harica-root-2021 \
        -file /tmp/cbr-certs/HARICA-TLS-RSA-Root-CA-2021.pem \
        -keystore /tmp/cacerts -storepass changeit \
    && keytool -importcert -noprompt -alias harica-dv-tls-rsa \
        -file /tmp/cbr-certs/HARICA-DV-TLS-RSA.pem \
        -keystore /tmp/cacerts -storepass changeit

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
ENV TZ=Europe/Moscow
WORKDIR /app
# Имя конкретное: `*.jar` копирует и plain-jar, и bootJar → сборка образа падала.
COPY --from=builder /app/build/libs/trading-bot-*.jar app.jar
COPY --from=builder /tmp/cacerts /app/cacerts
EXPOSE 8080
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-XX:+ZGenerational", \
    "-XX:MaxRAMPercentage=50.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Djavax.net.ssl.trustStore=/app/cacerts", \
    "-Djavax.net.ssl.trustStorePassword=changeit", \
    "-jar", "app.jar"]
