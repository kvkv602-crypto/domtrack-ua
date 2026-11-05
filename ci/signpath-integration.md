# GitHub and SignPath Foundation integration preparation

This document describes the prepared 1.3.0/API4 release path. The owner has approved public source publication under MIT. The exact approved license has been activated in the root LICENSE, app/LICENSE and package metadata; the repository visibility change is pending completion. No Foundation application, approval, certificate, signing policy, hosted signing run or signed release exists yet. Publication approval is not Foundation approval.

## Publication prerequisites

The owner has approved the reviewed source publication and exact MIT license. Inspect the complete Git history and repository artifacts before completing the visibility change. Do not publish a user's database, RIA API key, private logs, account-registration material, signing credentials or old local binaries.

The Foundation also assesses the project's release history, maintenance and reputation. It requires a released application in the intended distribution form. A newly public project has no guaranteed entitlement to signing. If an unsigned development preview is prepared to document the package format, label it explicitly as unsigned and do not claim it will run on protected Windows devices. No adoption, download count, release or independent review should be invented for an application.

The proposed maintainer/reviewer/approver is [kvkv602-crypto](https://github.com/kvkv602-crypto). See the [code signing policy](../CODE_SIGNING_POLICY.md), [privacy policy](../PRIVACY.md) and [third-party notices](../THIRD_PARTY_NOTICES.md). The separate Store/MSIX preparation is retained but is not the selected application route.

## Build inputs and native-only payload

The source CI restores official Node.js 24.22.1 and Microsoft.Web.WebView2 SDK 1.0.4314.35. The archive digests, expected official Node executable digest and Action commits are recorded in [dependency-lock.json](dependency-lock.json). Vendor signatures and notices must be preserved. The environment-specific development runtime is not used as a release dependency.

The proposed SignPath payload uses the same explicit application-file allowlist as MSIX preparation, plus a strict runtime marker requiring the native helper. The marker's internal filename/format contains `store`; in this release it only enforces the native-helper requirement and does not make the executable an MSIX or a Microsoft Store product. The payload excludes legacy `key-vault.ps1` and `notify.ps1` scripts, development launchers, tests and build inputs. The 1.3 launcher performs DPAPI and notification operations through its bounded native bridge. A missing or denied bridge fails closed.

The pinned SDK assemblies and x64 loader are build/runtime libraries; they are not the installed Microsoft Edge WebView2 browser Runtime. The latter remains a separate system prerequisite with its own terms.

## Two separate signing stages

The self-extracting format includes a payload ZIP and a manifest of exact inner bytes. Signing the inner executable after sealing those hashes would invalidate the package. Signing only the outer executable would leave the inner launcher unsigned.

The proposed manual workflow therefore has these stages:

1. `Prepare-SignPathPayload.ps1` compiles the source without starting the app and prepares the exact native-only payload. Upload its own `DomTrack-UA.exe` as an immutable GitHub workflow artifact for the first SignPath configuration.
2. SignPath signs only that project executable. `Seal-SignPathOuter.ps1` checks the returned file set, expected signer, Windows trust, timestamp and unchanged executable content outside allowed Authenticode fields. It inserts the signed launcher into the payload and seals the manifest/ZIP before compiling the outer package.
3. Upload the outer executable as another immutable artifact. A separate SignPath configuration signs it.
4. `Verify-SignPathRelease.ps1` checks the returned outer file, trusted expected signature and package resources as data, and creates a release candidate with final hashes. It does not start the app, install it or publish a GitHub Release.
5. Perform the Windows integration acceptance checks on an allowed device before publishing the candidate. A successful service signature does not prove GUI startup, update, DPAPI or notification behavior.

SignPath signs only the project's own executables. Node.js and Microsoft files keep their upstream signatures. The remote artifact configurations describe the ZIP wrapper produced by GitHub artifact upload and the exact expected executable and metadata; the custom self-extracting resources are not assumed to be automatically understood or recursively signed by the service.

The old `app/launcher/Build-OneFile.ps1` is a separate local-certificate builder. A service certificate's thumbprint is not a locally available private key and must not be substituted into that builder as though it performed cloud signing.

## Configuration after service approval

Do not populate configuration with guessed example values. Obtain the real organization ID, project slug, separate inner/outer artifact-configuration and signing-policy slugs, and the expected signer certificate thumbprint from the approved service project. The thumbprint identifies the expected signer; successful Windows trust and timestamp checks are also required.

Install the official SignPath GitHub App with access limited to this repository and link the GitHub.com trusted build system to the approved SignPath project. Store its submitter API token only as `SIGNPATH_API_TOKEN` in the protected GitHub release environment. This token is unrelated to the user's RIA data-source key. The signing private key stays in the signing service; no PFX, private key or certificate password belongs in the repository.

Configure the `signpath-release` GitHub environment's actual branch/deployment restrictions and required human approval, together with the SignPath approval policy and GitHub branch protections. Merely naming an environment in YAML does not create those protections. Multifactor authentication and the final human roles must be confirmed during onboarding. A workflow guard is not a substitute for a service policy or repository access control.

The workflow is manual and restricted to its reviewed main-branch configuration. It has no automatic release publication step and no push/PR/tag trigger. Never expose signing credentials to fork PRs or use an arbitrary user-supplied workflow revision to sign a release.

## Provenance and acceptance limits

GitHub-hosted `windows-2025` is an evolving runner image, not an immutable compiler environment. Build evidence records its available provenance; fixed input hashes do not establish bit-for-bit reproducibility across all runner updates or signature timestamps.

Default source tests mock Windows integrations. They do not exercise real user keys, DPAPI, desktop notifications or the final installed/extracted application. A current passing source/build report must accompany any candidate, with its remaining integration checks explicitly recorded. Current preparation is not evidence of actual SignPath approval or a successful remote signed build.

## Primary references

- [Foundation terms](https://signpath.org/terms.html) and [application](https://signpath.org/apply.html).
- [Official GitHub trusted-build integration](https://docs.signpath.io/trusted-build-systems/github), [artifact configuration syntax](https://docs.signpath.io/artifact-configuration/syntax) and [reference](https://docs.signpath.io/artifact-configuration/reference).
- [Official Node.js release](https://nodejs.org/en/blog/release/v24.22.1/) and [checksums](https://nodejs.org/dist/v24.22.1/SHASUMS256.txt).
- [Official WebView2 SDK package](https://www.nuget.org/packages/Microsoft.Web.WebView2/1.0.4314.35).
