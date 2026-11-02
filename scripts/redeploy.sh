#!/bin/bash
# 一键：停旧实例 -> 打包(跳测试或全量) -> 后台起服务 -> 健康检查
set -e
cd "$(dirname "$0")/.."
MODE=${1:-skip}
# kill any java serving 8080
PID=$(netstat -ano | grep ':8080 ' | grep LISTENING | awk '{print $5}' | sort -u | head -1 || true)
if [ -n "$PID" ]; then powershell -NoProfile -Command "Stop-Process -Id $PID -Force" 2>/dev/null || true; sleep 2; fi
MVN_HOME="E:\\develop\\apache-maven-3.9.4"
cd backend
ARGS="-DskipTests"; [ "$MODE" = "full" ] && ARGS=""
java -cp "$MVN_HOME/boot/plexus-classworlds-2.7.0.jar" \
  "-Dclassworlds.conf=$MVN_HOME/bin/m2.conf" "-Dmaven.home=$MVN_HOME" \
  "-Dmaven.multiModuleProjectDirectory=$(cygpath -w $PWD)" \
  org.codehaus.plexus.classworlds.launcher.Launcher -q $ARGS package
rm -f logs/../../logs/kb-run.log 2>/dev/null || true
# spring-dotenv 从工作目录读 .env —— 必须在 backend/ 下启动（.env 在此），不能 cd 到 target
( cd "D:/项目/ai-knowledge-base/backend" && java -jar kb-app/target/kb-app.jar > "D:/项目/ai-knowledge-base/logs/kb-app-$(date +%s).log" 2>&1 & echo $! > "D:/项目/ai-knowledge-base/logs/kb.pid" )
cd /d/项目/ai-knowledge-base
for i in $(seq 1 30); do
  sleep 2
  if curl -s --noproxy '*' --max-time 3 http://127.0.0.1:8080/api/ping | grep -q '"code":0'; then echo "SERVER_UP after $((i*2))s"; exit 0; fi
done
echo "SERVER_TIMEOUT"; tail -20 logs/kb-app-*.log 2>/dev/null | tail -8; exit 1
