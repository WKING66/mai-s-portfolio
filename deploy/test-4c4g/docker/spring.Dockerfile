# Spring 后端容器：只跑已构建好的可执行 jar。
# 镜像来自 GitHub Actions 产物，服务器不重新编译。
FROM eclipse-temurin:21-jre

RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
COPY spring.jar /app/app.jar

USER 10001:10001
EXPOSE 9333

# 堆上限按 4 GB 单机预算：Spring 1.1 GB，给容器外留出余量。
ENTRYPOINT ["java", "-Xms256m", "-Xmx1100m", "-XX:MaxMetaspaceSize=256m", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
