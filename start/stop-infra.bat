@echo off
rem 停止基础设施容器（数据在 volume 中不会丢）
title kb-stop-infra
cd /d "D:\项目\ai-knowledge-base\deploy"
docker compose stop mysql redis pgvector
echo 容器已停止（docker compose start 可原样再起）
pause
