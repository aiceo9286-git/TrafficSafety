@echo off
chcp 65001 > nul
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "GRADLE_USER_HOME=D:\temp\gradle_cache"

cd /d D:\TrafficSafety

"%JAVA_HOME%\bin\java.exe" -classpath "gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain assembleDebug --no-daemon 2>&1

echo %ERRORLEVEL%
