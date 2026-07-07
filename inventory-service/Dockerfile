FROM eclipse-temurin:21.0.12.1_1-jdk-noble AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY inventory-api/pom.xml inventory-api/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml
COPY ticketing-api/pom.xml ticketing-api/pom.xml
COPY load-generator/pom.xml load-generator/pom.xml
COPY payment-provider-stub/pom.xml payment-provider-stub/pom.xml
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -q -pl inventory-service -am -DskipTests -DexcludeReactor=true org.apache.maven.plugins:maven-dependency-plugin:3.11.0:go-offline
COPY inventory-api inventory-api
COPY inventory-service inventory-service
COPY ticketing-api ticketing-api
COPY load-generator load-generator
COPY payment-provider-stub payment-provider-stub
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -q -pl inventory-service -am -DskipTests install && ./mvnw -q -pl inventory-service org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dependency

FROM eclipse-temurin:21.0.12.1_1-jre-noble
RUN useradd --system --uid 10001 --create-home app
WORKDIR /app
COPY --from=build /src/inventory-service/target/inventory-service.jar app.jar
COPY --from=build /src/inventory-service/target/dependency/ lib/
USER 10001
EXPOSE 9090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-Dlogback.configurationFile=logback-json.xml", "-cp", "app.jar:lib/*", "com.elsworne.stubline.inventory.service.InventoryMain"]
