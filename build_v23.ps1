# Build TrafficSafety v2.3 APK
$ErrorActionPreference = 'Stop'
chcp 65001

$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17'
$env:GRADLE_USER_HOME = 'D:\temp\gradle_trafficsafety'

Write-Host "Building TrafficSafety v2.3..."
Write-Host "JAVA_HOME: $env:JAVA_HOME"

Set-Location 'D:\TrafficSafety'

# Run Gradle
.\gradlew.bat assembleDebug --no-daemon

if ($LASTEXITCODE -eq 0) {
    if (Test-Path 'app\build\outputs\apk\debug\app-debug.apk') {
        Write-Host "BUILD SUCCESS!"
        Copy-Item 'app\build\outputs\apk\debug\app-debug.apk' 'D:\temp\TrafficSafety_v2.3.apk' -Force
        Write-Host "APK copied to D:\temp\TrafficSafety_v2.3.apk"
    } else {
        Write-Host "APK file not found"
        exit 1
    }
} else {
    Write-Host "BUILD FAILED with exit code $LASTEXITCODE"
    exit 1
}
