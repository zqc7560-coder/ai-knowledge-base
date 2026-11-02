@echo off
rem ============================================================
rem 停止本项目的前后端/中继进程。
rem 按命令行特征过滤杀进程（绝按进程名杀，避免误伤其它 java/node）：
rem   java  且命令行含 kb-app.jar
rem   node  且命令行含 ai-knowledge-base\frontend
rem   python 且命令行含 relay_proxy.py / kb_relay.py（开发期代理中继）
rem 不动 Docker 容器：停基础设施用 stop-infra.bat
rem 本文件以 GBK 编码保存，勿另存为 UTF-8
rem ============================================================
title kb-stop-all
powershell -NoProfile -ExecutionPolicy Bypass -File "D:\项目\ai-knowledge-base\start\stop-procs.ps1"
echo 已停止前端/后端进程（若无匹配进程属正常）。基础设施容器如不需要请跑 stop-infra.bat
pause
