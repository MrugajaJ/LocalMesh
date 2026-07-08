FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S localmesh && adduser -S localmesh -G localmesh
USER localmesh

COPY target/localmesh-sidecar.jar app.jar

EXPOSE 8081

HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=3 \
  CMD wget -q -O- http://localhost:8081/health || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", \
            "-jar", "app.jar"]
