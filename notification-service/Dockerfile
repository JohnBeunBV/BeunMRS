# ── Build stage ──────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# Cache dependency layer separately from source
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# wget is already available in temurin alpine — used by the healthcheck
# No nc needed at all.

RUN addgroup -S notify && adduser -S notify -G notify
USER notify

COPY --from=builder /build/target/notification-service-*.jar app.jar

EXPOSE 8080

# Healthcheck uses wget (available in Alpine temurin) — NOT nc
HEALTHCHECK --interval=15s --timeout=5s --retries=5 --start-period=30s \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]