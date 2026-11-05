# Privacy policy

Privacy policy for DomTrack UA's MIT-licensed source publication. Maintainer/contact: [kvkv602-crypto](https://github.com/kvkv602-crypto). Repository: [domtrack-ua](https://github.com/kvkv602-crypto/domtrack-ua). The owner has approved public source publication and the MIT license is active. The repository visibility change is pending completion, and no signed end-user release is available.

## Local information

The app stores observed real-estate listing records and price history, public contact details returned by sources, user-entered listings, favorites, notes, saved searches, watch thresholds, settings, exchange-rate caches and internal notifications on the user's computer. SQLite data and notes are not separately encrypted. The optional RIA API key is protected with Windows DPAPI for the current Windows account; the decrypted key is not returned to the interface. This is not a promise of protection against malware running as that user.

The unpackaged native launcher defaults to `%LOCALAPPDATA%\DomTrackUA\data`. The MSIX launcher uses a separate `%LOCALAPPDATA%\DomTrackUA\store-<package-family-hash>\data` location, stable for the same package family across updates. This prevents accidentally reusing a portable instance or silently importing its data. An explicitly configured local `DOMTRACK_DATA_DIR` must stay outside the installation directory. A WebView2 profile is stored beside the selected data directory in `webview`, or in a separately configured `DOMTRACK_WEBVIEW_DIR`. Starting the development Node server directly can use its source-local `data` directory. MSIX can virtualize per-user storage; the effective installed-package location, upgrade and uninstall behavior still require acceptance testing. No database is silently copied or migrated. A bounded launcher log records startup/error diagnostics. Working data and logs are excluded from repository uploads and release artifacts.

## Network activity

The app has no application-owned advertising, analytics or telemetry reporting endpoint in the reviewed source. It binds its local API to loopback and requires a session token. It is not a VPN or anonymity service: remote services can observe the computer's public IP address and requested URLs.

Launching the desktop app automatically refreshes the selected city once. Searching or loading more pages requests the connected public catalogs: `rieltor.ua`, and, for the currently supported Zaporizhzhia routes, `dom.ria.com` and `obyava.ua`. Configured source collection and individual price watches may issue later requests while the app runs. A user may also configure other public source URLs. Website access restrictions and quotas can stop or limit collection; the app does not bypass paywalls or CAPTCHA.

Currency comparison and historical conversion request date-specific official rates from `bank.gov.ua`. Enabling the optional RIA integration sends the user-provided API key and selected search parameters to the official `developers.ria.com` API. It does not send the key to other listing sites. A map, when used for a listing with known coordinates, loads OpenStreetMap content and exposes that map request to its service. Opening an original listing, contact link or external link uses the chosen third-party service/default application.

The app does not itself upload the user's database, notes, favorites or key to a maintainer. Export saves listing records to a file selected by the user; it may include public source contact details and user-entered listing information. Sharing an export or backup is a separate user action.

## External components and controls

WebView2 Runtime and the operating system have their own network, update and privacy behavior. Review Microsoft's privacy statement at https://privacy.microsoft.com/privacystatement and OpenStreetMap's policy at https://osmfoundation.org/wiki/Privacy_Policy. Review the relevant listing provider's privacy policy when using that provider; the app is not affiliated with those providers.

The app does not install a Windows service or enable Windows autostart. Closing its window or choosing **«Остановить и выйти»** stops the server owned by that launcher and its scheduled checks. A separately started development server must be stopped separately. Users can disable configured collection, turn off watches and avoid map/external-link actions. The app includes a Russian privacy summary accessible from its footer. Its proposed signed release uses the native Windows bridge for DPAPI and notifications rather than distributing the legacy PowerShell helpers. Neither SignPath approval nor end-user release acceptance has occurred.

## Retention and removal

Local information remains until the user changes or removes it. A full backup must keep the SQLite database and any related WAL/SHM files together after stopping the app. The single-file edition can be removed by exiting it and removing the downloaded executable, any user-created shortcuts and `%LOCALAPPDATA%\DomTrackUA\app`. Its separate `data` and `webview` folders are not removed by that operation. Removing `data` deletes local history/settings and the encrypted key; removing `webview` clears the embedded profile. Removing app files does not cancel accounts or erase data held independently by third-party websites.
