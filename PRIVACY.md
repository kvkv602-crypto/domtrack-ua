# Privacy policy — draft

Privacy policy draft for the private DomTrack UA development project. Maintainer/contact: `https://github.com/kvkv602-crypto`. Repository: `https://github.com/kvkv602-crypto/domtrack-ua`. The owner must review this policy before any signed end-user release.

## Local information

The app stores observed real-estate listing records and price history, public contact details returned by sources, user-entered listings, favorites, notes, saved searches, watch thresholds, settings, exchange-rate caches and internal notifications on the user's computer. SQLite data and notes are not separately encrypted. The optional RIA API key is protected with Windows DPAPI for the current Windows account; the decrypted key is not returned to the interface. This is not a promise of protection against malware running as that user.

The packaged portable edition uses `%LOCALAPPDATA%\DomTrackUA\data`. A folder development edition may use its own `data` directory or an explicitly configured `DOMTRACK_DATA_DIR`. A WebView2 profile is stored beside the data directory, in `webview`. A bounded launcher log records startup/error diagnostics. Working data and logs are excluded from repository uploads and release artifacts.

## Network activity

The app has no application-owned advertising, analytics or telemetry reporting endpoint in the reviewed source. It binds its local API to loopback and requires a session token. It is not a VPN or anonymity service: remote services can observe the computer's public IP address and requested URLs.

Launching the desktop app automatically refreshes the selected city once. Searching or loading more pages requests the connected public catalogs (currently Rieltor, DOM.RIA and OBYAVA for Zaporizhzhia). Configured source collection and individual price watches may issue later requests while the app runs. A user may also configure other public source URLs. Website access rules and quotas are respected; the app does not bypass paywalls or CAPTCHA.

Currency comparison and historical conversion request date-specific official rates from `bank.gov.ua`. Enabling the optional RIA integration sends the user-provided API key and selected search parameters to the official `developers.ria.com` API. It does not send the key to other listing sites. A map, when used for a listing with known coordinates, loads OpenStreetMap content and exposes that map request to its service. Opening an original listing, contact link or external link uses the chosen third-party service/default application.

The app does not itself upload the user's database, notes, favorites or key to a maintainer. Export saves listing records to a file selected by the user; it may include public source contact details and user-entered listing information. Sharing an export or backup is a separate user action.

## External components and controls

WebView2 Runtime and the operating system have their own network, update and privacy behavior. Review Microsoft's privacy statement at https://privacy.microsoft.com/privacystatement and OpenStreetMap's policy at https://osmfoundation.org/wiki/Privacy_Policy. Review the relevant listing provider's privacy policy when using that provider; the app is not affiliated with those providers.

The app does not install a Windows service or enable Windows autostart. Closing/stopping it ends scheduled checks. Users can disable configured collection, turn off watches and avoid map/external-link actions. A future installer must show this policy and describe enabled network behavior; the current draft does not claim that installer review has happened.

## Retention and removal

Local information remains until the user changes or removes it. A full backup must keep the SQLite database and any related WAL/SHM files together after stopping the app. Removing the `data` folder deletes local history/settings and the encrypted key; removing `webview` clears the embedded profile. Uninstalling files does not cancel accounts or erase data held independently by third-party websites.