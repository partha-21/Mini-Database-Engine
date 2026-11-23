# Multi-stage build using Eclipse Temurin JDK 21 Alpine
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy all source files and project scripts
COPY . .

# Compile engine sources
RUN mkdir -p out && javac -d out $(find src/minidb -name "*.java")

# Compile and run test suite during image build to verify integrity
RUN mkdir -p out-test && javac -d out-test -cp out src/test/java/minidb/Tests.java && java -cp out:out-test minidb.Tests

# Runtime stage using lightweight JRE
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy compiled classes from builder
COPY --from=builder /app/out ./out

# Create persistent database directory
RUN mkdir -p /app/minidb-data

# Default port for Railway / Docker
ENV PORT=8080
EXPOSE 8080

# Start MiniDB in server mode
CMD ["java", "-cp", "out", "minidb.Main", "server"]
