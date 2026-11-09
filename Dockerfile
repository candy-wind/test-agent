# ---------- 构建阶段（JDK 25 + Maven） ----------
FROM maven:3.9.11-eclipse-temurin-25-noble AS build
# 默认走阿里云 Maven 镜像，境外构建可覆盖为 https://repo.maven.apache.org/maven2
ARG MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public
WORKDIR /build

RUN mkdir -p /root/.m2 && \
    printf '%s\n' \
      '<settings>' \
      '  <mirrors>' \
      '    <mirror>' \
      '      <id>maven-mirror</id>' \
      '      <mirrorOf>central</mirrorOf>' \
      "      <url>${MAVEN_MIRROR_URL}</url>" \
      '    </mirror>' \
      '  </mirrors>' \
      '</settings>' > /root/.m2/settings.xml

# 先只拷 pom，依赖层可以复用缓存；改代码时不必重新下载依赖
COPY pom.xml ./
# BuildKit 缓存挂载：Maven 本地仓库跨构建保留，改 pom 时也只补下新增依赖
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -DskipTests clean package

# ---------- 运行阶段（仅 JRE，体积更小） ----------
FROM eclipse-temurin:25-jre

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Duser.timezone=Asia/Shanghai"

WORKDIR /app

# 非 root 用户运行；容器内目录归属 10001，宿主机挂载目录需要同样 chown（见 docs/deploy-aliyun.md）
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --no-create-home app \
 && mkdir -p /app/data/kb /app/uploads /mnt/logs/test-agent \
 && chown -R app:app /app /mnt/logs

COPY --from=build /build/target/test-agent-1.0.0.jar /app/app.jar

USER app
EXPOSE 8080

# 容器内没有 curl，用 bash 的 /dev/tcp 探活
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
