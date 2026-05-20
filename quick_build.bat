@echo off
chcp 65001 > nul
cd /d D:\TrafficSuccess
D:\TrafficSafety\gradlew.bat assembleDebug --no-daemon --stacktrace
if %ERRORLEVEL% == 0 (
    copy D:\TrafficSafety\app\build\outputs\apk\debug\app-debug.apk D:\temp\TrafficSafety_v2.3.apk
    echo BUILD_SUCCESS
) else (
    echo BUILD_FAILED
)
