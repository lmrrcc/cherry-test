ARG MINI_DISCORD_BUILD_IMAGE=eclipse-temurin:25-jdk-alpine@sha256:3fd2d245c4e0eba615fe366a71b8bd25f5db7104f53e4026b24bf508b880bd2a
FROM ${MINI_DISCORD_BUILD_IMAGE} AS builder
WORKDIR /build
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN ./mvnw -B -ntp dependency:go-offline
COPY src src
RUN ./mvnw -B -ntp -DskipUnitTests=true -DskipITs=true verify

FROM builder AS offline-tests
ENV JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true -Xmx512m -XX:ActiveProcessorCount=2"
ENTRYPOINT ["./mvnw", "-o", "-B", "-ntp", "-Dmini.discord.isolated=true"]
CMD ["verify"]

FROM eclipse-temurin:25-jre-alpine@sha256:3c0a9084927a221ccd1d007fcaf614465672c0af37aaa834c5184483afe56d61 AS runtime
WORKDIR /app
COPY --from=builder /build/target/mini-discord.jar /app/mini-discord.jar
COPY src/main/resources/fixtures /app/fixtures
COPY scripts/container-entrypoint.sh /app/container-entrypoint.sh
RUN addgroup -g 10001 mini-discord && adduser -D -H -u 10001 -G mini-discord mini-discord
ENV MINI_DISCORD_MODE=LOCAL_MINI_DISCORD MINI_DISCORD_BIND=0.0.0.0 MINI_DISCORD_CONTAINER=true MINI_DISCORD_PORT=8080 MINI_DISCORD_GATEWAY_PORT=8081
ENV JAVA_TOOL_OPTIONS="-Xmx256m -XX:ActiveProcessorCount=2"
USER 10001:10001
EXPOSE 8080 8081
HEALTHCHECK --interval=5s --timeout=15s --start-period=15s --retries=12 CMD ["java", "-jar", "/app/mini-discord.jar", "health"]
ENTRYPOINT ["java", "-jar", "/app/mini-discord.jar"]
