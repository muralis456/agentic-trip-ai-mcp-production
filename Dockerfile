# syntax=docker/dockerfile:1

FROM eclipse-temurin:26-jdk AS build
WORKDIR /workspace

COPY . .
RUN chmod +x mvnw && ./mvnw -B -DskipTests clean package

FROM eclipse-temurin:26-jre
WORKDIR /app

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

COPY --from=build /workspace/target/agentic-trip-ai-mcp-production-0.0.1-SNAPSHOT.jar /app/app.jar

EXPOSE 8081

USER 10001:10001

ENTRYPOINT ["java","-jar","/app/app.jar"]
