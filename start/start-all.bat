@echo off
rem ============================================================
rem 一键全启：基础设施(阻塞至就绪) -> 后端窗口 -> 前端窗口 -> 打开浏览器
rem 单独启动某服务请直接双击对应的 run-*.bat
rem 本文件以 GBK 编码保存，勿另存为 UTF-8
rem ============================================================
title kb-start-all
call "D:\项目\ai-knowledge-base\start\run-infra.bat" || exit /b 1
start "kb-backend" cmd /k "D:\项目\ai-knowledge-base\start\run-backend.bat"
start "kb-frontend" cmd /k "D:\项目\ai-knowledge-base\start\run-frontend.bat"
echo 等待后端健康（最长约 120 秒）...
setlocal EnableDelayedExpansion
set RETRY=0
:probe
ping -n 4 127.0.0.1 >nul
set /a RETRY+=1
curl -s --noproxy "*" -m 5 http://127.0.0.1:8080/api/ping | findstr code >nul && goto up
if !RETRY! LSS 40 goto probe
echo [警告] 后端 120 秒内未就绪，请查看 kb-backend 窗口的报错日志
pause & exit /b 1
:up
endlocal
echo 后端已就绪，打开前端页面 http://localhost:5173
start "" http://localhost:5173
echo 全部完成。停止服务请运行 stop-all.bat
ping -n 6 127.0.0.1 >nul
