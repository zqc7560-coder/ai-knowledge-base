@echo off
rem ============================================================
rem 启动前端 Vite 开发服务器（:5173，/api 自动代理到后端 :8080）
rem 本文件以 GBK 编码保存，勿另存为 UTF-8
rem ============================================================
title kb-frontend
set NO_PROXY=localhost,127.0.0.1
set no_proxy=localhost,127.0.0.1
cd /d "D:\项目\ai-knowledge-base\frontend"
if not exist node_modules (
    echo [提示] 首次运行，安装依赖（npmmirror 源）...
    call npm install --no-audit --no-fund || (echo [失败] npm install & pause & exit /b 1)
)
echo ==== 前端启动中：http://localhost:5173 ====
call npm run dev
pause
