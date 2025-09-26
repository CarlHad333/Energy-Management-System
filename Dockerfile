# Multi-stage build for Station Energy Management System
# Production-ready Docker image with security and performance optimizations

# Build stage (maintained tag)
FROM maven:3.9.9-eclipse-temurin-17 AS builder

WORKDIR /app

# Copy dependency files for better caching
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build
COPY src ./src
RUN mvn clean package -DskipTests

# Runtime stage (lighter JRE image)
FROM eclipse-temurin:17-jre AS runtime

# Create non-root user for security
RUN groupadd -g 1001 spring && \
    useradd -r -u 1001 -g spring spring

# Install required packages and clean up
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        curl \
        dumb-init && \
    rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Copy jar from build stage
COPY --from=builder /app/target/Energy-Management-System-1.0.0.jar app.jar

# Create logs directory
RUN mkdir -p logs && chown -R spring:spring /app

# Switch to non-root user
USER spring

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/api/v1/station/health || exit 1

# Expose port
EXPOSE 8080

# Use dumb-init for proper signal handling
ENTRYPOINT ["dumb-init", "--"]

# Run application with optimized JVM settings
CMD ["java", \
     "-server", \
     "-XX:+UseG1GC", \
     "-XX:MaxGCPauseMillis=200", \
     "-XX:+UseStringDeduplication", \
     "-Xms512m", \
     "-Xmx1g", \
     "-XX:+HeapDumpOnOutOfMemoryError", \
     "-XX:HeapDumpPath=/app/logs/", \
     "-Dspring.profiles.active=docker", \
     "-jar", "app.jar"]
