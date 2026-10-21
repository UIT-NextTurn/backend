# ==============================================================================
# Multi-stage Dockerfile for NextTurn Spring Boot Backend (Java 25)
# Optimized for Multi-Architecture: linux/amd64 & linux/arm64
# Base Image: Ubuntu (Jammy) - *Update comment if you specifically require Oracle Linux 9*
# ==============================================================================

# ------------------------------------------------------------------------------
# Stage 1: Build Application
# ------------------------------------------------------------------------------
FROM eclipse-temurin:26.0.2.1_1-jdk-noble AS builder
WORKDIR /app

# Cache Maven dependencies by copying descriptor and wrapper first
COPY mvnw pom.xml ./
COPY .mvn .mvn

# Ensure wrapper is executable and download dependencies offline
RUN chmod +x ./mvnw && ./mvnw dependency:go-offline -B

# Copy project source tree and package application
COPY src src
RUN ./mvnw -B clean package -DskipTests

# ------------------------------------------------------------------------------
# Stage 2: Production JRE Runtime
# ------------------------------------------------------------------------------
FROM eclipse-temurin:26.0.2.1_1-jre-noble
WORKDIR /app

# Container security hardening: Non-root user
RUN addgroup --system nextturn && adduser --system --ingroup nextturn nextturn

# Install curl for healthchecks (minimal JRE images often lack wget/curl)
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Copy compiled runnable JAR from builder stage
# Note: Spring Boot Maven plugin renames the original artifact to .jar.original, 
# so *.jar safely matches only the executable repackaged jar.
COPY --from=builder /app/target/*.jar /app/app.jar

# Enforce secure file permissions
RUN chown -R nextturn:nextturn /app

# Run as unprivileged user
USER nextturn

# Expose application port
EXPOSE 8080

# Container ergonomics & Graceful Shutdown
# Note: UseContainerSupport is default in Java 10+, but harmless to keep.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"

# Enable Spring Boot graceful shutdown to finish active requests on SIGTERM
ENV SERVER_SHUTDOWN=graceful
ENV SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=20s

# Healthcheck probe (using curl instead of wget, targeting liveness)
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health/liveness || exit 1

# CRITICAL: 'exec' replaces the shell with Java, making Java PID 1 so it receives SIGTERM
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]