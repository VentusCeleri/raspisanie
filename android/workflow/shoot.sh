#!/usr/bin/env bash
# Screenshots for RuStore: run inside android-emulator-runner.
set -euo pipefail
mkdir -p shots
adb install -r android/build/outputs/apk/debug/*-debug.apk
adb shell settings put global sysui_demo_allowed 1
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" > /dev/null; }
demo enter
demo clock -e hhmm 0941
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4
demo notifications -e visible false
shot() { # name theme appearance [extra am args]
  local name=$1 theme=$2 appearance=$3; shift 3
  adb shell am force-stop ru.ventusceleri.pary
  adb shell am start -n ru.ventusceleri.pary/.MainActivity --ei groupId 9209 --es theme "$theme" --es appearance "$appearance" "$@" > /dev/null
  sleep 15
  adb exec-out screencap -p > "shots/$name.png"
}
shot 1-main-sand-light sand light
shot 2-main-ocean-dark ocean dark
shot 3-settings-lilac-dark lilac dark --ez openSettings true
shot 4-main-forest-light forest light
ls -la shots
