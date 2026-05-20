# Compile TrafficSafety v2.3 APK on Windows via PowerShell
$ErrorActionPreference = "Stop"

$JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17"
$PATH = "$JAVA_HOME\bin;$env:PATH"
$GRADLE_USER_HOME = "D:\temp\gradle_trafficsafety"

Write-Host "正在編譯 TrafficSafety v2.3..."
Write-Host "JAVA_HOME: $JAVA_HOME"

Set-Location "D:\TrafficSafety"

# Set environment variables
$env:JAVA_HOME = $JAVA_HOME
$env:GRADLE_USER_HOME = $GRADLE_USER_HOME

# Run Gradle
& "$JAVA_HOME\bin\java.exe" -classpath "gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain assembleDebug --no-daemon --stacktrace

if ($LASTEXITCODE -eq 0) {
    if (Test-Path "app\build\outputs\apk\debug\app-debug.apk") {
        Write-Host "編譯成功！"
        Copy-Item "app\build\outputs\apk\debug\app-debug.apk" "D:\temp\TrafficSafety_v2.3.apk" -Force
        Write-Host "APK 已複製到 D:\temp\TrafficSafety_v2.3.apk"
    } else {
        Write-Host "編譯失敗：找不到 APK 檔案"
        exit 1
    }
} else {
    Write-Host "編譯失敗，退出碼: $LASTEXITCODE"
    exit 1
}
