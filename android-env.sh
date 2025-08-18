#!/bin/bash
# Android development environment setup

export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export PATH="$ANDROID_HOME/build-tools/34.0.0:$PATH"

# Use Android Studio's bundled JDK
export JAVA_HOME="$HOME/Development/android-studio/jbr"
export PATH="$JAVA_HOME/bin:$PATH"

echo "Android environment configured:"
echo "  ANDROID_HOME: $ANDROID_HOME"
echo "  JAVA_HOME: $JAVA_HOME"
echo "  Java version: $(java -version 2>&1 | head -n1)"