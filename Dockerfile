FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY backend/pom.xml .
COPY backend/src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar

# Create data and report directories that are bind-mounted at runtime
RUN mkdir -p data/uploads data/working reports

EXPOSE 8080
ENTRYPOINT ["java", \
  "-Xms128m", "-Xmx512m", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "app.jar"]
