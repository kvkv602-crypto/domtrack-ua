#!/usr/bin/env python3
"""Prove ordinary WorkManager wakes the app after its process has disappeared.

No instrumentation, Activity, manual search, forced job, or time adjustment runs
during the 15-minute wait. Only adb reads a copied SQLite/WAL snapshot.
"""

import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import tempfile
import time


PACKAGE = "ua.domtrack.mobile"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
CLASS = PACKAGE + ".ClosedBackgroundDeviceTest"


def adb(*args, timeout=30, check=True):
    result = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError("adb " + " ".join(args[:4]) + " failed: "
                           + result.stderr.decode(errors="replace")[-1000:])
    return result


def instrument(method, log, timeout=240):
    result = adb("shell", "am", "instrument", "-w", "-e", "class", CLASS + "#" + method,
                 RUNNER, timeout=timeout, check=False)
    output = result.stdout.decode(errors="replace") + result.stderr.decode(errors="replace")
    log.write_text(output, encoding="utf-8")
    for line in output.splitlines():
        if line.strip().startswith("AUTO_"):
            print("::notice title=Android closed background::" + line.strip(), flush=True)
    if result.returncode or "FAILURES!!!" in output or "INSTRUMENTATION_FAILED" in output or "OK (" not in output:
        raise AssertionError(method + " failed: " + output[-8000:])


def state_snapshot():
    """Reading via run-as does not create/keep alive the application process."""
    with tempfile.TemporaryDirectory(prefix="domtrack-closed-state-") as temporary:
        target = Path(temporary) / "domtrack.db"
        for name in ["domtrack.db", "domtrack.db-wal"]:
            result = adb("exec-out", "run-as", PACKAGE, "cat", "databases/" + name, check=False)
            if result.returncode == 0:
                (target.parent / name).write_bytes(result.stdout)
            elif name == "domtrack.db":
                raise AssertionError("Cannot inspect background fixture database")
        # A concurrent commit can interrupt a copied snapshot; caller retries those reads.
        with sqlite3.connect(target) as database:
            return {name: json.loads(data) for name, data in database.execute(
                "SELECT name,data FROM state WHERE name IN ('closedBackgroundFixture','autoCheck')")}


def pids():
    result = adb("shell", "pidof", PACKAGE, check=False)
    value = result.stdout.decode(errors="replace").strip()
    if value and not re.fullmatch(r"\d+(?:\s+\d+)*", value):
        raise AssertionError("Unexpected pidof output")
    return value.split()


def screen_off():
    power = adb("shell", "dumpsys", "power").stdout.decode(errors="replace")
    return bool(re.search(r"mWakefulness=Asleep\b", power)
                or re.search(r"mInteractive=false\b", power))


def run(out):
    prepared = False
    restored = False
    try:
        instrument("prepareClosedProcessRepeat", out / "CLOSED-BACKGROUND-PREPARE.txt")
        prepared = True
        snapshot = state_snapshot()
        fixture = snapshot.get("closedBackgroundFixture", {})
        first = fixture.get("first", {})
        first_at = first.get("lastSuccessAtMillis", 0)
        if not fixture.get("prepared") or not first_at:
            raise AssertionError("Background preparation did not persist the first run")
        # Activity was finishAndRemoveTask'd in the preparation phase. This is a memory/process
        # kill of a background app, deliberately not Android's persistent user force-stop flag.
        adb("shell", "input", "keyevent", "223")
        adb("shell", "am", "kill", PACKAGE)
        deadline = time.monotonic() + 15
        while pids() and time.monotonic() < deadline:
            time.sleep(0.5)
        if pids():
            raise AssertionError("App process remained alive after its Activity closed and am kill")
        if not screen_off():
            raise AssertionError("Screen must be off before waiting for the periodic job")
        evidence = {
            "first": first,
            "firstProcessId": fixture.get("firstProcessId"),
            "processAbsentAfterKill": True,
            "activityClosedBeforeKill": True,
            "instrumentationExitedBeforeKill": True,
            "forceStopUsed": False,
            "manualSearchButtonPressedDuringWait": False,
            "currentTargetRegisteredBySearch": bool(fixture.get("currentTargetRegisteredBySearch")),
            "forcedJobUsed": False,
            "intervalMillis": 900000,
        }
        (out / "CLOSED-BACKGROUND.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")
        (out / "CLOSED-BACKGROUND-JOBS-BEFORE.txt").write_bytes(adb("shell", "dumpsys", "jobscheduler").stdout)
        print("::notice title=Android closed background::AUTO_PROCESS_GONE screenOff=true ordinaryPeriodicWait=true", flush=True)
        deadline = time.monotonic() + 1200
        second = {}
        observed_pids = []
        while time.monotonic() < deadline:
            if not screen_off():
                raise AssertionError("Screen woke during the natural process-dead periodic wait")
            current_pids = pids()
            if current_pids:
                observed_pids = current_pids
            try:
                second = state_snapshot().get("autoCheck", {})
            except (sqlite3.Error, json.JSONDecodeError):
                time.sleep(5)
                continue
            if second.get("lastSuccessAtMillis", 0) > first_at and second.get("loadedPages", 0) > 0 \
                    and second.get("status") in ("checked", "partial"):
                break
            time.sleep(10)
        else:
            raise AssertionError("No successful natural periodic repeat after process kill: " + json.dumps(second))
        interval = second["lastSuccessAtMillis"] - first_at
        if interval < 900000 or second.get("trigger") != "periodic":
            raise AssertionError("Repeat did not come from the ordinary 15-minute periodic job: " + json.dumps(second))
        evidence.update({"second": second, "actualIntervalMillis": interval,
                         "screenOffForSecondRun": True, "observedRestartedProcessIds": observed_pids,
                         "noActivityOrInstrumentationDuringWait": True, "passed": True})
        (out / "CLOSED-BACKGROUND.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")
        (out / "CLOSED-BACKGROUND-JOBS-AFTER.txt").write_bytes(adb("shell", "dumpsys", "jobscheduler").stdout)
        instrument("verifyClosedProcessRepeatAndRestore", out / "CLOSED-BACKGROUND-VERIFY.txt", timeout=90)
        restored = True
    finally:
        if prepared and not restored:
            instrument("cleanupClosedProcessFixture", out / "CLOSED-BACKGROUND-CLEANUP.txt", timeout=90)


if __name__ == "__main__":
    directory = Path(os.environ["DOMTRACK_SMOKE_DIR"])
    directory.mkdir(parents=True, exist_ok=True)
    run(directory)
