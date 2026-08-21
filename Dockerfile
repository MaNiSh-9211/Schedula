# build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY persistence/pom.xml persistence/pom.xml
COPY queue/pom.xml queue/pom.xml
COPY dispatcher/pom.xml dispatcher/pom.xml
COPY engine/pom.xml engine/pom.xml
COPY worker-runtime/pom.xml worker-runtime/pom.xml
COPY api/pom.xml api/pom.xml
COPY app/pom.xml app/pom.xml
RUN mvn -q -B dependency:go-offline || true
COPY common common
COPY persistence persistence
COPY queue queue
COPY dispatcher dispatcher
COPY engine engine
COPY worker-runtime worker-runtime
COPY api api
COPY app app
RUN mvn -q -B -DskipTests package

# runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S schedula && adduser -S schedula -G schedula
USER schedula
COPY --from=build /build/app/target/schedula-app-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
