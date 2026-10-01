#!/usr/bin/env bash
# Capture screenshots and diagnostics in one shell before applying UI health gates.
set +e

REVIEW_DIR="visual-review"
PACKAGE="com.jepongdevxyz.browser"
mkdir -p "$REVIEW_DIR"

adb shell wm size 1536x550
adb shell wm density 160
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n "$PACKAGE/.MainActivity"
sleep 20
adb shell dumpsys window > "$REVIEW_DIR/desktop-window.txt" 2>&1
adb shell pidof "$PACKAGE" > "$REVIEW_DIR/desktop-pid.txt" 2>&1
adb shell uiautomator dump /sdcard/window.xml > "$REVIEW_DIR/desktop-uiautomator.txt" 2>&1
adb shell cat /sdcard/window.xml > "$REVIEW_DIR/desktop-window.xml" 2>&1
if grep -Fq "System UI isn't responding" "$REVIEW_DIR/desktop-window.xml"; then
  adb shell input keyevent KEYCODE_DPAD_DOWN
  adb shell input keyevent KEYCODE_ENTER
  sleep 10
  adb shell uiautomator dump /sdcard/window.xml > "$REVIEW_DIR/desktop-uiautomator.txt" 2>&1
  adb shell cat /sdcard/window.xml > "$REVIEW_DIR/desktop-window.xml" 2>&1
fi
adb exec-out screencap -p > "$REVIEW_DIR/devxyz-desktop-reference.png" 2> "$REVIEW_DIR/desktop-screencap.txt"
adb logcat -d -v time > "$REVIEW_DIR/desktop-logcat.txt" 2>&1
adb shell dumpsys activity activities > "$REVIEW_DIR/desktop-activity.txt" 2>&1

adb shell wm size 720x1600
adb shell wm density 320
adb shell am force-stop "$PACKAGE"
adb shell am start -n "$PACKAGE/.MainActivity"
sleep 20
adb shell dumpsys window > "$REVIEW_DIR/phone-window.txt" 2>&1
adb shell pidof "$PACKAGE" > "$REVIEW_DIR/phone-pid.txt" 2>&1
adb shell uiautomator dump /sdcard/phone-window.xml > "$REVIEW_DIR/phone-uiautomator.txt" 2>&1
adb shell cat /sdcard/phone-window.xml > "$REVIEW_DIR/phone-window.xml" 2>&1
if grep -Fq "System UI isn't responding" "$REVIEW_DIR/phone-window.xml"; then
  adb shell input keyevent KEYCODE_DPAD_DOWN
  adb shell input keyevent KEYCODE_ENTER
  sleep 10
  adb shell uiautomator dump /sdcard/phone-window.xml > "$REVIEW_DIR/phone-uiautomator.txt" 2>&1
  adb shell cat /sdcard/phone-window.xml > "$REVIEW_DIR/phone-window.xml" 2>&1
fi
adb exec-out screencap -p > "$REVIEW_DIR/devxyz-phone-reference.png" 2> "$REVIEW_DIR/phone-screencap.txt"
adb logcat -d -v time > "$REVIEW_DIR/phone-logcat.txt" 2>&1
adb shell dumpsys activity activities > "$REVIEW_DIR/phone-activity.txt" 2>&1

python3 scripts/verify_emulator_screen.py "$REVIEW_DIR/desktop-window.xml"
desktop_xml_status=$?
python3 scripts/verify_emulator_screen.py "$REVIEW_DIR/phone-window.xml"
phone_xml_status=$?
desktop_foreground_status=0
phone_foreground_status=0
grep -q "$PACKAGE" "$REVIEW_DIR/desktop-window.txt" || desktop_foreground_status=1
grep -q "$PACKAGE" "$REVIEW_DIR/phone-window.txt" || phone_foreground_status=1
test -s "$REVIEW_DIR/devxyz-desktop-reference.png" || desktop_foreground_status=1
test -s "$REVIEW_DIR/devxyz-phone-reference.png" || phone_foreground_status=1
test -s "$REVIEW_DIR/desktop-pid.txt" || desktop_foreground_status=1
test -s "$REVIEW_DIR/phone-pid.txt" || phone_foreground_status=1

if [ "$desktop_xml_status" -ne 0 ] || [ "$phone_xml_status" -ne 0 ] || \
   [ "$desktop_foreground_status" -ne 0 ] || [ "$phone_foreground_status" -ne 0 ]; then
  echo "Visual review gate failed; screenshots and diagnostics are saved under $REVIEW_DIR/"
  exit 1
fi
