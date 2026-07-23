FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -DskipTests dependency:go-offline
COPY src src
RUN ./mvnw -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENV PORT=8080
ENV JAVA_TOOL_OPTIONS="-Xms64m -Xmx240m -XX:MaxMetaspaceSize=80m -XX:CompressedClassSpaceSize=16m -XX:ReservedCodeCacheSize=32m -XX:MaxDirectMemorySize=16m -XX:+UseSerialGC -Xss256k -Dspring.backgroundpreinitializer.ignore=true"
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
