# DomTrack UA

Private development repository owned by kvkv602-crypto. The project is not released under MIT or another open-source license. User databases, API keys, local logs and compiled binaries are excluded.

## Current application status

Version 1.2.0 source covers Ukrainian apartment/house sale listings, observed price history, confirmed duplicate review, favorites, notes and price thresholds. USD/EUR/UAH conversion uses exact-date official NBU rates, preserving source prices. Zaporizhzhia has adapters for Rieltor.ua, public DOM.RIA and OBYAVA.ua; other catalog cities currently use Rieltor. Full internet coverage is not claimed.

The existing local EXE is unsigned and Windows Smart App Control blocked it. No new trusted signed release has been obtained. Policy-denied helpers now fail closed and do not retry their contents through another host mode.

## Signing and privacy

Free SignPath Foundation support is for eligible open-source projects; the current private project does not meet that publication requirement. No Foundation application or signing request has been submitted. See [code signing policy](CODE_SIGNING_POLICY.md).

[Privacy policy](PRIVACY.md) · [third-party notices](THIRD_PARTY_NOTICES.md) · [security reports](SECURITY.md).

## Build preparation

Application source is in [app/](app). [CI](.github/workflows/build.yml) restores fixed official Node.js and Microsoft WebView2 packages with hash and vendor-signature checks. It does not use the private development environment's runtime.

CI is **manual only**. No workflow is started on push or pull requests, and no signing request or release publication is automatic. Review account quota/billing before any manual hosted run. CI evidence is explicitly unsigned and is not a trusted Windows release. Real Windows DPAPI, system notifications and GUI integrations are not claimed by the default source tests.

Local source preparation has 261 passing JavaScript tests and one explicitly skipped real-DPAPI integration. Signing-option contract checks passed; actual signing still requires an approved signing route and credentials held by the human owner.
