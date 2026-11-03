#!/usr/bin/env bash
# M11.2 AI 密钥一键轮换：改 .env 的 AI_API_KEY → 重启后端 → ai-check 验证 → 失败自动回滚。
# 安全（docs/03 L7）：新 key 从【文件】或 getpass 读取，绝不进命令行/echo/history；
# 全程只回显掩码（前6+后4）。验证失败自动还原 .env 备份；备份含旧 key，用后提示清理。
#
# 用法：
#   bash scripts/rotate-ai-key.sh --from-file /path/newkey.txt   # 文件里是裸 key（用后安全销毁）
#   bash scripts/rotate-ai-key.sh                                 # 交互输入（不回显）
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
ENVB="$ROOT/backend/.env"
ENVDEP="$ROOT/deploy/.env"
[ -f "$ENVB" ] || { echo "缺少 $ENVB"; exit 1; }
if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  echo "用法: bash scripts/rotate-ai-key.sh [--from-file 新key文件]   # 不带参数则交互输入(不回显)"
  exit 0
fi
[ "$#" -gt 0 ] && [ "${1:-}" != "--from-file" ] && { echo "未知参数: $1"; exit 1; }

# 1) 取新 key（不落命令行）
NEW=""
if [ "${1:-}" = "--from-file" ]; then
  [ -f "${2:-}" ] || { echo "文件不存在"; exit 1; }
  NEW="$(tr -d ' \t\r\n' < "$2")"
  : > "$2" && rm -f "$2"          # 源文件覆写为空后删除，防明文残留
else
  NEW="$(python -c 'import getpass,sys; sys.stdout.write(getpass.getpass("新 AI_API_KEY: "))')"
fi
[ -n "$NEW" ] || { echo "空 key，中止"; exit 1; }
case "$NEW" in sk-*) : ;; *) echo "警告：新 key 不以 sk- 开头，仍继续（可能是其它兼容端点）";; esac

mask() { local k="$1"; printf '%s****%s' "${k:0:6}" "${k: -4}"; }
OLDKEY="$(grep '^AI_API_KEY=' "$ENVB" | cut -d= -f2-)"
[ "$OLDKEY" = "$NEW" ] && echo "提示：新旧 key 相同（演练模式，流程照常走完）"
echo "旧 key 掩码: $(mask "$OLDKEY")"
echo "新 key 掩码: $(mask "$NEW")"

# 2) 备份 + 整行重写（值经环境变量传 awk，不进任何命令行；整行替换规避 &/\ 转义炸点）
TS="$(date +%s)"
cp "$ENVB" "$ENVB.bak.$TS"
[ -f "$ENVDEP" ] && cp "$ENVDEP" "$ENVDEP.bak.$TS"
rewrite_env() { NEWKEY="$NEW" awk '{ if (index($0,"AI_API_KEY=")==1) print "AI_API_KEY=" ENVIRON["NEWKEY"]; else print }' "$1" > "$1.tmp" && mv "$1.tmp" "$1"; }
rewrite_env "$ENVB"
[ -f "$ENVDEP" ] && rewrite_env "$ENVDEP"
unset NEW OLDKEY
echo ".env 已更新（备份 .env.bak.$TS）"

# 3) 重启后端（宿主精确停→起；容器 backend 若在跑也重启）
echo "重启后端..."
if command -v powershell.exe >/dev/null 2>&1; then
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$ROOT/start/stop-procs.ps1" >/dev/null 2>&1 || true
fi
sleep 2
if [ -f "$ROOT/backend/kb-app/target/kb-app.jar" ]; then
  ( cd "$ROOT/backend" && nohup java -jar kb-app/target/kb-app.jar > "$ROOT/logs/rotate-backend.log" 2>&1 & )
fi
docker restart kb-backend >/dev/null 2>&1 || true

# 4) 等健康（宿主 8080 / 容器 8081 自适应）
echo "等待后端就绪..."
BASE=""
for i in $(seq 1 40); do
  sleep 3
  curl -s --noproxy '*' -m 5 http://127.0.0.1:8080/api/ping 2>/dev/null | grep -q code && { BASE=http://127.0.0.1:8080/api; break; }
  curl -s --noproxy '*' -m 5 http://127.0.0.1:8081/api/ping 2>/dev/null | grep -q code && { BASE=http://127.0.0.1:8081/api; break; }
done
[ -n "$BASE" ] || { echo "❌ 8080/8081 都未就绪（可能只是没重启成功，不代表 key 无效）——不回滚，.env 保持新值，请手动重启后在后台『AI 连通自检』确认。"; exit 3; }

# 5) ai-check 验证（管理员口令从 .env 读；登录/权限异常 => 无法验证(3,不回滚)；真跑出非 ok => 回滚(2)）
python - "$BASE" "$ENVB" <<'PY'
import sys, json, urllib.request, re, pathlib
base, envb = sys.argv[1], sys.argv[2]
p = pathlib.Path(envb)
if not p.exists():
    m = re.match(r"^/([a-zA-Z])/(.*)$", envb)   # git-bash /d/... -> D:/...
    if m: p = pathlib.Path(m.group(1).upper() + ":/" + m.group(2))
def call(pt, body=None, tok=None):
    d = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base+pt, data=d, method="POST" if body else "GET",
        headers={"Content-Type":"application/json", **({"Authorization":tok} if tok else {})})
    return json.loads(urllib.request.urlopen(req, timeout=60).read())
try:
    apw = re.search(r"KB_ADMIN_PASSWORD=(\S+)", p.read_text(encoding="utf-8", errors="ignore")).group(1)
    tok = call("/auth/login", {"username":"kb_admin","password":apw})["data"]["token"]
    d = call("/admin/ai-check", None, tok).get("data") or {}
    print("验证结果: chat=%s embedding=%s rerank=%s keyMask=%s" % (
        d.get("chat"), d.get("embedding"), d.get("rerank"), d.get("keyMask")))
    sys.exit(0 if (d.get("chat")=="ok" and d.get("embedding")=="ok") else 2)
except SystemExit:
    raise
except Exception as e:
    print("无法执行验证（不作 key 无效判定）:", type(e).__name__, str(e)[:80]); sys.exit(3)
PY
RC=$?
if [ "$RC" = "3" ]; then echo "⚠️ 请手动在后台『AI 连通自检』确认新 key。"; exit 3; fi
if [ "$RC" != "0" ]; then
  echo "❌ 验证失败——判定新 key 无效，自动回滚 .env 到备份并重启后端："
  cp "$ENVB.bak.$TS" "$ENVB"
  [ -f "$ENVDEP.bak.$TS" ] && cp "$ENVDEP.bak.$TS" "$ENVDEP"
  if command -v powershell.exe >/dev/null 2>&1; then powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$ROOT/start/stop-procs.ps1" >/dev/null 2>&1 || true; fi
  sleep 1
  [ -f "$ROOT/backend/kb-app/target/kb-app.jar" ] && ( cd "$ROOT/backend" && nohup java -jar kb-app/target/kb-app.jar > "$ROOT/logs/rotate-backend.log" 2>&1 & )
  docker restart kb-backend >/dev/null 2>&1 || true
  echo "已还原旧 key 运行。请核对新 key 来源后重试。"
  exit 2
fi
echo "✅ 轮换完成，新 key 三端点可用。清理含旧 key 的备份：rm -f backend/.env.bak.* deploy/.env.bak.*"
