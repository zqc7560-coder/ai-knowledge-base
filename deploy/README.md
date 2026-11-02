# 部署手册（Deploy README）

> 目标形态：单机 Docker Compose 全栈（mysql/redis/pgvector/backend/frontend）。
> 两种模式：本地开发（只起基础设施+裸跑后端）、生产（--profile app 全容器化）。

## 0. 前置
- Docker 24+（带 compose v2）、Linux 或 Docker Desktop。
- 端口占用检查：基础设施默认绑 127.0.0.1 高位端口（3307/6380/5433），生产 app 层用 8085/8081。

## 1. 配置（密钥红线 L8）
```bash
cd deploy
cp .env.example .env        # 若无此文件则手工创建，见下表键名
# 必填：MYSQL_ROOT_PASSWORD MYSQL_PASSWORD PGVECTOR_PASSWORD AI_API_KEY
```
| 键 | 用途 |
|---|---|
| `AI_API_KEY` | 阿里云百炼 DashScope 密钥；仅以环境变量注入 backend 容器，**任何文件/git/镜像层不得出现明文** |
| `MYSQL_*` / `PGVECTOR_*` | 各库口令，部署机自拟强随机 |
| `KB_ADMIN_USERNAME/PASSWORD` | 首启引导管理员（可选；不设则用 SQL 手工升角色） |

## 2. 本地开发（后端裸跑，热重启快）
```bash
cd deploy && docker compose up -d          # mysql/redis/pgvector
cp ../.env.example ../backend/.env          # 填密钥与各端口(3307/6380/5433)
cd .. && start\build-backend.bat && start\run-backend.bat   # 构建+启动（或直接双击 start\start-all.bat）
# 前端：cd frontend && npm run dev  （vite 代理 /api -> :8080）
```

## 3. 生产全容器化
```bash
cd frontend && npm ci && npm run build      # dist 生成并自动过密钥扫描门禁
cd ../deploy && docker compose --profile app up -d --build
docker compose ps                            # 五服务应全部 healthy/running
curl -s http://127.0.0.1:8085/api/ping       # nginx -> backend 全链路连通
```
- 前端入口 `http://<host>:8085`（nginx 托管 dist + 反代 /api，SSE 已禁缓冲）。
- 对外发布：在前面再挂一层 TLS（云 LB/certbot），nginx.conf 的 443 模板按证书路径补。

## 4. 备份与恢复（cron 每日）
```bash
bash deploy/backup.sh /var/backups/kb        # mysqldump + pg_dump + uploads tar，留 14 份
bash deploy/restore.sh <备份前缀>            # 恢复演示（影子库演练过的脚本）
```
建议 crontab：`17 3 * * * /opt/kb/deploy/backup.sh /var/backups/kb`

## 5. 升级发布
```bash
git pull && cd deploy
docker compose build --pull backend
docker compose --profile app up -d backend   # 滚动重启，Flyway 自动迁移新表
```
迁移纪律：只加 V{n}__*.sql 增量，不改已发布脚本（Flyway checksum 会炸）。

## 6. 验证清单（每次部署后）
- [ ] `/api/ping` 三 ok（mysql/redis）
- [ ] 登录成功、错误密码 5 次后临时锁定
- [ ] 上传 PDF/DOCX/MD 到达 READY，chunk 数=向量数（`SELECT count(*) FROM kb_vectors`）
- [ ] 问答流式出字、引用卡片显示、无关问题拒答
- [ ] 浏览器 DevTools 的 Network 里**不存在任何 dashscope/modelscope 域名请求**（密钥隔离的最终肉眼验证）
- [ ] `docker exec kb-backend env | grep AI_API` 仅容器内有值；`docker history kb-backend` 无密钥

## 7. 故障速查
| 症状 | 原因 | 处置 |
|---|---|---|
| backend 起不来 "kb.ai.api-key is not configured" | compose 没读到 deploy/.env 的 AI_API_KEY | 检查 .env 键名拼写 |
| 问答报 4001 AI_UPSTREAM_BUSY | 百炼 QPS 限流（实测存在） | 已内置退避重试；持续出现则降并发/申请提额 |
| 文档卡 PARSING | 解析线程池被大文件占住 | 观察 logs；池=2 是防打爆 embedding 的刻意值 |
| pgvector 检索慢 | 数据量>50万 chunk | 按 docs/01 决策切 Milvus（EmbeddingStore 抽象已预留） |
| SSE 不流式整段返回 | 中间加了反代没关缓冲 | nginx 已配 proxy_buffering off；自定义网关需同步 |
