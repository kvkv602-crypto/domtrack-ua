# DomTrack UA

DomTrack UA is a local Windows application for Ukrainian apartment and house sale listings. It keeps observed price history, filters properties, groups confirmed duplicate listings, and provides favorites, notes, saved searches and price-threshold notifications.

## Publication and release status

Version **1.3.0 / API4** is in development. The owner has approved public source publication under the **MIT license** and selected the **GitHub + SignPath Foundation** distribution route. [LICENSE](LICENSE) is the active project license; [LICENSE.proposed](LICENSE.proposed) retains the exact approved reference text. The repository visibility change is pending completion; this document does not claim it is already public.

No Foundation application has been submitted, no signing approval or production certificate is configured, and no trusted signed release is available. The earlier local 1.2.0 executable is unsigned and was blocked by Windows Smart App Control. Building a file on GitHub does not, by itself, make Windows trust it.

The Foundation's [eligibility conditions](https://signpath.org/terms.html) require an eligible open-source project and approval. Publication alone does not guarantee acceptance. The separate [MSIX preparation](packaging/msix/README.md) remains a development alternative; it has not produced a certified Store release.

## What the application currently covers

- Sales of apartments and houses, with city, room count, area, floor, price and seller-type filters where source data permits.
- Zaporizhzhia: Rieltor.ua, public DOM.RIA pages and OBYAVA.ua. Other cities in the current catalog use Rieltor.ua. Availability and errors are reported for each connected source; coverage of the whole internet is not claimed.
- An optional official DIM.RIA API connection with the user's own key. Its collection is a separate source action, and real-key access to search and listing details has not yet been verified. Available data and quotas depend on the API account.
- USD, EUR and UAH comparison using official NBU rates for the selected date. Original prices and currencies are retained; missing rates are reported rather than replaced with another date's rate.
- Price history starts at the first successful observation. Similar properties are not automatically treated as identical without the application's confirmation criteria or the user's review. Confirmed groups can display their lowest comparable price.
- Price watches and notifications work while the application is running. Windows may suppress system notifications; events are also available inside the app.

## Installation, data and removal

There is currently **no signed end-user download to install**. The intended release is a single Windows x64 self-extracting executable, with a signed launcher and signed outer package. Microsoft Edge WebView2 Runtime and .NET Framework 4.6.2 or a compatible later version are prerequisites. The app does not silently install these system components.

The single-file package extracts application files beneath `%LOCALAPPDATA%\DomTrackUA\app\<payload-hash>`. It keeps observations, preferences and the protected API key separately in `%LOCALAPPDATA%\DomTrackUA\data`; the embedded browser profile is in `%LOCALAPPDATA%\DomTrackUA\webview`. Updating application files does not overwrite the data folder. A supported explicit data/profile override must be an absolute local path outside the installation directory. See [privacy and storage details](PRIVACY.md).

The app does not install a Windows service or enable automatic startup. Closing its window or choosing **«Остановить и выйти»** shuts down the server owned by that launcher and stops its scheduled checks. A development server started separately must be stopped separately.

To remove the single-file edition, exit it, remove the downloaded executable and any shortcuts you created, then remove its application cache under `DomTrackUA\app`. Keep `data` if you want to preserve your history and settings. Removing `data` also removes the encrypted API key; removing `webview` clears the embedded browser profile. None of these actions delete a third-party website account. A complete data backup should be made after the app has stopped.

## Build and signing

[Source CI](.github/workflows/build.yml) restores the pinned official Node.js and WebView2 SDK inputs, checks their hashes and vendor signatures, runs source tests, and compiles an unsigned development folder. The private development environment's executable is not a distribution dependency. CI artifacts are development evidence, not a signed Windows release.

The [SignPath integration preparation](ci/signpath-integration.md) separates signing the inner native launcher from signing the outer package. Version 1.3's launcher contains the Windows DPAPI/notification bridge; the proposed production payload excludes the old PowerShell helpers. Missing or policy-denied native components fail closed.

Build/signing workflows are manual. Publishing a release is a separate reviewed action. Real Windows startup, DPAPI, notifications, update behavior and clean-machine installation remain release acceptance checks; mocked source tests and compilation do not establish those results.

## Project policies

[Code signing policy](CODE_SIGNING_POLICY.md) · [Privacy policy](PRIVACY.md) · [Third-party notices](THIRD_PARTY_NOTICES.md) · [Security reports](SECURITY.md)

Maintainer: [kvkv602-crypto](https://github.com/kvkv602-crypto). User databases, API keys, logs, signing credentials and local build outputs are excluded from source publication.
