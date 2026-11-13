#!/usr/bin/env bash
# Stop hook: do not let the session say "done" while the debug build is broken.
# Builds only when app sources are newer than the last debug APK, so chat-only turns cost nothing.
cd "$(dirname "$0")/.." || exit 0
input=$(cat)
case "$input" in *'"stop_hook_active":true'*|*'"stop_hook_active": true'*) exit 0 ;; esac
apk=app/build/outputs/apk/debug/app-debug.apk
if [ -f "$apk" ]; then
  newer=$(find app/src app/build.gradle.kts -type f -newer "$apk" 2>/dev/null | head -1)
  [ -z "$newer" ] && exit 0
fi
export JAVA_HOME="${JAVA_HOME:-/c/Android/jdk17/jdk-17.0.20.1+1}"
out=$(./gradlew.bat assembleDebug -q 2>&1)
if [ $? -ne 0 ]; then
  echo "assembleDebug failed, fix the build before answering:" >&2
  echo "$out" | grep -E "^e:|FAILED|error:" | head -20 >&2
  exit 2
fi
exit 0
