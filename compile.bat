@echo off
setlocal
set "ROOT=%~dp0"
set "JAVA_HOME=%ROOT%..\jdk"
set "GRADLE_USER_HOME=%ROOT%..\.gradle"
set "PATH=%JAVA_HOME%\bin;%ROOT%..\gradle\bin;%PATH%"

call gradle.bat --no-daemon build
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" (
  echo Build failed.
  exit /b %RC%
)

if not exist "%ROOT%..\server\plugins" mkdir "%ROOT%..\server\plugins"
if exist "%ROOT%..\server\plugins\amethystads-plugin.jar" del /F /Q "%ROOT%..\server\plugins\amethystads-plugin.jar"
if exist "%ROOT%..\server\plugins\.paper-remapped\amethystads-plugin.jar" del /F /Q "%ROOT%..\server\plugins\.paper-remapped\amethystads-plugin.jar"
if exist "%ROOT%..\server\plugins\.paper-remapped\index.json" del /F /Q "%ROOT%..\server\plugins\.paper-remapped\index.json"
if exist "%ROOT%..\server\plugins\.paper-remapped\unknown-origin\index.json" del /F /Q "%ROOT%..\server\plugins\.paper-remapped\unknown-origin\index.json"
if exist "%ROOT%..\server\plugins\.paper-remapped\extra-plugins\index.json" del /F /Q "%ROOT%..\server\plugins\.paper-remapped\extra-plugins\index.json"
copy /Y "%ROOT%build\libs\amethystads-plugin.jar" "%ROOT%..\server\plugins\amethystads-plugin.jar" >nul
if errorlevel 1 (
  echo Copy failed.
  exit /b 1
)
echo.
echo Built: server\plugins\amethystads-plugin.jar
