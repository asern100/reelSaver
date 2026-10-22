#!/bin/bash
# Installs the x86_64 APK on the emulator, opens the app and reports any crash as a CI annotation.
APK=$(ls app/build/outputs/apk/debug/*x86_64*.apk | head -1)
if ! adb install -r "$APK" > install.txt 2>&1; then
  echo "::error title=install failed::$(tr '\n' ' ' < install.txt)"; exit 1
fi
adb logcat -c
adb shell am start -W -n com.asern.reelsaver/.MainActivity
sleep 20
adb logcat -d -b crash > crash.txt
adb logcat -d | grep -iE "AndroidRuntime|FATAL|reelsaver|youtubedl" | tail -60 > log.txt
if [ -s crash.txt ]; then
  echo "::error title=crash::$(grep -vE '^\s*$' crash.txt | head -40 | tr '\n' '|' | cut -c1-3500)"; exit 1
fi
if ! adb shell pidof com.asern.reelsaver > /dev/null; then
  echo "::error title=not running::$(tr '\n' '|' < log.txt | cut -c1-3500)"; exit 1
fi
echo "::notice title=smoke ok::App installed and running"
