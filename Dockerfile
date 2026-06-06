# ── Stage 1: Build the JAR ────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
# Cache dependencies separately so rebuilds are fast
RUN mvn dependency:go-offline -q
COPY src ./src
# -Pnewrelic downloads newrelic.jar to newrelic/newrelic.jar alongside the app JAR
RUN mvn package -DskipTests -Pnewrelic -q

# ── Stage 2: Lean runtime image with Tesseract OCR ────────────────────────────
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Install Tesseract + English trained data + HEIC/HEIF conversion tools
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        tesseract-ocr \
        tesseract-ocr-eng \
        libheif-examples \
        imagemagick \
        libheif1 && \
    rm -rf /var/lib/apt/lists/*

# Ubuntu installs tessdata to /usr/share/tesseract-ocr/4.00/tessdata/
# Symlink to the path our app expects so Tesseract can find eng.traineddata
RUN ln -s /usr/share/tesseract-ocr/4.00/tessdata /usr/share/tessdata

# Set tessdata path (Debian/Ubuntu default)
ENV TESSDATA_PATH=/usr/share/tessdata

# Uploads directory (mount a volume or use S3 in production)
RUN mkdir -p /app/uploads
ENV UPLOAD_DIR=/app/uploads

# Copy the built JAR
COPY --from=build /app/target/*.jar app.jar

# New Relic APM agent — bundled so the image is self-contained.
# Activated at runtime via JAVA_TOOL_OPTIONS env var in docker-compose / .env.
# When JAVA_TOOL_OPTIONS is not set the agent is never attached — zero impact.
COPY --from=build /app/newrelic/newrelic.jar newrelic/newrelic.jar
COPY newrelic/newrelic.yml newrelic/newrelic.yml

EXPOSE 8080

# JAVA_TOOL_OPTIONS is read automatically by the JVM before main() runs.
# Set it in .env to tune JVM memory and optionally activate the NR agent.
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=prod"]
