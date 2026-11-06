# Android automatic checks repair

**Goal:** Restore observable autonomous checks with the app open and keep the OS-managed periodic checks while it is closed.

**Evidence:** Android 0.2.0 schedules WorkManager, but ScanWorker returns success when Scanner.BUSY rejects work or SearchService interrupts it. No separate automatic-run record exists. Empty startup work can finish before the first saved search is created; saving or enabling a search does not enqueue a catch-up. The Activity only refreshes after SearchService changes, so automatic updates are invisible while the screen remains open. The user confirms the problem also occurs with the screen on. These are verified code defects; the physical Samsung's scheduler state is not yet observable.

**Architecture:** Keep WorkManager and existing network/battery constraints. Give skipped work a retry result, persist distinct automatic-run diagnostics, add a constrained unique catch-up when saved searches become active or an overdue app is visible, and show status independently of manual search. Do not use exact alarms, an endless foreground service or a remote server. The 15-minute interval remains subject to Android scheduling.

- [ ] Add a real Android Worker regression that fails against the old busy branch; observe it fail in CI.
- [ ] Add due/status tests, fix worker outcomes and catch-up scheduling, and refresh automatic status in the Activity.
- [ ] Show enabled targets, worker state/last stop, last automatic attempt/result and platform restrictions. Link to the app's Android settings without changing system settings.
- [ ] Run unit tests, lint, device tests and an autonomous two-run check with no Search-button action, including screen-off execution.
- [ ] Build and verify APK 0.2.1, keep old APK/data intact, document actual device/scheduling/signature limitations, and deliver the new file.

**Validation:** Official WorkManager TestWorkerBuilder for real worker behavior; Android 16 emulator and real SQLite; existing live catalog and native model/map tests. CI uploads aggregate diagnostics only, never user keys or databases. Changes stay on the previously authorized android-standalone branch. Windows 1.4.0 is outside this fix.
