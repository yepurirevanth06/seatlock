# Build stage: compile and package the Spring Boot jar
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Runtime stage: slim JRE, non-root user
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --no-create-home seatlock
COPY --from=build /app/target/*.jar app.jar
USER seatlock
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
