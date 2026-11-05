#!/usr/bin/env bash
set -euo pipefail

result_dir="$PWD/android/app/build/reports/emulator-smoke"
mkdir -p "$result_dir"
export DOMTRACK_SMOKE_DIR="$result_dir"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
export ANDROID_USER_HOME="$RUNNER_TEMP/domtrack-android"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
mkdir -p "$ANDROID_AVD_HOME"

"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install "emulator" "system-images;android-36;google_apis;x86_64" > "$result_dir/sdk-install.log" 2>&1
if [ -e /dev/kvm ]; then
  sudo chown "$(id -u):$(id -g)" /dev/kvm
fi
echo no | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd --force --name DomTrackSmoke --path "$ANDROID_AVD_HOME/DomTrackSmoke.avd" --package "system-images;android-36;google_apis;x86_64" --device pixel_7 > "$result_dir/avd.log" 2>&1
test -f "$ANDROID_AVD_HOME/DomTrackSmoke.avd/config.ini"
if [ ! -f "$ANDROID_AVD_HOME/DomTrackSmoke.ini" ]; then
  printf 'avd.ini.encoding=UTF-8\npath=%s\ntarget=android-36\n' "$ANDROID_AVD_HOME/DomTrackSmoke.avd" > "$ANDROID_AVD_HOME/DomTrackSmoke.ini"
fi
emulator -list-avds > "$result_dir/avd-list.txt"
if ! grep -Fxq DomTrackSmoke "$result_dir/avd-list.txt"; then
  echo '::error::Created Android virtual device is not visible to emulator'
  exit 1
fi
emulator -avd DomTrackSmoke -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader -memory 2048 -camera-back none -camera-front none > "$result_dir/emulator.log" 2>&1 &
emulator_pid=$!
trap 'adb emu kill >/dev/null 2>&1 || true; kill "$emulator_pid" >/dev/null 2>&1 || true' EXIT
timeout 120 adb wait-for-device
booted=0
for attempt in $(seq 1 120); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then booted=1; break; fi
  sleep 2
done
if [ "$booted" != 1 ]; then echo '::error::Android 16 emulator did not finish booting'; exit 1; fi
adb shell input keyevent 82
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb install android/app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am start -W -n ua.domtrack.mobile/.MainActivity > "$result_dir/startup.txt"
sleep 5

python3 - <<'PY'
import os, re, subprocess, time, xml.etree.ElementTree as ET
from pathlib import Path

out = Path(os.environ['DOMTRACK_SMOKE_DIR'])
def adb(*args):
    return subprocess.check_output(['adb', *args], timeout=30)
def capture(name):
    adb('shell', 'uiautomator', 'dump', '/sdcard/domtrack-smoke.xml')
    raw = adb('exec-out', 'cat', '/sdcard/domtrack-smoke.xml')
    (out / (name + '.xml')).write_bytes(raw)
    (out / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
    return ET.fromstring(raw)
def tap(label):
    tree = capture('before-tap')
    for node in tree.iter('node'):
        if node.get('text', '').casefold() == label.casefold():
            bounds = [int(n) for n in re.findall(r'\d+', node.get('bounds', ''))]
            if len(bounds) != 4:
                continue
            adb('shell', 'input', 'tap', str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))
            time.sleep(1)
            return
    raise AssertionError('Missing native UI control: ' + label)
try:
    tree = capture('01-search')
    if not any(n.get('text') == 'DomTrack UA' for n in tree.iter('node')):
        raise AssertionError('Native main screen did not appear')
    tap('Фильтры')
    capture('02-filters')
    adb('shell', 'input', 'keyevent', '4')
    time.sleep(1)
    for label, name in [('Избранное','03-favorites'),('События','04-events'),('Ещё','05-more'),('Поиск','06-search-again')]:
        tap(label)
        capture(name)
    adb('shell', 'pidof', 'ua.domtrack.mobile')
    crashes = adb('logcat', '-b', 'crash', '-d').decode(errors='replace')
    (out / 'crashes.txt').write_text(crashes)
    if 'FATAL EXCEPTION' in crashes:
        raise AssertionError('Android runtime crash: ' + crashes[-3000:])
    (out / 'RESULT.txt').write_text('PASS: Android 16 cold start, filter dialog, four native tabs, no process crash. No physical Samsung/battery test.\n')
    print('Android 16 native startup and navigation: PASS')
except Exception as error:
    message = str(error).replace('%','%25').replace('\r','%0D').replace('\n','%0A')
    print('::error title=Android runtime smoke::' + message)
    raise
PY
