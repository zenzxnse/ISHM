# Multi-stage Dockerfile for Soil Health Monitoring System

# Stage 1: Build
FROM gradle:8.5-jdk17 AS build

WORKDIR /app

# Copy gradle files
COPY build.gradle settings.gradle ./
COPY gradle ./gradle

# Download dependencies (cached layer)
RUN gradle dependencies --no-daemon || true

# Copy source code
COPY src ./src

# Build application
RUN gradle shadowJar --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="support@soilhealth.gov.in"
LABEL description="Interactive Soil Health Monitoring System"
LABEL version="1.0.0"

# Install required packages
RUN apk add --no-cache \
    curl \
    bash \
    tzdata

# Set timezone
ENV TZ=Asia/Kolkata
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# Create app user
RUN addgroup -g 1001 appuser && \
    adduser -D -u 1001 -G appuser appuser

WORKDIR /app

# Copy JAR from build stage
COPY --from=build /app/build/libs/*.jar app.jar

# Copy static resources (if not bundled in JAR)
COPY src/main/resources/public ./public

# Change ownership
RUN chown -R appuser:appuser /app

# Switch to non-root user
USER appuser

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

# Expose port
EXPOSE 8080

# JVM options for container
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+UseStringDeduplication"

# Run application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

# Default command (can be overridden)
CMD []