FROM busybox:1.37.0-musl AS probe
FROM eclipse-temurin:21.0.12.1_1-jdk-noble AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY inventory-api/pom.xml inventory-api/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY ticketing-api/pom.xml ticketing-api/pom.xml
COPY load-generator/pom.xml load-generator/pom.xml
COPY payment-provider-stub/pom.xml payment-provider-stub/pom.xml
COPY dev-token-issuer/pom.xml dev-token-issuer/pom.xml
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -q -pl ticketing-api -am -DskipTests -DexcludeReactor=true org.apache.maven.plugins:maven-dependency-plugin:3.11.0:go-offline
COPY inventory-api inventory-api
COPY inventory-service inventory-service
COPY notification-service notification-service
COPY ticketing-api ticketing-api
COPY load-generator load-generator
COPY payment-provider-stub payment-provider-stub
COPY dev-token-issuer dev-token-issuer
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -q -pl ticketing-api -am -DskipTests install && \
    ./mvnw -q -pl ticketing-api org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dependency

FROM eclipse-temurin:21.0.12.1_1-jre-noble
ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.source="https://github.com/codexary/stubline-backend" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"
RUN useradd --system --uid 10001 --create-home app
WORKDIR /app
COPY --from=probe /bin/busybox /bin/busybox
ADD --chmod=0644 --checksum=sha256:e43a270df53c6f423e0ab157be9c27ccc01d5dc3232e162fe54777573db2f149 https://repo.maven.apache.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/2.33.1/opentelemetry-javaagent-2.33.1.jar /app/otel.jar
COPY --from=build /src/ticketing-api/target/ticketing-api.jar app.jar
COPY --from=build /src/ticketing-api/target/dependency/ lib/
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-javaagent:/app/otel.jar", "-Dotel.exporter.otlp.endpoint=http://otel-collector:4317", "-Dotel.exporter.otlp.protocol=grpc", "-Dsun.net.inetaddr.ttl=5", "-XX:MaxRAMPercentage=60", "-Dlogback.configurationFile=logback-json.xml", "-cp", "app.jar:lib/*", "com.elsworne.stubline.http.TicketingMain"]
CMD ["jdk", "8080"]
