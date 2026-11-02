# 后端生产镜像：多阶段构建（编译与运行分离，运行层不含 JDK/Maven 全家桶）
# 基础镜像走 daocloud 镜像源：本机 docker.io 直连常超时（实测），源内前缀是最稳写法；
# 换环境部署时若直连可用可去掉前缀。
FROM docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
# 依赖缓存挂 cache mount：只挂 repository 子目录——挂整 .m2 会把 COPY 进去的 settings.xml 遮蔽掉
# （构建"照样成功"但镜像源失效、速度暴跌，极难察觉；此为毕设项目实测教训）
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -q -DskipTests package

FROM docker.m.daocloud.io/library/eclipse-temurin:21-jre
# 非 root 运行：容器被破时攻击者拿到的不是 root
RUN useradd -r -u 10001 kbuser
WORKDIR /app
COPY --from=build /src/kb-app/target/kb-app.jar app.jar
# 上传目录与日志目录留给 volume；.env 绝不进镜像层（密钥红线 L8：运行时 -e 注入）
RUN mkdir -p /app/data/uploads /app/logs && chown -R kbuser /app
USER kbuser
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s \
  CMD curl -fs http://127.0.0.1:8080/api/ping || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
