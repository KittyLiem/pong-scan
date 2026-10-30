# Build: Quarkus fast-jar met Maven op JDK 25.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Runtime: alleen de JRE en de gebouwde applicatie, als niet-root gebruiker.
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 10001 --no-create-home quarkus
COPY --from=build --chown=quarkus /build/target/quarkus-app/lib/ ./lib/
COPY --from=build --chown=quarkus /build/target/quarkus-app/*.jar ./
COPY --from=build --chown=quarkus /build/target/quarkus-app/app/ ./app/
COPY --from=build --chown=quarkus /build/target/quarkus-app/quarkus/ ./quarkus/
USER quarkus
EXPOSE 8080
ENV JAVA_OPTS_APPEND="-XX:MaxRAMPercentage=75 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS_APPEND -jar /app/quarkus-run.jar"]
