# ── Stage 1: Build the JAR ────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
# Cache dependencies separately so rebuilds are fast
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: Lean runtime image with Tesseract OCR ────────────────────────────
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Install Tesseract + English trained data
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        tesseract-ocr \
        tesseract-ocr-eng && \
    rm -rf /var/lib/apt/lists/*

# Set tessdata path (Debian/Ubuntu default)
ENV TESSDATA_PATH=/usr/share/tessdata

# Uploads directory (mount a volume or use S3 in production)
RUN mkdir -p /app/uploads
ENV UPLOAD_DIR=/app/uploads

# Copy the built JAR
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# Always run with the production Spring profile
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=prod"]
