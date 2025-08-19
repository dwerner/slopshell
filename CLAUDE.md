# Project Notes for Claude

## Important Tasks

### Android Environment
- Always check the Android environment shell script when working on this project
- This ensures proper build environment setup and dependencies are configured

### ADB Connection
- The device is already connected via adb over Wi-Fi
- If reconnection is needed, we should explore the Wi-Fi adb connection process
- This allows wireless debugging and deployment without USB cable

## Deployment Instructions

### Building the App
1. Ensure Android environment is set up properly
2. Build debug APK: `./gradlew assembleDebug`
3. Build release APK: `./gradlew assembleRelease`

### Deploying the App
1. Check connected devices: `adb devices`
2. Install debug build: `./gradlew installDebug`
3. Or manually install APK: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
4. Launch the app: `adb shell am start -n org.connectbot/.ConsoleActivity`