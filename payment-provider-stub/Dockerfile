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
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -q -pl payment-provider-stub -am -DskipTests -DexcludeReactor=true org.apache.maven.plugins:maven-dependency-plugin:3.11.0:go-offline
COPY inventory-api inventory-api
COPY inventory-service inventory-service
COPY notification-service notification-service
COPY ticketing-api ticketing-api
COPY load-generator load-generator
COPY payment-provider-stub payment-provider-stub
COPY dev-token-issuer dev-token-issuer
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -q -pl payment-provider-stub -am -DskipTests install && \
    ./mvnw -q -pl payment-provider-stub -am org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dependency

FROM eclipse-temurin:21.0.12.1_1-jre-noble
RUN useradd --system --uid 10001 --create-home app
WORKDIR /app
COPY --from=build /src/payment-provider-stub/target/payment-provider-stub.jar app.jar
COPY --from=build /src/payment-provider-stub/target/dependency/ lib/
USER 10001
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-Dlogback.configurationFile=logback-json.xml", "-cp", "app.jar:lib/*", "com.elsworne.stubline.provider.PaymentProviderMain"]
