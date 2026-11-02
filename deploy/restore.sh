#!/usr/bin/env bash
# 恢复演练脚本：把一个备份前缀的三件套恢复到【影子库】，绝不动生产库。
# 用法: restore.sh <备份文件前缀>   例: restore.sh /var/backups/kb/mysql-20261102-031700.sql.gz
# 影子库名 kb_restore_test；验证行数>0 即成功。生产恢复=把影子库名换成 kb_system 并走同样管道（需人工确认）。
set -euo pipefail
SRC="${1:?用法: restore.sh <mysql备份.sql.gz> [pg备份.sql.gz] [uploads.tar.gz]}"
COMPOSE_DIR="$(cd "$(dirname "$0")" && pwd)"
# 不用 source：特殊字符口令会让 source 中断脚本（backup.sh 同款实测坑）
ENVV() { grep -E "^$1=" "$COMPOSE_DIR/.env" 2>/dev/null | head -1 | cut -d= -f2- || true; }
ROOT_PW="$(ENVV MYSQL_ROOT_PASSWORD)"
DB=kb_restore_test

echo "[1/3] 创建影子库并导入 mysql 备份: $SRC"
docker exec kb-mysql sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -e 'DROP DATABASE IF EXISTS $DB; CREATE DATABASE $DB CHARACTER SET utf8mb4'"
# 单库 dump 无 USE 语句 -> 必须显式给库名，否则 1046 No database selected（实测坑）
zcat "$SRC" | docker exec -i kb-mysql sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" --default-character-set=utf8mb4 $DB"
ROWS=$(docker exec kb-mysql sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N -e \"SELECT COUNT(*) FROM $DB.document_chunks\"")
echo "  document_chunks rows=$ROWS"
[ "$ROWS" -gt 0 ] || { echo "FATAL: 恢复行数异常"; exit 1; }

if [ -n "${2:-}" ]; then
  echo "[2/3] pgvector 备份恢复（影子表 _restore 后缀需自行改名，演练默认跳过导入仅校验文件完整）"
  zcat "$2" | grep -q kb_vectors && echo "  pg dump 结构校验 OK"
fi
if [ -n "${3:-}" ]; then
  echo "[3/3] uploads tar 校验"
  tar tzf "$3" >/dev/null && echo "  tar 完整 OK"
fi
docker exec kb-mysql sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -e 'DROP DATABASE IF EXISTS $DB'"
echo "restore drill PASS（影子库已清理）"
