@echo off
chcp 65001 > nul
echo Building TrafficSafety...
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17
set GRADLE_USER_HOME=D:\temp\gradle_trafficsafety
cd /d D:\TrafficSafety

"%JAVA_HOME%\bin\java.exe" -classpath "gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain assembleDebug --no-daemon

echo Exit code: %ERRORLEVEL%

if %ERRORLEVEL% == 0 (
    echo BUILD SUCCESS
    copy "app\build\outputs\apk\debug\app-debug.apk" "D:\temp\TrafficSafety_v2.3.apk" /Y
) else (
    echo BUILD FAILED
)
