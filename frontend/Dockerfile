# 前端构建 + 静态托管。dist 产物构建期已过 scripts/check-secrets.mjs 扫描（npm build 内嵌），
# 镜像层不再含任何密钥形态字符串（密钥红线 L3 的镜像级落实）。
FROM docker.m.daocloud.io/library/node:22-alpine AS build
WORKDIR /web
COPY package*.json ./
RUN npm ci --registry=https://registry.npmmirror.com --no-audit --no-fund
COPY . .
RUN npm run build

FROM docker.m.daocloud.io/library/nginx:alpine
COPY --from=build /web/dist /usr/share/nginx/html
# nginx.conf 由 compose 挂载（配置与镜像解耦，改配置不重 build）
EXPOSE 80
