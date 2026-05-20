#!/bin/bash
# WSL compile script for TrafficSafety
export JAVA_HOME="/mnt/c/Program Files/Eclipse Adoptium/jdk-17"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="/mnt/d/temp/gradle_trafficsafety"

cd /mnt/d/TrafficSafety

# Run gradlew via Windows
"/mnt/c/Program Files/Eclipse Adoptium/jdk-17/bin/java.exe" \
  -classpath "gradle/wrapper/gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain \
  assembleDebug --no-daemon --stacktrace

if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
    echo "Build successful!"
    cp "app/build/outputs/apk/debug/app-debug.apk" "/mnt/d/temp/TrafficSafety_v2.3.apk"
else
    echo "Build failed"
fi
