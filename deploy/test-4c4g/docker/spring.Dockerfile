FROM eclipse-temurin:21-jre@sha256:d77e090ae1f6970c047b443c6f0a6c1358b0426d44432bb5a7753408cd43de0d
WORKDIR /app
COPY --chmod=0444 spring.jar /app/app.jar
USER 10001:10001
ENTRYPOINT ["java", "-Xms256m", "-Xmx768m", "-XX:MaxMetaspaceSize=192m", "-XX:+ExitOnOutOfMemoryError", "-Xlog:gc*:stdout:time,uptime,level,tags", "-jar", "/app/app.jar"]
