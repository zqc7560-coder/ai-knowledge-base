@echo off
rem ============================================================
rem 启动基础设施容器（mysql/redis/pgvector，compose 默认三件套）
rem 本文件以 GBK 编码保存，勿另存为 UTF-8
rem ============================================================
title kb-infra
docker info >nul 2>&1
if errorlevel 1 (
    echo [提示] Docker 引擎未就绪，尝试拉起 Docker Desktop...
    if exist "C:\Program Files\Docker\Docker\Docker Desktop.exe" start "" "C:\Program Files\Docker\Docker\Docker Desktop.exe"
    echo 等待引擎就绪（最长约 150 秒）...
    setlocal EnableDelayedExpansion
    set RETRY=0
    :waitloop
    ping -n 6 127.0.0.1 >nul
    set /a RETRY+=1
    docker info >nul 2>&1 && goto ready
    if !RETRY! LSS 30 goto waitloop
    echo [失败] Docker 引擎未就绪，请手动打开 Docker Desktop 后重试
    exit /b 1
    :ready
    endlocal
)
cd /d "D:\项目\ai-knowledge-base\deploy"
docker compose up -d mysql redis pgvector
if errorlevel 1 ( echo [失败] 基础设施启动失败 & pause & exit /b 1 )
echo 基础设施已启动：mysql 127.0.0.1:3307 / redis :6380 / pgvector :5433
