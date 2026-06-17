# =========================
# Stage 1 - Build
# =========================
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app

# Copy Maven wrapper and dependencies first
COPY .mvn .mvn
COPY mvnw .
COPY pom.xml .

# Download dependencies (cached layer)
RUN chmod +x mvnw && \
    ./mvnw dependency:go-offline -B

# Copy source
COPY src ./src

# Build application
RUN ./mvnw clean package -DskipTests -B && \
    cp target/*.jar target/app.jar


# =========================
# Stage 2 - Runtime
# =========================
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Create non-root user
RUN addgroup -S pibase && \
    adduser -S pibase -G pibase

# Application directories
RUN mkdir -p /app/data /app/logs && \
    chown -R pibase:pibase /app

# Copy application
COPY --from=build /app/target/app.jar app.jar

USER pibase

# JVM tuned for Raspberry Pi
ENV JAVA_OPTS="-Xms128m -Xmx384m \
-XX:+UseG1GC \
-XX:MaxGCPauseMillis=100 \
-XX:+UseStringDeduplication"

EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8080/api/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]