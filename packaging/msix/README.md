# Microsoft Store MSIX preparation

This path prepares an **unsigned x64 MSIX for Partner Center submission**. It does not install or start DomTrack, change Windows protection, trust a development certificate, sign the package, create a Store submission, or publish anything. The GitHub repository remains private. A package is not a trusted release until Microsoft has certified/signed it and the installed version has passed acceptance tests.

Current preparation status: **the real Partner Center product identity is not available, so no actual Store submission package has been produced**. New personal-account onboarding requires an identity document and selfie verification; the scripts cannot replace that human verification. A company account is an option only for a real company using Microsoft's official business-verification procedure, not a fictitious company or a way to skip personal-account requirements. Source/package preparation can remain private while the owner decides on an eligible distribution route.

## Exact Partner Center identity

The human owner must finish Windows developer-account verification, create/reserve the Store product, and open **Product management → Product identity** for that product. Copy these three values exactly, including case and the entire publisher distinguished name:

| Partner Center value | Build parameter / manual workflow input |
| --- | --- |
| `Package/Identity/Name` | `-IdentityName` / `identity_name` |
| `Package/Identity/Publisher` | `-Publisher` / `publisher` |
| `Package/Properties/PublisherDisplayName` | `-PublisherDisplayName` / `publisher_display_name` |

They are not a GitHub repository name, email, Package Family Name, Store ID, or an invented `CN=` string. The builder has no default publisher identity. It refuses missing, malformed or unresolved values **before writing any files or invoking an SDK tool**. Syntax validation cannot prove that an identity belongs to the owner; Partner Center association remains authoritative. The package display name is currently `DomTrack UA`; reserve that Store display name or review the manifest display names before submission.

The numeric app version is read from the compiled folder's `package.json` and becomes `major.minor.patch.0`. The launcher file version must match. The fourth component stays `0` for Store submission, and future submissions must use an accepted increasing package version.

Source: [Microsoft product identity instructions](https://learn.microsoft.com/en-us/windows/apps/publish/view-app-identity-details), [package Identity schema](https://learn.microsoft.com/en-us/uwp/schemas/appxpackage/uapmanifestschema/element-f-identity).

## Private Store audience

Private source hosting and Store audience are separate settings. For the initial Store submission, select **Private audience** under the app's availability/visibility settings and choose a known user group containing the owner's/testers' **Microsoft-account email addresses**. This is stronger than merely hiding a public listing from search. Confirm the audience and final submission summary before authorizing publication.

Private audience is intended for a product that has not previously been made public. Selected people access the listing while signed into the specified Microsoft account; removing someone later does not revoke an already downloaded app. This repository contains no tester addresses and does not configure the audience automatically. See [beta testing and targeted distribution](https://learn.microsoft.com/en-us/windows/apps/publish/beta-testing-and-targeted-distribution) and [known user groups](https://learn.microsoft.com/en-us/windows/apps/publish/create-known-user-groups).

## Build inputs and output

`ci/Restore-Dependencies.ps1` restores the pinned official Node.js and Microsoft WebView2 SDK. `ci/Build-Source.ps1` compiles the inner WinForms launcher including the native Windows helper mode; the launcher/helper is not executed. `ci/Restore-PackagingTools.ps1` restores the pinned official Microsoft Windows SDK build tools to a workspace directory, verifies their archive digest and Microsoft signatures, and returns `MakeAppxPath`. No system SDK installer is required.

`ci/Build-StorePackage.ps1` takes that compiled folder, the exact three identity parameters, a new output directory and optionally `-MakeAppxPath`. It accepts only a valid Microsoft-signed `MakeAppx.exe` with its original SDK file identity, records its digest/version, keeps schema validation enabled, and invokes **only that packaging tool**. `-ValidateIdentityOnly` performs the identity gate without any filesystem mutation or SDK invocation. No signing certificate or Store token is needed for this preparation.

`payload-files.txt` is an explicit allowlist. It includes the inner launcher, Node, WebView2 SDK DLLs, application JS/HTML/CSS/icon files, and required notices. It excludes the SFX, all PowerShell scripts, C# and build sources, tests, databases, logs, API keys and signing material. Source-build evidence must confirm that native helper mode was compiled. The native `--windows-helper` mode lives in the same launcher executable; there is no additional helper EXE to copy. A generated root `store-runtime.json` marker (`format: domtrack-store-runtime`, `version: 1`) tells the shared selector to require the configured native bridge and fail closed if it is absent. The separate unpackaged source edition can retain its existing PowerShell support; the Store payload contains no PS1 and never selects that route. The builder generates three PNG assets deterministically from the existing `app.ico`, then checks the final MSIX ZIP entry set against the allowlist and generated MSIX metadata.

The output directory contains the `.msix`, its `.sha256`, `store-package-evidence.json`, `SUBMISSION-ONLY.txt`, and a staging `payload/` folder for inspection. Only the package and evidence files are uploaded by the manual workflow. The staging folder is not an alternative release or startup method. No existing output directory is overwritten or deleted.

The [manual workflow](../../.github/workflows/store-package.yml) has no push/PR trigger, signing secret, Store credential, installation command or publish/release action. Its three identity inputs are passed through environment variables rather than inserted into shell source. Running it is a separate owner action and can consume private-repository Actions quota; this preparation does not start a hosted run.

Source: [MakeAppx usage and validation](https://learn.microsoft.com/en-us/windows/msix/package/create-app-package-with-makeappx-tool), [Microsoft SDK BuildTools NuGet](https://www.nuget.org/packages/Microsoft.Windows.SDK.BuildTools/10.0.28000.2705).

## Runtime and certification checks still required

The manifest declares a **desktop/full-trust** WinForms app (`packagedClassicApp`, `mediumIL`, `runFullTrust`), x64, Windows Desktop 10.0.19041.0 or newer. It is not an AppContainer conversion. Full trust means ordinary user access, not administrator elevation. Explain `runFullTrust` in Partner Center as required for the existing desktop UI, bundled Node process, local listing database and Windows DPAPI operations. Request no elevation, autostart, driver, firewall modification or background service. [Microsoft capability reference](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/app-capability-declarations).

All mutable settings, SQLite/WAL/SHM files, encrypted keys, endpoint discovery, logs and the WebView2 profile must use the launcher's external per-user data location. The MSIX default is `%LOCALAPPDATA%\DomTrackUA\store-<first 24 lowercase hex characters of SHA256(lowercase package family name)>\data`, with sibling `webview`. This stays distinct from the portable `%LOCALAPPDATA%\DomTrackUA\data`; it does not silently copy/import the old database or key. MSIX virtualization can redirect the effective physical location and must be checked during installed-package acceptance. The installed package and bundled JS are read-only. Acceptance must verify clean storage, stable paths across updates, uninstall behavior and any owner-authorized manual migration. [MSIX preparation requirements](https://learn.microsoft.com/en-us/windows/msix/desktop/desktop-to-uwp-prepare).

The SDK DLLs are not the WebView2 browser Runtime. The current launcher uses the separately installed **Evergreen WebView2 Runtime**. Check availability on a clean supported Windows system and document the official Microsoft prerequisite if missing; do not assume the Edge browser is a substitute. An App Installer external dependency is a separate distribution-specific mechanism, not a completed Store prerequisite configuration in this template. [WebView2 distribution](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/distribution).

Local structural tests cover AST parsing, identity refusal before writes, manifest shape, allowlist safety and deterministic icon generation. They do **not** certify Store acceptance, the installed-package network boundary, launcher lifecycle, WebView2 GUI, DPAPI or notifications. Verify clean install, close/reopen, single-instance behavior, localhost/WebView2, key save/load, notifications and update on an allowed test device with Smart App Control enabled. Packaging is not evidence that a policy-denied component now runs.

For Store MSIX distribution, Microsoft supplies the package signature after certification; a CA-trusted publisher certificate is not required for submission. Merely renaming an unsigned EXE/MSIX, supplying the package directly, or choosing the Store MSI/EXE route does not provide this signature. Before submission, the owner must review privacy policy URL, store descriptions/screenshots, age rating, restricted-capability explanation, audience and certification notes. No submission has been made by these scripts. [Microsoft Store signing and submission guidance](https://learn.microsoft.com/en-us/windows/apps/publish/get-started).

## Local structure check

On a system allowed to run ordinary source checks, use `packaging/msix/Test-StorePackaging.ps1` with PowerShell 7. It invokes no app, helper or SDK executable. Any test-only identity exists in memory and is not a package identity/default; no MSIX is generated by this check. Do not work around a policy denial to run a test.
