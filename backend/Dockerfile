# 后端生产镜像：多阶段构建（编译层 JDK+Maven，运行层仅 JRE，非 root）。
#
# <h3>离线依赖策略（本机环境现实约束的解法）</h3>
# 本机制品环境 Docker 容器直连任何远程 Maven 源均失败（阿里云 502/TLS 掐断、中央仓超时；
# 宿主 FlClash 代理只对宿主进程生效）。解法：宿主已验证的本地 Maven 仓库经命名上下文
# m2seed 挂进构建，settings-seed.xml 用 <mirrorOf>*</mirrorOf> 把所有仓库（含父 pom 里的
# central，Spring Boot parent 还会带 spring-milestones）统一重定向到 file:///seed —— 
# Maven 完全离线化。CI/正常网络机器删掉 additional_contexts 与环境变量即用在线镜像源。
# 用法（本地）:
#   docker build -f backend/Dockerfile \
#     --build-context m2seed=E:/develop/apache-maven-3.9.4/mvn_repo -t kb-backend .
# compose 已在 build.additional_contexts 里等价配好。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY deploy/maven-settings.xml /tmp/
# pom 与源码分层 COPY：pom 不变时源码改动不击穿依赖层缓存（常规 Dockerfile 优化惯例）
COPY backend/pom.xml ./
COPY backend/kb-common/pom.xml ./kb-common/
COPY backend/kb-security/pom.xml ./kb-security/
COPY backend/kb-document/pom.xml ./kb-document/
COPY backend/kb-rag/pom.xml ./kb-rag/
COPY backend/kb-app/pom.xml ./kb-app/
COPY backend/kb-common ./kb-common
COPY backend/kb-security ./kb-security
COPY backend/kb-document ./kb-document
COPY backend/kb-rag ./kb-rag
COPY backend/kb-app ./kb-app
# cache mount 保留 .m2/repository 增量（只挂 repository 子目录——挂整 .m2 会遮蔽 settings.xml，
# 镜像源静默失效且"构建照样成功"，毕设实测教训）。种子优先时走 file:///seed 不写缓存也无妨。
RUN --mount=type=cache,target=/root/.m2/repository \
    --mount=type=bind,from=m2seed,target=/seed,readonly \
    cp -rn /seed/. /root/.m2/repository/ ; \
    find /root/.m2/repository -name "_remote.repositories" -delete ; \
    mvn -q -o -DskipTests -s /tmp/maven-settings.xml package \
    || (echo "=== offline seed build failed, retrying online ===" && \
        mvn -q -DskipTests -s /tmp/maven-settings.xml package)

# 编译产物密钥门禁（L1）由三道更可靠的防线承担，不在镜像构建里做易碎的解包扫描：
#   ① pre-commit gitleaks 式扫描挡 git；② 根 .dockerignore 挡 .env 进任何层；
#   ③ CI/发布前对 jar 直接 `node scripts/check-secrets.mjs backend/kb-app/target`（纯文本层）。
FROM eclipse-temurin:21-jre
# 非 root 运行：容器被突破时攻击者拿到的不是 root（纵深防御）
RUN useradd -r -u 10001 kbuser
WORKDIR /app
COPY --from=build /src/kb-app/target/kb-app.jar app.jar
# 上传与日志目录留给 volume；.env 绝不进任何镜像层（密钥红线 L8：运行期 -e 注入）
RUN mkdir -p /app/data/uploads /app/logs && chown -R kbuser /app
USER kbuser
EXPOSE 8080
# healthcheck 用后端自带的免鉴权 /api/ping（compose 侧也配一道，双保险）
HEALTHCHECK --interval=30s --timeout=5s --start-period=50s \
  CMD curl -fs http://127.0.0.1:8080/api/ping || exit 1
# sh -c + $JAVA_OPTS 透传：容器内网络环境需要代理的机器（如本机 WSL2 NAT 绕过宿主 TUN），
# 在 deploy/.env 配 JAVA_OPTS="-Dhttps.proxyHost=... -Dhttps.proxyPort=..." 即可，不改镜像。
# 默认 JAVA_OPTS 空串：正常联网机器零感知。
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
