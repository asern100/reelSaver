#!/bin/bash
# Installs the x86_64 APK, opens each screen in light/dark (+ Arabic), saves screenshots, reports crashes.
PKG=com.asern.reelsaver.app
MAIN=$PKG/com.asern.reelsaver.MainActivity
APK=$(ls app/build/outputs/apk/debug/*x86_64*.apk | head -1)
if ! adb install -r "$APK" > install.txt 2>&1; then
  echo "::error title=install failed::$(tr '\n' ' ' < install.txt)"; exit 1
fi
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb push ci/sample_history.json /data/local/tmp/h.json
adb shell "run-as $PKG sh -c 'mkdir -p files && cp /data/local/tmp/h.json files/history.json'"
adb logcat -c
mkdir -p shots
shot() { sleep 5; adb exec-out screencap -p > "shots/$1.png"; }
screens() {
  adb shell am force-stop $PKG; adb shell am start -W -n $MAIN > /dev/null; shot "main_$1"
  adb shell am force-stop $PKG; adb shell am start -W -n $MAIN --es open history > /dev/null; shot "history_$1"
  adb shell am force-stop $PKG; adb shell am start -W -n $MAIN --es open settings > /dev/null; shot "settings_$1"
}
adb shell cmd uimode night no; adb shell am start -W -n $MAIN > /dev/null; sleep 15; screens light
adb shell cmd uimode night yes; screens dark
adb shell cmd locale set-app-locales $PKG --locales ar
adb shell cmd uimode night no;  screens ar_light
adb shell cmd locale set-app-locales $PKG --locales en
adb logcat -d -b crash > crash.txt
if [ -s crash.txt ]; then
  echo "::error title=crash::$(grep -vE '^\s*$' crash.txt | head -40 | tr '\n' '|' | cut -c1-3500)"; exit 1
fi
echo "::notice title=smoke ok::App installed, all screens opened without crash"
