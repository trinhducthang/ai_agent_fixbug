@echo off
rem Build (nếu cần) và chạy Jira AI Fixer tại http://127.0.0.1:8099
chcp 65001 >nul
cd /d "%~dp0"
if not exist target\jira-ai-fixer-0.1.0.jar (
  call mvn -q -B -DskipTests package || exit /b 1
)
java -jar target\jira-ai-fixer-0.1.0.jar %*
