FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon 2>/dev/null || true
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -g 1001 -S ledger && adduser -u 1001 -S ledger -G ledger

WORKDIR /app

COPY --from=builder /build/build/libs/ledger-api-*.jar app.jar

RUN chown -R ledger:ledger /app

USER ledger

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/healthz || exit 1

ENTRYPOINT ["java", \
  "-XX:+UseG1GC", \
  "-XX:MaxGCPauseMillis=100", \
  "-XX:+UseStringDeduplication", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
