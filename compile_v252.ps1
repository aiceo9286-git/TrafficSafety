$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17'
$env:GRADLE_USER_HOME = 'D:\temp\gradle_trafficsafety'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

cd 'D:\TrafficSafety'

Write-Host "=== Starting TrafficSafety v2.5.2 Build ==="

# Clean and build
.\gradlew.bat clean assembleDebug --no-daemon --offline

if ($LASTEXITCODE -eq 0) {
    Write-Host "Build successful!"
    
    $source = "D:\TrafficSafety\app\build\outputs\apk\debug\app-debug.apk"
    $dest = "D:\temp\TrafficSafety_v2.5.2.apk"
    
    if (Test-Path $source) {
        Copy-Item $source $dest -Force
        Write-Host "APK copied to: $dest"
        Write-Host "BUILD_SUCCESS"
    } else {
        Write-Host "APK file not found"
        exit 1
    }
} else {
    Write-Host "Build failed"
    exit 1
}
