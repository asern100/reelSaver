#!/bin/bash
# End-to-end check of "remove music" on the emulator: share a speech+music test video to
# "ReelSaver 🔇 No music", wait for the result, and measure how much music is left.
PKG=com.asern.reelsaver.app
T=nmtest
adb push $T/nm_test.mp4 /data/local/tmp/nm_test.mp4 > /dev/null
adb shell "run-as $PKG sh -c 'mkdir -p cache/ci && cp /data/local/tmp/nm_test.mp4 cache/ci/nm_test.mp4'"
adb logcat -c
adb shell cmd uimode night no
START=$(date +%s)
adb shell "run-as $PKG ls -la cache/ci" | sed 's/^/cache: /'
adb shell am start -W -a android.intent.action.SEND -t video/mp4 \
  --eu android.intent.extra.STREAM "file:///data/user/0/$PKG/cache/ci/nm_test.mp4" \
  -n $PKG/com.asern.reelsaver.QuickNoMusic
sleep 20; adb shell cmd statusbar expand-notifications; sleep 3
adb exec-out screencap -p > shots/nm_progress.png; adb shell cmd statusbar collapse
ok=0
for i in $(seq 1 60); do
  if adb shell "run-as $PKG cat files/history.json" 2>/dev/null | grep -q "no music"; then ok=1; break; fi
  sleep 10
done
SECS=$(( $(date +%s) - START ))
adb shell cmd statusbar expand-notifications; sleep 3
adb exec-out screencap -p > shots/nm_done.png; adb shell cmd statusbar collapse
adb logcat -d -b crash > nm_crash.txt
if [ -s nm_crash.txt ]; then
  echo "::error title=music removal crash::$(grep -vE '^\s*$' nm_crash.txt | head -40 | tr '\n' '|' | cut -c1-3500)"; exit 1
fi
if [ $ok != 1 ]; then
  adb logcat -d | grep -E "ReelSaver|onnxruntime|AndroidRuntime|QuickNoMusic|DownloadService|ForegroundService" | tail -40 > nm_log.txt
  cat nm_log.txt
  echo "::error title=music removal did not finish::after ${SECS}s | $(tr '\n' '|' < nm_log.txt | cut -c1-3000)"; exit 1
fi
OUT=$(adb shell "ls /sdcard/Download/ReelSaver/" | tr -d '\r' | grep "no music" | head -1)
adb pull "/sdcard/Download/ReelSaver/$OUT" $T/result.mp4 > /dev/null
read BEFORE AFTER < <(python3 ci/nm_mix.py score $T $T/result.mp4)
MSG="clean-speech SDR: with music ${BEFORE} dB -> after removal ${AFTER} dB, ${SECS}s incl. 67 MB model download"
if python3 -c "import sys; sys.exit(0 if float('$AFTER') >= 10 else 1)"; then
  echo "::notice title=music removal ok::$MSG"
else
  echo "::error title=music removal too weak::$MSG"; exit 1
fi
