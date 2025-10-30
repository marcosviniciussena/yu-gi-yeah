# build stage
FROM maven:3.8.8-openjdk-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -e -B package -DskipTests

# runtime
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/servidor-raft-redis-1.0-SNAPSHOT.jar /app/app.jar
EXPOSE 5000 6000
CMD ["java", "-jar", "/app/app.jar"]
