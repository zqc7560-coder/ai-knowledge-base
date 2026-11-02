@echo off
cd /d D:\项目i-knowledge-baseackend
call D:\项目i-knowledge-base\scripts\mvn.cmd -q -DskipTests package
echo MVN_RC=%ERRORLEVEL%
