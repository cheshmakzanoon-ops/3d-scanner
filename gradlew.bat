@echo off
setlocal
set "APP_HOME=%~dp0"
if exist "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" goto run
powershell -NoProfile -ExecutionPolicy Bypass -File "%APP_HOME%tools\bootstrap-wrapper.ps1"
if errorlevel 1 exit /b 1
:run
if defined JAVA_HOME (set "JAVA_EXE=%JAVA_HOME%\bin\java.exe") else (set "JAVA_EXE=java.exe")
"%JAVA_EXE%" -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
