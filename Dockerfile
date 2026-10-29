# syntax=docker/dockerfile:1

# Build stage: compile and package the Spring Boot jar with the Maven wrapper
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Resolve dependencies first so they are cached until pom.xml changes
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -q package -DskipTests \
    && cp target/construx-*.jar app.jar

# Runtime stage: JRE only, non-root user
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system construx && useradd --system --gid construx construx
COPY --from=build /app/app.jar app.jar
USER construx

EXPOSE 8080
# Configuration comes from environment variables (DB_PASSWORD, JWT_SECRET, GOOGLE_CLIENT_ID, ...).
# Override the datasource with SPRING_DATASOURCE_URL, e.g. jdbc:postgresql://db:5432/construx
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
