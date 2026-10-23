#!/bin/bash
# Installs the published arm64 APK (exact file users download) and reports the real installer error.
gh release download "$TAG" -R "$REPO" -p '*arm64*' -O rel.apk || { echo "::error::download failed"; exit 1; }
adb shell getprop ro.product.cpu.abilist
if adb install rel.apk > out.txt 2>&1; then
  adb shell am start -W -n com.asern.reelsaver.app/com.asern.reelsaver.MainActivity; sleep 15
  adb logcat -d -b crash > crash.txt
  if [ -s crash.txt ]; then echo "::error title=crash::$(head -40 crash.txt | tr '\n' '|' | cut -c1-3500)"; exit 1; fi
  echo "::notice title=arm64 install ok::$(tr '\n' ' ' < out.txt)"
else
  echo "::error title=arm64 install failed::$(tr '\n' ' ' < out.txt | cut -c1-3000)"
  exit 1
fi
