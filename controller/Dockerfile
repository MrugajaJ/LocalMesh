FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S localmesh && adduser -S localmesh -G localmesh
USER localmesh

COPY target/localmesh-controller-*.jar app.jar

EXPOSE 8090 50051

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -q -O- http://localhost:8090/health || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", \
            "-jar", "app.jar"]
