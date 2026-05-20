@echo off
chcp 65001 > nul
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "GRADLE_USER_HOME=D:\temp\gradle_trafficsafety"

echo 正在編譯汽車安全助理...
cd /d D:\TrafficSafety

"%JAVA_HOME%\bin\java.exe" -classpath "gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain assembleDebug --no-daemon --stacktrace

if exist "app\build\outputs\apk\debug\app-debug.apk" (
    echo 編譯成功！
    copy "app\build\outputs\apk\debug\app-debug.apk" "D:\temp\TrafficSafety_v2.apk"
) else (
    echo 編譯失敗
)
pause