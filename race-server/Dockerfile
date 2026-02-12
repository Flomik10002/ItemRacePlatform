FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY . .
RUN chmod +x ./gradlew && ./gradlew :race-server:installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /workspace/race-server/build/install/race-server /app
RUN mkdir -p /app/config

ENV JAVA_OPTS="-XX:+UseG1GC -XX:+UseStringDeduplication -XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=20 -XX:MaxMetaspaceSize=192m"

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec ./bin/race-server $JAVA_OPTS"]
