@echo off
rem ============================================================
rem 构建后端 jar（E:\develop 的 mvn.cmd 已损坏，直调 classworlds 启动器）。
rem 用法: build-backend.bat [skipTests默认 | tests 跑单测]
rem 本文件以 GBK 编码保存，勿另存为 UTF-8
rem ============================================================
title kb-build
set MVN_HOME=E:\develop\apache-maven-3.9.4
set PROJ=D:\项目\ai-knowledge-base\backend
cd /d "%PROJ%"
set ARGS=-DskipTests
if /i "%~1"=="tests" set ARGS=
echo 构建中（日志在本窗口）...
java -cp "%MVN_HOME%\boot\plexus-classworlds-2.7.0.jar" -Dclassworlds.conf="%MVN_HOME%\bin\m2.conf" -Dmaven.home="%MVN_HOME%" -Dmaven.multiModuleProjectDirectory="%PROJ%" org.codehaus.plexus.classworlds.launcher.Launcher -q %ARGS% package
if errorlevel 1 (
    echo [失败] 构建未通过
    exit /b 1
)
echo [成功] kb-app\target\kb-app.jar 已就绪
exit /b 0
