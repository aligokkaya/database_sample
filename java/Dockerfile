# ============================================================
# Stage 1 – Build
# ============================================================
FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /build

# Copy dependency descriptors first for layer caching
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source and build the fat JAR
COPY src ./src
RUN mvn clean package -DskipTests -q

# ============================================================
# Stage 2 – Runtime
# ============================================================
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Create a non-root user for security
RUN groupadd --system appgroup && \
    useradd  --system --gid appgroup appuser

# Copy the built artifact
COPY --from=builder /build/target/discovery-1.0.0.jar app.jar

RUN chown appuser:appgroup app.jar
USER appuser

EXPOSE 8080

# JVM tuning: container-aware heap sizing, IPv4 preference, no JVM classpath caching
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-Djava.net.preferIPv4Stack=true", \
  "-jar", "app.jar"]
