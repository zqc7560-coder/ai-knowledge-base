@echo off
rem ============================================================
rem 启动后端 Spring Boot（:8080）。jar 缺失时自动构建。
rem 凭据由 spring-dotenv 从 backend\.env 读取——必须在 backend 目录下启动。
rem 本文件以 GBK 编码保存，勿另存为 UTF-8
rem ============================================================
title kb-backend
set NO_PROXY=localhost,127.0.0.1
set no_proxy=localhost,127.0.0.1
if not exist "D:\项目\ai-knowledge-base\backend\.env" (
    echo [失败] 缺少 backend\.env（先 cp .env.example backend\.env 并填密钥）
    pause & exit /b 1
)
if not exist "D:\项目\ai-knowledge-base\backend\kb-app\target\kb-app.jar" (
    echo [提示] 未发现 kb-app.jar，先构建（约 1-3 分钟）...
    call "D:\项目\ai-knowledge-base\start\build-backend.bat" || (pause & exit /b 1)
)
cd /d "D:\项目\ai-knowledge-base\backend"
echo ==== 后端启动中：http://localhost:8080 （本窗口含实时日志）====
java -jar kb-app\target\kb-app.jar
echo ==== 后端进程已退出，窗口保留供查看日志 ====
pause
