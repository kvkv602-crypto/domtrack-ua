# DomTrack Android 0.3: combined property workspace

## Intent and references

The owner requested analysis of the official Android DIM.RIA (`com.ria.dom`) and OLX Ukraine (`ua.slando`) applications and a more functional combined application tailored to the existing DomTrack requirements. This design keeps independent phone operation as the primary mode. It implements our own code and adapters; downloaded vendor packages, artwork, signing identities and account credentials are not incorporated into DomTrack.

Official references: [DIM.RIA Google Play](https://play.google.com/store/apps/details?hl=uk&id=com.ria.dom), [OLX Ukraine Google Play](https://play.google.com/store/apps/details?hl=uk&id=ua.slando), [OLX developer FAQ](https://developer.olx.ua/articles/faq). Current public listings describe map/region search, filters, favorites/notebook, saved search/new listing alerts, sharing and price alerts. Publisher screenshots were downloaded separately for inspection. Static package analysis and publisher-signature evidence remain outside the source repository; they do not establish that the apps were run or that a downloaded older version is current.

## Chosen approach

Extend the existing native Java/SQLite/MapLibre application. Reusing our adapters, background scheduler and local history keeps phone independence and existing sources. A website-only wrapper would not supply reliable unified history, and concatenating vendor APKs would not grant their server-side database/API privileges. OLX explicitly limits its partner API to the authorized account's own adverts. Public site failures remain visible; the app cannot claim complete Internet coverage.

## Resulting workflow

1. Search shows the current city/budget, an explicit full-search action, visible sort choice and a compact source result summary. Source details retain actual cursor/error evidence rather than a fabricated completion percentage.
2. Favorites shows all saved objects regardless of current search city, budget or drawn polygon.
3. Tracking brings watched objects, saved searches and events together. Opening and editing a saved search can update the same ID instead of accumulating copies. Threshold editing retains its saved currency.
4. Original listing links can be shared out through the Android chooser. Existing inbound sharing from DIM.RIA/OLX remains supported; OLX individual public pages acquire a conservative detail parser and exact watch matching.
5. Map navigation remains readily accessible. Editing a region loads candidates without the old polygon, and its live coverage label is updated through the persistent view.

The owner subsequently clarified that the DIM.RIA reference concerns the map of a specific advert. A selected-listing mode therefore ignores active search filters, shows a compact price/address/source/accuracy panel, offers directions and returns to the same card without changing the search. Unknown coordinates produce city context without a property pin. Nearby listings and 3D are outside this iteration.

For a failed direct import, a user can open a supported public RIA/OLX advert in a short-lived foreground WebView reader. This reader runs in a separate process/profile with no account cookies, private endpoints, media downloads, file access, permission grants or background rendering. Its capped visible DOM/public JSON-LD snapshot returns only after exact URL/canonical validation; Main validates the original object again before ingestion. HTTPS errors, login, CAPTCHA and server refusals remain errors. A browser rendering is not a promise of continuous background access. Native DOM.RIA API watches refresh the exact original ID through the existing own-key client and quota instead of falling into the incompatible public catalog adapter.

## Data and notification constraints

Keep existing records, key storage and WorkManager interval/constraints. Do not migrate URL identities speculatively or merge a different listing's history. A failed check must not ingest an old price or unrelated catalog records as the watched object's successful update. Mark visible converted/unknown-origin prices as display evidence and suppress verified price-drop/threshold notifications from those amounts; show that distinction to the user. New OLX detail capture does not download photos.

RIА API integration, RU/UA matching, existing other sources, on-device duplicate model, encrypted backup and optional PC file exchange remain. Whole-internet completeness, exact 15-minute delivery under all Android conditions, Android17, physical Samsung battery performance and unrestricted OLX access are not acceptance claims for this patch.

## Acceptance

Pure Java regressions verify global favorites, saved sort/search identity/currency, displayed-price notification suppression and strict OLX detail parsing. Existing Android16 tests continue; new device checks verify changed navigation and workflows on synthetic data, with screenshots for inspection. Map instrumentation validates all-candidate region editing and live coverage. Build/test/lint/signature/alignment evidence is recorded for the delivered APK. User databases and RIA keys are excluded from CI and exports of project sources.

The owner also requested monitoring while the app is closed. Persistent WorkManager scheduling already outlives the Activity and ordinary process death; no resident service is added. The natural 15-minute acceptance test therefore runs in external phases: first catch-up, Activity/task close, instrumentation exit, confirmed process kill without force-stop, screen-off wait, then a periodic refresh recorded in SQLite before any Activity/instrumentation resumes. Android user force-stop, Doze and vendor restrictions remain platform constraints.

## Signing and delivery

Previous CI debug builds used different disposable signers. Preserve a local signing identity for subsequent owned builds without putting its private key or password into the public repository. The first build with that identity cannot update an APK signed with a different key; backup/restore limitations must be explained before any uninstall. Do not remove an installed app or its data during this work.
