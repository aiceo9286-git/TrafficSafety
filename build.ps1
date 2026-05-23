# TrafficSafety App 編譯腳本
$ErrorActionPreference = 'Stop'

$ProjectPath = "D:\TrafficSafety"
$JavaHome = "C:\Program Files\Eclipse Adoptium\jdk-17"

# 設定環境
$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$env:PATH"

Write-Host "Java 版本:" -ForegroundColor Green
& "$JavaHome\bin\java.exe" -version 2&1

Set-Location $ProjectPath

Write-Host ""
Write-Host "開始編譯 TrafficSafety v2.5.2..." -ForegroundColor Cyan

# 清理並編譯
$buildOutput = .\gradlew.bat clean assembleDebug --no-daemon 2&1
$exitCode = $LASTEXITCODE

$buildOutput | Out-File -FilePath "D:\TrafficSafety\build_log.txt" -Encoding UTF8

if ($exitCode -eq 0) {
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Green
    Write-Host "✅ 編譯成功!" -ForegroundColor Green
    Write-Host "========================================" -ForegroundColor Green
    
    $apk = Get-ChildItem -Path "app\build\outputs\apk\debug" -Filter "*.apk" | Select-Object -First 1
    if ($apk) {
        Write-Host "APK: $($apk.FullName)" -ForegroundColor Yellow
        Write-Host "大小: $([math]::Round($apk.Length/1MB, 2)) MB" -ForegroundColor Yellow
    }
} else {
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Red
    Write-Host "❌ 編譯失敗!" -ForegroundColor Red
    Write-Host "========================================" -ForegroundColor Red
    Get-Content "D:\TrafficSafety\build_log.txt" -Tail 100
}
