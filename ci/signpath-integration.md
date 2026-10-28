# Private-project status

The owner selected private source hosting. The free SignPath Foundation route below is not applicable under the current visibility. This is retained technical reference only; no account, token, signing policy or request is configured.

# Source CI and proposed SignPath integration

Draft prepared on 2026-10-28. No repository, signing account, request or release has been created by these files. There is no SignPath approval or certificate yet.

## Repository layout and current source CI

Use `app/` for reviewed app sources, `ci/` for these scripts, and copy `build.yml` to `.github/workflows/build.yml`. Do not copy the old EXEs, local Codex runtime, SQLite databases, encrypted keys, logs or registration material into the public repository. Keep the vendored parse5/entities sources and their original notices.

The proposed source job restores Node.js 24.22.1 and WebView2 SDK 1.0.4314.35 from their official origins. Their checksums are fixed in `dependency-lock.json`, rather than fetched anew and blindly trusted on each build. The official Node executable has a different digest from the local Codex/OpenAI-signed runtime. The local runtime must not be used as the public dependency.

The job runs JS fixture tests with real DPAPI explicitly disabled, then compiles the inner Windows launcher. Its uploaded folder is labelled **unsigned development evidence**, not a release. It does not start the application, its GUI, or native test helpers. Real DPAPI, notifications, native loader/upgrade integration and the final packaged GUI remain separate acceptance checks on an allowed test system. The workflow has no signing secret and cannot publish a release.

Before enabling CI, align `app/package.json.version` with both C# file versions and `server.cjs`. The original package.json says 1.0.0 while the current code says 1.2.0; the source build deliberately refuses that mismatch. Adopt the owner's approved OSS license before describing the repository as open source.

`windows-2025` is a GitHub-hosted image family, not an immutable toolchain. The compiler and runner image versions are recorded in `ci-provenance.json`. These files establish pinned dependency inputs and a traceable build; they do not claim bit-for-bit reproducibility across runner updates, or across signatures with different timestamps.

## Why the SFX needs two signing phases

The current single-file EXE embeds `DomTrack.Payload` and `DomTrack.Manifest`; the latter contains hashes of all inner files. Signing an inner EXE or PowerShell script after those hashes are sealed would invalidate the package. Signing only the outer EXE would leave the inner launcher and helpers untrusted.

The proposed release sequence is:

1. Build the reviewed inner runtime folder in a GitHub-hosted job. Upload it as an immutable workflow artifact.
2. Submit that artifact to the approved SignPath **payload** configuration. Sign only the project's own `DomTrack-UA.exe`, `lib/key-vault.ps1` and `lib/notify.ps1`. Verify existing Node/WebView2 signatures and retain their publishers. SignPath supports separate PE and PowerShell file elements; do not apply the project certificate to third-party code.
3. Download the approved signed result into a new staging directory. Check allowed paths, certificate trust, expected publisher and timestamps. Reject missing/unexpected files. Seal the manifest and ZIP only from these signed bytes.
4. Compile the outer SFX with those exact resources. Upload the resulting outer EXE as a second workflow artifact and request signing under a separate **outer** configuration.
5. Verify the final outer signature and its embedded resource hashes without loading executable code. Run clean-install, upgrade, key-vault, notification and GUI acceptance under Smart App Control enforcement on an allowed test system. Only then publish the final SHA-256 and signed release.

The current `Build-OneFile.ps1 -BuildMode Release` uses a locally available owner-selected signing certificate. It is **not a SignPath client**, and a service certificate's thumbprint cannot substitute for a private key on this computer. Before enabling release automation, its compilation/sealing stages must be exposed as the two service-signing phases above. The source-CI workflow intentionally does not pretend that this refactor already exists. If a development SFX is tested in an allowed runner, pass the explicit repository-local `-BuildDirectory` supported by the reviewed builder; do not use the old `../../../work` assumption.

## Required owner/service configuration

All of these remain unset until real project approval:

- SignPath organization ID and project slug.
- Separate reviewed payload/outer artifact-configuration slugs and signing-policy slugs.
- SignPath GitHub App access to the intended repository and the GitHub.com trusted-build-system link.
- An approved submitter's `SIGNPATH_API_TOKEN`, stored only in a protected GitHub environment secret; never in this repository, chat or build arguments printed to logs.
- Named code reviewers and release approvers, project metadata restrictions and the expected signing certificate identity.
- Protected release rules and explicit human signing approval. No fork PR or unreviewed branch may receive signing credentials.

SignPath's documented action is `signpath/github-action-submit-signing-request`; the current v3 commit is recorded in the lock file. Its required input names are `api-token`, `organization-id`, `project-slug`, `signing-policy-slug` and `github-artifact-id`; `artifact-configuration-slug` chooses the reviewed format. The `github-artifact-id` must come from the preceding `actions/upload-artifact` output, not a hand-built local URL. The connector needs read permissions for contents and actions. Use `output-artifact-directory` to collect the signed result and `wait-for-completion` according to the approval process.

GitHub upload-artifact normally wraps the upload in ZIP; the SignPath configuration must describe that wrapper. A custom embedded SFX is not automatically a recognized nested container, so do not assume a single outer signing request signs its resources. The private key stays in SignPath's service/HSM; the repository never receives a PFX or private key.

## Primary sources

- [Official Node.js 24.22.1 release](https://nodejs.org/en/blog/release/v24.22.1/) and [checksums](https://nodejs.org/dist/v24.22.1/SHASUMS256.txt).
- [Official WebView2 NuGet package](https://www.nuget.org/packages/Microsoft.Web.WebView2/1.0.4314.35) and [immutable catalog hash metadata](https://api.nuget.org/v3/catalog0/data/2026.10.23.06.50.10/microsoft.web.webview2.1.0.4314.35.json).
- [SignPath GitHub connector](https://docs.signpath.io/trusted-build-systems/github), [artifact formats and signature verification](https://docs.signpath.io/artifact-configuration/reference), [nested signing](https://docs.signpath.io/artifact-configuration/syntax).
- [SignPath Foundation conditions](https://signpath.org/terms.html). Approval depends on licensing, maintenance, verifiable reputation and build provenance; a newly public repository is not guaranteed acceptance.
- [GitHub action pinning guidance](https://docs.github.com/en/enterprise-cloud@latest/actions/reference/security/secure-use).
