# TrafficSafety App 編譯腳本 (修復版)
$ErrorActionPreference = 'Stop'

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "TrafficSafety App 編譯腳本" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# 設定路徑
$ProjectPath = "D:\TrafficSafety"
$JavaHome = "C:\Program Files\Eclipse Adoptium\jdk-17"

# 檢查 Java
if (-not (Test-Path "$JavaHome\bin\java.exe")) {
    Write-Host "錯誤: 找不到 Java" -ForegroundColor Red
    exit 1
}

# 設定環境變數
$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;" + $env:PATH

Write-Host "Java 版本:" -ForegroundColor Green
& "$JavaHome\bin\java.exe" -version

# 進入專案目錄
Set-Location $ProjectPath

Write-Host ""
Write-Host "開始編譯..." -ForegroundColor Green
Write-Host ""

# 清理並編譯
$buildResult = ./gradlew.bat clean assembleDebug --no-daem 2>&1
$buildResult | Out-File -FilePath build_log.txt -Encoding UTF8

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Green
    Write-Host "編譯成功!" -ForegroundColor Green
    Write-Host "========================================" -ForegroundColor Green
    
    # 顯示 APK 位置
    $apk = Get-ChildItem -Path "app\build\outputs\apk\debug" -Filter "*.apk" | Select-Object -First 1
    if ($apk) {
        Write-Host "APK 位置: $($apk.FullName)" -ForegroundColor Yellow
        Write-Host "檔案大小: $([math]::Round($apk.Length/1MB, 2)) MB" -ForegroundColor Yellow
    }
} else {
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Red
    Write-Host "編譯失敗! 錯誤碼: $LASTEXITCODE" -ForegroundColor Red
    Write-Host "========================================" -ForegroundColor Red
    
    # 顯示錯誤日誌
    if (Test-Path "build_log.txt") {
        Write-Host ""
        Write-Host "=== 錯誤摘要 ===" -ForegroundColor Red
        Get-Content "build_log.txt" -Tail 50
    }
}

Write-Host ""
Write-Host "按任意鍵結束..."
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
