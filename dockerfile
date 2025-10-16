# Multi-stage build for optimized production image

# Stage 1: Build stage
FROM gradle:8.5-jdk17 AS builder

WORKDIR /app

# Copy gradle wrapper and build files
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# Download dependencies (cached layer)
RUN ./gradlew dependencies --no-daemon

# Copy source code
COPY src src

# Build the application
RUN ./gradlew shadowJar --no-daemon

# Stage 2: Runtime stage
FROM eclipse-temurin:17-jre-alpine

# Install additional packages if needed
RUN apk add --no-cache \
    curl \
    tzdata \
    && rm -rf /var/cache/apk/*

# Set timezone
ENV TZ=Asia/Kolkata
RUN cp /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# Create application user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=builder /app/build/libs/soil-health-map*.jar app.jar

# Copy static resources
COPY --from=builder /app/src/main/resources/public /app/static

# Create directories for logs and data
RUN mkdir -p /app/logs /app/data && \
    chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

# JVM options for container environment
ENV JAVA_OPTS="-Xmx512m -Xms256m \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -XX:+UseStringDeduplication \
    -Djava.security.egd=file:/dev/./urandom \
    -Dcom.sun.management.jmxremote=false"

# Environment variables for configuration
ENV MICRONAUT_ENVIRONMENTS=docker
ENV MICRONAUT_SERVER_PORT=8080
ENV DB_URL=jdbc:postgresql://db:5432/ishm
ENV DB_USER=ishm
ENV DB_PASSWORD=ishm

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]