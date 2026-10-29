# syntax=docker/dockerfile:1

# Dependencies stage: resolve Maven dependencies so they are cached until pom.xml changes
FROM eclipse-temurin:21-jdk AS deps
WORKDIR /app
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

# Dev stage: run from source; compose bind-mounts src/ and restarts on demand
FROM deps AS dev
EXPOSE 8080
CMD ["./mvnw", "-B", "spring-boot:run"]

# Build stage: compile and package the Spring Boot jar
FROM deps AS build
COPY src src
RUN ./mvnw -B -q package -DskipTests \
    && cp target/construx-*.jar app.jar

# Runtime stage (default target): JRE only, non-root user
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN groupadd --system construx && useradd --system --gid construx construx
COPY --from=build /app/app.jar app.jar
USER construx

EXPOSE 8080
# Configuration comes from environment variables (DB_URL, DB_PASSWORD, JWT_SECRET, GOOGLE_CLIENT_ID, ...).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
