# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build

# Copy Maven wrapper and pom first (for layer caching)
COPY api/pom.xml .
RUN apk add --no-cache maven && mvn dependency:go-offline -q

# Copy source and build the fat JAR
COPY api/src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: Run ──────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S localmesh && adduser -S localmesh -G localmesh
USER localmesh

COPY --from=builder /build/target/localmesh-api.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 \
  CMD wget -q -O- http://localhost:8080/health || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", \
            "-jar", "app.jar"]
