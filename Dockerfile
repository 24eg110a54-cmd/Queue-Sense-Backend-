# Multi-stage Dockerfile for Spring Boot Backend
FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /app

# Cache dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Build application
COPY src ./src
RUN mvn clean package -DskipTests

# Runtime image
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Ensure data directory exists for H2 database
RUN mkdir -p /app/data
VOLUME ["/app/data"]

COPY --from=build /app/target/queuesense-backend-*.jar app.jar

ENV PORT=8000
ENV ADMIN_KEY=admin123
ENV DATABASE_URL=jdbc:h2:file:./data/queuesense

EXPOSE 8000

ENTRYPOINT ["java", "-jar", "app.jar"]
