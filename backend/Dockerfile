FROM gradle:9.4.1-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle :backend:installDist --no-daemon
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/backend/build/install/backend /app
RUN mkdir -p /data/files && chown -R 10001:10001 /data /app
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["/app/bin/backend"]
