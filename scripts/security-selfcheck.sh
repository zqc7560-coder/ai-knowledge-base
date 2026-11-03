#!/usr/bin/env bash
# M5.4 安全自查（docs/03 密钥规范落地校验）。可重复执行，全 PASS 才算过。
# 真实密钥从 backend/.env 运行时读取（脚本本身不含明文，符合 L1），逐项验证它没有
# 泄漏到 git 历史 / 前端产物 / 日志 / 镜像层。疑似明文判定：sk- 后 40+ 字符含数字
# （掩码 sk-ws-**** 太短不会误报，真 key 116 字符必命中）。
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
FAIL=0
pass(){ echo "  PASS $1"; }
fail(){ echo "  FAIL $1 ${2:-}"; FAIL=1; }

# 疑似明文密钥正则（ERE）
PAT='sk-[A-Za-z0-9._-]{40,}'

# 1. git 全历史无明文密钥：pickaxe 找增删该串的 commit 数应为 0
CNT=$(git log --all -S "$(grep '^AI_API_KEY=' backend/.env | cut -d= -f2-)" --oneline 2>/dev/null | wc -l | tr -d ' ')
[ "$CNT" = "0" ] && pass "1 git 历史 0 处明文密钥" || fail "1 git 历史出现密钥" "$CNT commits"

# 2. 前端 dist 无明文 & 无上游域名
if [ ! -d frontend/dist ]; then
  echo "  SKIP 2 (无 dist，先 npm run build)"
else
  if grep -rqE "$PAT" frontend/dist 2>/dev/null; then fail "2 dist 含明文密钥"; else pass "2 dist 无明文密钥"; fi
  if grep -rqiE 'dashscope|modelscope|api-inference' frontend/dist 2>/dev/null; then fail "2 dist 含上游域名"; else pass "2 dist 无上游域名"; fi
fi

# 3. 宿主运行日志无明文
if ls logs/*.log >/dev/null 2>&1; then
  if grep -rqE "$PAT" logs/*.log 2>/dev/null; then fail "3 宿主日志含明文"; else pass "3 宿主日志无明文(仅掩码)"; fi
else pass "3 无宿主日志目录"; fi

# 4/5. 容器（若在跑）日志 + 镜像层无明文
if docker ps --format '{{.Names}}' 2>/dev/null | grep -q '^kb-backend$'; then
  if docker logs kb-backend 2>&1 | grep -qE "$PAT"; then fail "4 容器日志含明文"; else pass "4 容器日志无明文"; fi
else pass "4 kb-backend 未运行(跳过容器日志)"; fi

if docker images -q kb-backend:test 2>/dev/null | grep -q .; then
  found=$(docker run --rm --entrypoint sh kb-backend:test -c "grep -rlE '$PAT' /app 2>/dev/null | wc -l" | tr -d '\r')
  [ "$found" = "0" ] && pass "5 镜像层无明文" || fail "5 镜像层含明文" "$found files"
else pass "5 无 kb-backend 镜像(跳过)"; fi

# 6. 唯一 AI 密钥外发者审计（口径=真实不变式，非字面词频）：
#    红线是「AI_API_KEY 只能被 AiClientFactory 拼进外发 Authorization 头」。
#    M10.7 开放API/M11.3 等 legitimately 在注释与【入站头解析】里出现 "Bearer" 字样
#    （解析用户自带的 key 串，与上游密钥无关）——旧版按 grep -rl "Bearer" 判会误报。
#    现按三层核：① 持有者唯一（getenv/@Value AI key 仅一处）；② 外发拼接唯一；③ 其余文件出现 Bearer 不得伴随 AI_API_KEY。
HOLD=$(grep -rl "AI_API_KEY\|ai.api-key" --include='*.java' backend 2>/dev/null | sed 's#.*/##' | sort -u | tr '\n' ',')
[ "$HOLD" = "AiClientFactory.java," ] && pass "6a AI密钥唯一持有 AiClientFactory" || fail "6a AI密钥持有散落" "$HOLD"
WIRE=$(grep -rl '"Bearer " *+\|"Bearer "+' --include='*.java' backend 2>/dev/null | sed 's#.*/##' | sort -u | tr '\n' ',')
[ "$WIRE" = "AiClientFactory.java," ] && pass "6b Bearer外发拼接唯一" || fail "6b Bearer外发散落" "$WIRE"
LEAK=$(grep -rl "Bearer" --include='*.java' backend 2>/dev/null | xargs grep -l "AI_API_KEY" 2>/dev/null | grep -v AiClientFactory | sed 's#.*/##' | sort -u | tr '\n' ',')
[ -z "$LEAK" ] && pass "6c 其余Bearer字样文件均不接触AI密钥" || fail "6c Bearer文件混触AI密钥" "$LEAK"

# 7. .env 被 gitignore（backend/.env 与 deploy/.env 均不应被跟踪）
if git ls-files --error-unmatch backend/.env >/dev/null 2>&1; then fail "7 backend/.env 被跟踪"; else pass "7 .env 未被 git 跟踪"; fi

# 8. 镜像里不存在 .env（.dockerignore 生效）——仅当有镜像
if docker images kb-backend:test -q 2>/dev/null | grep -q .; then
  n=$(docker run --rm --entrypoint sh kb-backend:test -c 'find / -name ".env" 2>/dev/null | grep -v proc | wc -l' | tr -d '\r')
  [ "$n" = "0" ] && pass "8 镜像内无 .env" || fail "8 镜像内有 .env" "$n"
else pass "8 无镜像(跳过镜像内 .env 检查)"; fi

echo
[ "$FAIL" = "0" ] && echo "==== 安全自查 ALL PASS ====" || echo "==== 安全自查存在 FAIL，请修复后重跑 ===="
exit $FAIL
