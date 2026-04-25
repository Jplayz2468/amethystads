@echo off
setlocal
set "ROOT=%~dp0"
set "JAVA_HOME=%ROOT%..\jdk"
set "GRADLE_USER_HOME=%ROOT%..\.gradle"
set "PATH=%JAVA_HOME%\bin;%ROOT%..\gradle\bin;%PATH%"

call gradle.bat --no-daemon build
