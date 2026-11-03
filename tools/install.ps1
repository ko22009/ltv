# Builds the debug APK, installs it on the emulator and opens the app on the screen that was open before the update.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = '<JDK 17>'
& '<Android SDK>\gradle-8.7\bin\gradle.bat' -p $root assembleDebug
if ($LASTEXITCODE -ne 0) { throw "build failed: $LASTEXITCODE" }
$adb = '<Android SDK>\platform-tools\adb.exe'
$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
& $adb -s emulator-5554 install -r $apk
if ($LASTEXITCODE -ne 0) { throw "install failed: $LASTEXITCODE" }
& $adb -s emulator-5554 shell am start -n ru.lazytv/.MainActivity --ez restore true
