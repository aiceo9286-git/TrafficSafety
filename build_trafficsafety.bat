@echo off
chcp 65001 >nul
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d D:\TrafficSafety

echo ========================================
echo TrafficSafety v2.5.3 Build (修復版)
echo ========================================
echo.

call gradlew.bat clean assembleDebug --no-daemon -x test

if %ERRORLEVEL% equ 0 (
    echo.
    echo ========================================
    echo 編譯成功!
    echo ========================================
    if exist "app\build\outputs\apk\debug\app-debug.apk" (
        copy /Y "app\build\outputs\apk\debug\app-debug.apk" "v2.5.3_fix.apk"
        echo APK 已複製到 v2.5.3_fix.apk
    )
) else (
    echo ========================================
    echo 編譯失敗! 錯誤碼: %ERRORLEVEL%
    echo ========================================
)
pause
