# ==========================================
# STAGE 1: Build the application
# ==========================================
FROM maven:3.9.6-eclipse-temurin-21 AS builder
WORKDIR /build

# Copy the pom.xml first to cache the dependencies
COPY pom.xml .
# This step pulls dependencies and caches them in the Docker layer
RUN mvn dependency:go-offline -B

# Copy the actual source code and build the jar
COPY src ./src
RUN mvn clean package -DskipTests

# ==========================================
# STAGE 2: Create the lightweight runtime image
# ==========================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create a non-root user for security best practices
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copy only the built JAR from Stage 1
COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8083

# Force the JVM onto a strict 256MB so Mac doesn't crash
ENV JAVA_TOOL_OPTIONS="-Xms256m -Xmx256m"

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]