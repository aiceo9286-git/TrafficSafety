$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Write-Host "JAVA_HOME set to: $env:JAVA_HOME"
Set-Location "D:\TrafficSafety"
Write-Host "Current location: $(Get-Location)"
.\gradlew.bat clean assembleDebug --no-daemon
