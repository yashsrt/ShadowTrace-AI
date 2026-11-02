@echo off
cd /d "%~dp0"
where java >nul 2>&1
if errorlevel 1 (echo Install Java JDK 17 or newer and add it to PATH.& pause& exit /b 1)
where javac >nul 2>&1
if errorlevel 1 (echo JDK required: javac not found.& pause& exit /b 1)
if not exist out mkdir out
javac -encoding UTF-8 -d out src\ShadowTraceAI.java
if errorlevel 1 (pause& exit /b 1)
echo Open http://localhost:8080 in your browser.
java -cp out ShadowTraceAI
pause
