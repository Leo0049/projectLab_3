# Build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependency layer first so source edits do not re-resolve the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Suite A needs a database; it runs in CI, not in the image build.
RUN mvn -B -q clean package -DskipTests

# Run
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S bizmcp && adduser -S bizmcp -G bizmcp
COPY --from=build /build/target/bizmcp-*.jar app.jar
USER bizmcp

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
