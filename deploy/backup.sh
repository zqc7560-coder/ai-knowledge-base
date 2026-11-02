#!/usr/bin/env bash
# 每日备份：MySQL(mysqldump 单库) + pgvector(pg_dump) + 上传文件 tar。
# 保留最近 14 份；备份目录满告警由 cron 邮件承担。
# 用法: backup.sh [目标目录=/var/backups/kb]
set -euo pipefail
DEST="${1:-/var/backups/kb}"
COMPOSE_DIR="$(cd "$(dirname "$0")" && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$DEST"
# 口令含特殊字符时 `source` 会炸（实测 KB_ADMIN_PASSWORD 触发），只提取本脚本需要的键。
# `|| true` 必需：键缺失时 grep 退出码非 0，会在 set -e + pipefail 下静默终止整个脚本（实测踩过）。
ENVV() { grep -E "^$1=" "$COMPOSE_DIR/.env" 2>/dev/null | head -1 | cut -d= -f2- || true; }
MYSQL_ROOT_PW="$(ENVV MYSQL_ROOT_PASSWORD)"
PG_USER="$(ENVV PGVECTOR_USER)"; PG_USER="${PG_USER:-kb_app}"
PG_DB="$(ENVV PGVECTOR_DB)"; PG_DB="${PG_DB:-kb_vectors}"

# 容器内执行避免宿主机装 mysql 客户端；单库模式不带 --databases，恢复端需显式给库名（实测坑）
docker exec kb-mysql sh -c "mysqldump -uroot -p'${MYSQL_ROOT_PW}' --single-transaction --default-character-set=utf8mb4 kb_system" \
  | gzip > "$DEST/mysql-$STAMP.sql.gz"
docker exec kb-pgvector pg_dump -U "$PG_USER" -d "$PG_DB" | gzip > "$DEST/pgvector-$STAMP.sql.gz"
# 上传件：从 backend 容器 volume 打 tar（compose 卷名 = {project}_{volume}，project=kb）。
# 工具镜像用本地已有的 kb-backend（temurin 自带 tar）——现场环境拉不到 alpine，依赖本地镜像最稳。
# 必须 --entrypoint tar：否则命令参数会喂给镜像的 java ENTRYPOINT，产物是启动日志不是归档（实测踩过）。
docker run --rm --entrypoint tar -v kb_kb-uploads:/data:ro kb-backend:test czf - -C /data . > "$DEST/uploads-$STAMP.tar.gz"

# 体检：首行特征断言（mysqldump 产物首行是 "-- MySQL dump" 而非含字面量 mysqldump——实测反直觉点）。
# 不用 `zcat | grep -q`：grep 命中即退会让 zcat 吃 SIGPIPE(141)，pipefail 下误判失败（实测踩过）；
# 改为先解包到临时文件再查。
MCHK=$(zcat "$DEST/mysql-$STAMP.sql.gz" | head -1); echo "$MCHK" | grep -q "MySQL dump" || { echo "FATAL: mysql dump 内容异常"; exit 1; }
zcat "$DEST/pgvector-$STAMP.sql.gz" > "$DEST/.pgcheck" && grep -q kb_vectors "$DEST/.pgcheck" && rm -f "$DEST/.pgcheck" || { echo "FATAL: pg dump 缺表"; exit 1; }

ls -t "$DEST"/mysql-*.sql.gz | tail -n +15 | xargs -r rm -f
ls -t "$DEST"/pgvector-*.sql.gz | tail -n +15 | xargs -r rm -f
ls -t "$DEST"/uploads-*.tar.gz | tail -n +15 | xargs -r rm -f
echo "backup ok -> $DEST ($STAMP)"
