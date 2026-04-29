FROM eclipse-temurin:25-jdk

WORKDIR /app

# JAR must be built before docker-compose up:
#   mvn package -pl . -DskipTests
COPY target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "--add-opens", "java.base/java.lang=ALL-UNNAMED", \
  "--add-opens", "java.base/java.util=ALL-UNNAMED", \
  "-jar", "app.jar"]
