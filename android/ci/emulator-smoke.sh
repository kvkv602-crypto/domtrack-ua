#!/usr/bin/env bash
set -euo pipefail

result_dir="$PWD/android/app/build/reports/emulator-smoke"
mkdir -p "$result_dir"
export DOMTRACK_SMOKE_DIR="$result_dir"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
export ANDROID_USER_HOME="$RUNNER_TEMP/domtrack-android"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
mkdir -p "$ANDROID_AVD_HOME"

# Reuse the runner's working emulator instead of downloading an unrelated update.
install_sdk() {
  for attempt in 1 2 3; do
    if "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install "$@" >> "$result_dir/sdk-install.log" 2>&1; then return 0; fi
    sleep 5
  done
  tail -n 35 "$result_dir/sdk-install.log"
  echo '::error title=Android SDK download::SDK package could not be installed after three attempts'
  return 1
}
if ! "$ANDROID_HOME/emulator/emulator" -version > "$result_dir/emulator-version.txt" 2>&1; then
  install_sdk "emulator"
fi
if [ ! -f "$ANDROID_HOME/system-images/android-36/google_apis/x86_64/package.xml" ]; then
  install_sdk "system-images;android-36;google_apis;x86_64"
fi
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
adb install android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant ua.domtrack.mobile android.permission.POST_NOTIFICATIONS
adb logcat -c
adb shell input keyevent 224
adb shell wm dismiss-keyguard
adb shell settings put system screen_off_timeout 180000
adb shell dumpsys power > "$result_dir/startup-power.txt"
adb shell am start -W -n ua.domtrack.mobile/.MainActivity > "$result_dir/startup.txt"
sleep 5

python3 - <<'PY'
import os, re, subprocess, time, json, sqlite3, collections, xml.etree.ElementTree as ET
from pathlib import Path

out = Path(os.environ['DOMTRACK_SMOKE_DIR'])
def adb(*args):
    return subprocess.check_output(['adb', *args], timeout=30)
def capture(name):
    failures = []
    for attempt in range(5):
        try:
            adb('shell', 'rm', '-f', '/sdcard/domtrack-smoke.xml')
            adb('shell', 'uiautomator', 'dump', '/sdcard/domtrack-smoke.xml')
            raw = adb('exec-out', 'cat', '/sdcard/domtrack-smoke.xml')
            tree = ET.fromstring(raw)
            if tree.tag != 'hierarchy':
                raise AssertionError('Missing UI hierarchy')
            (out / (name + '.xml')).write_bytes(raw)
            (out / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
            return tree
        except (subprocess.CalledProcessError, ET.ParseError, AssertionError) as error:
            failures.append(str(error))
            time.sleep(1)
    raise AssertionError('UI hierarchy unavailable: ' + '; '.join(failures))
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
    for label, name in [('Избранное','03-favorites'),('Слежение','04-tracking'),('Ещё','05-more'),('Поиск','06-search-again')]:
        tap(label)
        capture(name)
    # Exercise the real automatic Worker before any manual Search-button test.
    automatic = subprocess.Popen(['adb','shell','am','instrument','-w','-e','class','ua.domtrack.mobile.AutoCheckDeviceTest','ua.domtrack.mobile.test/androidx.test.runner.AndroidJUnitRunner'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    automatic_lines = []
    with (out / 'AUTOMATIC-WORKER.txt').open('w') as automatic_log:
        for line in automatic.stdout:
            automatic_lines.append(line)
            automatic_log.write(line)
            automatic_log.flush()
            if line.strip().startswith('AUTO_'):
                print('::notice title=Android automatic check::' + line.strip(), flush=True)
    automatic.wait(timeout=30)
    automatic_output = ''.join(automatic_lines)
    (out / 'AUTOMATIC-WORKER.txt').write_text(automatic_output)
    if automatic.returncode != 0 or 'FAILURES!!!' in automatic_output or 'INSTRUMENTATION_FAILED' in automatic_output or 'OK (' not in automatic_output:
        raise AssertionError('Automatic Worker regression failed: ' + automatic_output[-10000:])
    # Test output contains synthetic tokenizer text and aggregate counts only.
    result = subprocess.run(['adb','shell','am','instrument','-w','-e','class','ua.domtrack.mobile.DeviceUpgradeTest','ua.domtrack.mobile.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,timeout=360)
    output = result.stdout.decode(errors='replace') + result.stderr.decode(errors='replace')
    (out / 'INSTRUMENTATION.txt').write_text(output)
    if result.returncode != 0 or 'FAILURES!!!' in output or 'INSTRUMENTATION_FAILED' in output or 'OK (' not in output:
        raise AssertionError('Device tests failed: ' + output[-10000:])
    (out / '07-native-map.png').write_bytes(adb('exec-out','run-as','ua.domtrack.mobile','cat','files/map-native.png'))
    for image in ['map-selected.png','map-selected-no-coordinates.png']:
        (out / image).write_bytes(adb('exec-out','run-as','ua.domtrack.mobile','cat','files/'+image))
    combined = subprocess.run(['adb','shell','am','instrument','-w','-e','class','ua.domtrack.mobile.CombinedWorkspaceDeviceTest','ua.domtrack.mobile.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,timeout=180)
    combined_output = combined.stdout.decode(errors='replace') + combined.stderr.decode(errors='replace')
    (out / 'COMBINED-WORKSPACE.txt').write_text(combined_output)
    if combined.returncode != 0 or 'FAILURES!!!' in combined_output or 'INSTRUMENTATION_FAILED' in combined_output or 'OK (' not in combined_output:
        raise AssertionError('Combined workspace device tests failed: ' + combined_output[-10000:])
    for image in ['combined-favorites.png','combined-tracking.png','combined-details.png','combined-full-details.png']:
        (out / image).write_bytes(adb('exec-out','run-as','ua.domtrack.mobile','cat','files/'+image))
    reader = subprocess.run(['adb','shell','am','instrument','-w','-e','class','ua.domtrack.mobile.ListingBrowserDeviceTest','ua.domtrack.mobile.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,timeout=180)
    reader_output = reader.stdout.decode(errors='replace') + reader.stderr.decode(errors='replace')
    (out / 'PUBLIC-READER.txt').write_text(reader_output)
    if reader.returncode != 0 or 'FAILURES!!!' in reader_output or 'INSTRUMENTATION_FAILED' in reader_output or 'OK (' not in reader_output:
        raise AssertionError('Public listing reader device tests failed: ' + reader_output[-10000:])
    for test_class, report_name in [('CurrentSearchDeviceTest','CURRENT-SEARCH.txt'),('MapPriceDeviceTest','MAP-PRICE-GROUPS.txt'),('ListingMapPreviewDeviceTest','LISTING-MAP-PREVIEW.txt'),('RemotePhotosDeviceTest','REMOTE-PHOTOS.txt')]:
        result = subprocess.run(['adb','shell','am','instrument','-w','-e','class','ua.domtrack.mobile.'+test_class,'ua.domtrack.mobile.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,timeout=240)
        output = result.stdout.decode(errors='replace') + result.stderr.decode(errors='replace')
        (out / report_name).write_text(output)
        if result.returncode != 0 or 'FAILURES!!!' in output or 'INSTRUMENTATION_FAILED' in output or 'OK (' not in output:
            raise AssertionError(test_class+' failed: '+output[-10000:])
    for image in ['map-price-groups.png','map-price-group-list.png','map-price-selected-polish.png','map-price-style-positron.png',
                  'map-price-selected-2d.png','map-price-selected-no-point.png','listing-map-preview.png','source-photos.png']:
        (out / image).write_bytes(adb('exec-out','run-as','ua.domtrack.mobile','cat','files/'+image))
    # Run quick UI/data regressions before the natural 15-minute periodic check.
    subprocess.run(['python3','android/ci/closed-background.py'],check=True,timeout=1500)
    (out / 'AUTOMATIC-CHECK.png').write_bytes(adb('exec-out','run-as','ua.domtrack.mobile','cat','files/automatic-check.png'))
    # Reopen the Activity after the test switched off the screen; ensure cached EUR results are visible.
    adb('shell','input','keyevent','82')
    adb('shell','wm','dismiss-keyguard')
    adb('shell','am','start','-W','-n','ua.domtrack.mobile/.MainActivity')
    time.sleep(3)
    adb('shell','uiautomator','dump','/sdcard/domtrack-live.xml')
    live = ET.fromstring(adb('exec-out','cat','/sdcard/domtrack-live.xml'))
    visible_count = max([int(m.group(1)) for n in live.iter('node') if (m := re.search(r'Найдено в локальной базе: (\d+)',n.get('text','')))], default=0)
    adb('shell', 'pidof', 'ua.domtrack.mobile')
    crashes = adb('logcat', '-b', 'crash', '-d').decode(errors='replace')
    (out / 'crashes.txt').write_text(crashes)
    if 'FATAL EXCEPTION' in crashes:
        raise AssertionError('Android runtime crash: ' + crashes[-3000:])
    adb('shell', 'am', 'force-stop', 'ua.domtrack.mobile')
    private = Path(os.environ['RUNNER_TEMP']) / 'domtrack-db-smoke'
    private.mkdir(exist_ok=True)
    for name in ['domtrack.db', 'domtrack.db-wal']:
        result = subprocess.run(['adb', 'exec-out', 'run-as', 'ua.domtrack.mobile', 'cat', 'databases/' + name], capture_output=True, timeout=30)
        if result.returncode == 0:
            (private / name).write_bytes(result.stdout)
        elif name == 'domtrack.db':
            raise AssertionError('Cannot inspect development app database')
    with sqlite3.connect(private / 'domtrack.db') as db:
        records = [json.loads(row[0]) for row in db.execute('SELECT data FROM listing')]
        row = db.execute("SELECT data FROM state WHERE name='lastCheck'").fetchone()
        last = json.loads(row[0]) if row else {}
        extra = {}
        for name in ['aiDeviceTest','screenOffTest','newSourcesTest','mapDeviceTest','selectedMapDeviceTest','automaticDeviceTest','combinedWorkspaceDeviceTest','sourcePhotosDeviceTest']:
            row = db.execute('SELECT data FROM state WHERE name=?',(name,)).fetchone()
            extra[name] = json.loads(row[0]) if row else {}
    (out / 'DEVICE-CHECKS.json').write_text(json.dumps(extra,indent=2))
    live_result = {'rows': len(records), 'visibleWithEmptyEurBudget': visible_count,
                   'sources': dict(collections.Counter(r.get('source','unknown') for r in records)),
                   'pages': last.get('pages'), 'errors': last.get('errors', [])}
    (out / 'LIVE-SEARCH.json').write_text(json.dumps(live_result, ensure_ascii=False, indent=2))
    if not records or visible_count < 1:
        raise AssertionError('Live search returned no visible listings: ' + json.dumps(live_result, ensure_ascii=False))
    (out / 'RESULT.txt').write_text('PASS: Android 16 cold start, native navigation, real multilingual neural inference and official tokenizer parity, SQLite migration, public RIA example, full search advances beyond four pages while screen off, stop and persistent cursors. No physical Samsung/battery test.\n')
    print('Android 16 native startup, navigation and live search: PASS')
except Exception as error:
    for image in ['map-native.png','map-selected.png','map-selected-no-coordinates.png',
                  'combined-favorites.png','combined-tracking.png','combined-details.png','combined-full-details.png','automatic-check.png',
                  'map-price-groups.png','map-price-group-list.png','map-price-selected-polish.png','map-price-style-positron.png',
                  'map-price-selected-2d.png','map-price-selected-no-point.png','listing-map-preview.png','source-photos.png']:
        try:
            (out / image).write_bytes(adb('exec-out','run-as','ua.domtrack.mobile','cat','files/'+image))
        except Exception:
            pass
    for name, args in [('failure-crashes.txt',('logcat','-b','crash','-d')),
                       ('failure-logcat.txt',('logcat','-d','-t','1000')),
                       ('failure-power.txt',('shell','dumpsys','power')),
                       ('failure-window.txt',('shell','dumpsys','window')),
                       ('failure-screen.png',('exec-out','screencap','-p'))]:
        try:
            (out / name).write_bytes(adb(*args))
        except Exception:
            pass
    message = str(error).replace('%','%25').replace('\r','%0D').replace('\n','%0A')
    print('::error title=Android runtime smoke::' + message)
    raise
PY
