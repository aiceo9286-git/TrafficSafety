
@echo off
chcp 65001 > nul
setlocal

set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17
set PATH=%JAVA_HOME%in;%PATH%

cd /d D:\TrafficSafety

echo 🔨 Building TrafficSafety APK...
echo.

call gradlew.bat clean assembleDebug --no-daemon 2>&1

if %ERRORLEVEL% neq 0 (
    echo Build FAILED!
    exit /b 1
)

echo.
echo ✅ Build SUCCESS!
copy /Y "appuild\outputspk\debugpp-debug.apk" "TrafficSafety.apk" > nul
echo APK copied to: D:\TrafficSafety\TrafficSafety.apk
