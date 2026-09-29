# Build stage: Maven + JDK to compile the project
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY . .
RUN mvn -B -q -DskipTests package

# Run stage: Microsoft's Playwright Java image already has Chromium/Firefox/WebKit installed
FROM mcr.microsoft.com/playwright/java:v1.47.0-jammy
WORKDIR /workspace

COPY --from=build /workspace /workspace
COPY --from=build /root/.m2 /root/.m2

RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

ENTRYPOINT ["mvn", "-B", "test"]
