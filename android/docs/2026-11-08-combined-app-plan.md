# Combined Android workspace implementation plan

**Goal:** Apply useful official-app workflows to our independent multi-source property tracker.

**Architecture:** Keep native UI, existing SQLite/history and WorkManager. Isolate presentation-state rules in a pure Java `Workspace` helper; add an OLX detail adapter with exact identity matching; correct map candidate/coverage handling. Vendor APKs stay outside the build.

**Spec:** `2026-11-08-combined-app-design.md`.

- [ ] Add failing pure-Java tests for favorites unaffected by search filters, saved search ID reuse and watch currency retention; implement `Workspace`.
- [ ] Add displayed-price notification regressions in Model; preserve failure/no-observation semantics.
- [ ] Wire visible sort, global favorites, tracking workspace, editing saved searches, outbound sharing, display-price labels and source summary into MainActivity. Keep existing entry points compatible.
- [ ] Implement conservative OLX detail import/watch regressions and exact original-object selection in Sources/Scanner. Existing source and network restrictions remain.
- [ ] Correct persistent map coverage and region-edit candidate filtering; run pure and device regressions.
- [ ] Add selected-ad map/accuracy/directions and return-to-card behavior without modifying the current search.
- [ ] Add a foreground isolated public-page import reader, exact URL/canonical checks, media/service-worker restrictions and synthetic WebView tests; leave server challenges and background access limitations explicit.
- [ ] Refresh native RIA API watches by original ID with existing key/quota, preserving stored history identity and rejecting unrelated/failed responses.
- [ ] Add Android16 UI checks for combined workflows and capture screenshots. Keep test data isolated from user data.
- [ ] Advance version to 0.3.0/code4; run local meaningful JVM checks and review changes before the established GitHub Android CI build.
- [ ] Verify CI unit/device/lint/build results; inspect delivered screenshots and APK inventory/signature. Establish local signer continuity without uploading private signing data.
- [ ] Deliver APK, checksums and concise functional comparison/report, distinguishing static APK analysis, public screenshots, emulator checks and unavailable physical-device tests.
