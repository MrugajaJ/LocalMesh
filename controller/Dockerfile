FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build

# Copy proto definitions
COPY ../proto /proto

# Copy pom and source
COPY pom.xml .
COPY src ./src

# Build fat jar
RUN mvn package -DskipTests -q

# ── Runtime ──────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S localmesh && adduser -S localmesh -G localmesh
USER localmesh

COPY --from=build /build/target/localmesh-controller-*.jar app.jar

EXPOSE 8090 50051

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -q -O- http://localhost:8090/health || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", \
            "-jar", "app.jar"]
