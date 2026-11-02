# 前端镜像：直接把【宿主已构建并已过密钥扫描】的 dist 塞进 nginx。
#
# 为什么不在容器里跑 npm build（node 镜像方案）：
# ① 本机 node:*-alpine 基础镜像常拉不到（registry 抖动，见 backend 同款问题）；
# ② 更重要的是安全流程不变——npm run build 的收尾就是
#    scripts/check-secrets.mjs dist 扫描（密钥红线 L3），该门禁在宿主构建时已执行；
#    镜像只做"分发产物"，不重复构建职责。
# 前提纪律：必须先 `cd frontend && npm run build` 成功再 build 本镜像（compose 不代跑前端构建）。
FROM nginx:1.25-alpine
COPY frontend/dist /usr/share/nginx/html
# nginx.conf 由 compose 以 bind mount 提供（改反代配置不重打镜像）
EXPOSE 80
