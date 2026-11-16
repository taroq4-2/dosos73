#!/usr/bin/env bash
set -euo pipefail

apk=${1:?Path to the checked signed APK is required}
package=com.os73.dosos73
mkdir -p captures
sha256sum "$apk" > captures/apk-sha256.txt
adb shell settings put global hide_error_dialogs 1
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell wm size 1080x2400
adb shell wm density 420
adb install "$apk"
adb shell pm grant "$package" android.permission.POST_NOTIFICATIONS

capture_home() {
    local language=$1 label=$2 name="home-$1"
    adb shell cmd locale set-app-locales "$package" --user 0 --locales "$language"
    adb shell am force-stop "$package"
    adb shell am start -W -n "$package/.MainActivity"
    for attempt in {1..6}; do
        if adb shell uiautomator dump /sdcard/dosos-window.xml &&
            adb pull /sdcard/dosos-window.xml "captures/$name.xml" &&
            grep -Fq "package=\"$package\"" "captures/$name.xml" &&
            grep -Fq "$label" "captures/$name.xml"; then
            adb exec-out screencap -p > "captures/$name.png"
            return
        fi
        adb shell am start -W -n "$package/.MainActivity"
        sleep 3
    done
    adb logcat -d -s AndroidRuntime ActivityTaskManager > "captures/$name-startup.log"
    return 1
}

capture_home ar 'تحميل الوسائط'
capture_home en 'Download media'
