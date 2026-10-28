# DomTrack UA

Private development repository owned by kvkv602-crypto. The project is not released under MIT or another open-source license. User databases, API keys, local logs and compiled binaries are excluded.

## Current application status

Version 1.3.0 source is being prepared for MSIX Store distribution. It covers Ukrainian apartment/house sale listings, observed price history, confirmed duplicate review, favorites, notes and price thresholds. USD/EUR/UAH conversion uses exact-date official NBU rates, preserving source prices. Zaporizhzhia has adapters for Rieltor.ua, public DOM.RIA and OBYAVA.ua; other catalog cities currently use Rieltor. Full internet coverage is not claimed.

The previous local 1.2.0 EXE is unsigned and Windows Smart App Control blocked it. No new trusted signed release has been obtained. The native launcher now supplies its own bounded DPAPI/notification bridge, and MSIX preparation excludes PowerShell scripts. Policy-denied helpers fail closed without interpreter retries. Data and WebView2 profiles stay outside the installation directory; API4 prevents silently reusing an API3 server with the old helper behavior.

## Signing and privacy

Free SignPath Foundation support is for eligible open-source projects; the current private project does not meet that publication requirement. No Foundation application or signing request has been submitted. See [code signing policy](CODE_SIGNING_POLICY.md).

The selected route is [Microsoft Store MSIX preparation](packaging/msix/README.md), using Private audience for the first submission. It requires a verified publisher and the exact Partner Center product identity. Those values are not available yet, so no actual MSIX has been produced, installed or submitted. New personal publisher registration requires an ID document and selfie; this project does not bypass that requirement. Public policy URL, listing screenshots, Store certification and installed-package acceptance remain pending.

[Privacy policy](PRIVACY.md) · [third-party notices](THIRD_PARTY_NOTICES.md) · [security reports](SECURITY.md).

## Build preparation

Application source is in [app/](app). [CI](.github/workflows/build.yml) restores fixed official Node.js and Microsoft WebView2 packages with hash and vendor-signature checks. It does not use the private development environment's runtime.

CI is **manual only**. No workflow is started on push or pull requests, and no signing request or release publication is automatic. Review account quota/billing before any manual hosted run. CI evidence is explicitly unsigned and is not a trusted Windows release. Real Windows DPAPI, system notifications and GUI integrations are not claimed by the default source tests.

Default source tests explicitly skip real DPAPI integration. Source checks and compilation do not prove that the installed Store package's GUI, DPAPI, notifications, updates or migration work on an enforced device. Final check results are recorded in the [preparation report](packaging/msix/PREPARATION.md); Store signing is supplied only after successful certification.
