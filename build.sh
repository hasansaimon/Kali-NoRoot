#!/bin/bash
# Build RootProvider APK in Codespace / CI
set -e

echo "Building RootProvider APK..."
echo "Java: $(java -version 2>&1 | head -1)"

# Use Java 21 via sdkman if available
if [ -d "/usr/local/sdkman/candidates/java/21.0.10-ms" ]; then
    export JAVA_HOME=/usr/local/sdkman/candidates/java/21.0.10-ms
    export PATH=$JAVA_HOME/bin:$PATH
    echo "Using Java 21 via sdkman"
fi

# Install dependencies
sudo apt-get update -qq
sudo apt-get install -y -qq wget unzip xz-utils

# Install Android SDK
export ANDROID_HOME=$HOME/android-sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
export PATH=$ANDROID_HOME/platform-tools:$PATH

if [ ! -d "$ANDROID_HOME" ]; then
    echo "Installing Android SDK..."
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmdline-tools.zip
    unzip -q /tmp/cmdline-tools.zip -d /tmp
    mkdir -p $ANDROID_HOME/cmdline-tools
    mv /tmp/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
    yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null 2>&1 || true
    $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools" > /dev/null 2>&1
    echo "Android SDK installed"
fi

# Run Gradle build
chmod +x gradlew
./gradlew assembleDebug

# Find and show the APK
APK=$(find . -name "*.apk" -path "*/debug/*" -path "*/app/*" | head -1)
if [ -f "$APK" ]; then
    echo ""
    echo "========================================"
    echo "BUILD SUCCESSFUL!"
    echo "APK: $APK"
    echo "Size: $(du -h "$APK" | cut -f1)"
    echo "========================================"
    mkdir -p output
    cp "$APK" output/
    echo "Copied to output/$(basename "$APK")"
else
    APK=$(find . -name "*.apk" | head -1)
    if [ -f "$APK" ]; then
        echo "BUILD SUCCESSFUL (APK found at alternate location)"
        echo "APK: $APK"
    else
        echo "BUILD FAILED - no APK found"
        exit 1
    fi
fi
