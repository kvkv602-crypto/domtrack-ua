# Code signing policy

## Current status

DomTrack UA is a public MIT-licensed source project preparing an application to SignPath Foundation. The project's own code is covered by [LICENSE](LICENSE). No Foundation application, service approval, production signing configuration or signed release exists yet.

The following attribution is reserved for an accepted and configured Foundation project and must not be presented as a current endorsement: “Free code signing provided by [SignPath.io](https://signpath.io), certificate by [SignPath Foundation](https://signpath.org).”

## Responsibilities

The proposed maintainer, committer, reviewer and release approver is [kvkv602-crypto](https://github.com/kvkv602-crypto). No additional reviewer or organization is implied. Roles, multifactor authentication and the final signing policy must be confirmed during the Foundation's onboarding. A change from a contributor who is not a committer requires review before it enters a release. Signing requests require explicit human approval under the configured service policy.

## Release boundaries

- Build the reviewed source on GitHub-hosted runners and retain its immutable workflow artifacts and commit identity. Restrict the signing workflow to the approved repository, branch and protected release environment.
- Obtain the signing identity and artifact policies from the service after approval. Keep submitter credentials only in the protected GitHub environment; do not store a signing private key, token or certificate password in source, chat, output files or build arguments.
- Sign the project's inner `DomTrack-UA.exe`, validate the returned bytes and trusted timestamped signature, and then construct the payload manifest from those signed bytes. Sign and verify the outer self-extracting executable in a separate stage. Record final download hashes only after outer signing.
- Preserve official Node.js and Microsoft WebView2 signatures and their notices. Do not apply the project's signing identity to upstream binaries. The native-only release payload excludes the legacy PowerShell helper scripts.
- Treat signature or helper failures as failures. Do not insert a trust root, relax Windows protections, or retry a denied component through a different interpreter to manufacture a successful launch.
- Before publication, review the complete signed candidate and perform clean-start, shutdown, update, key-storage and notification checks on an allowed Windows system with the intended protections enabled. Source tests and a valid signature alone are not evidence that these integrations work.

Workflows are started manually; a push, pull request or tag does not automatically sign or publish a release. A prepared artifact is not an end-user release until its acceptance checks and publication review are complete.

## Privacy commitment

The application does not upload its user's database, private notes or API key to the maintainer. Network access serves the user's selected listing searches, configured watches, currency lookup, map viewing and explicit external-link actions. The optional API key goes only to the official RIA API. See the [privacy policy](PRIVACY.md), including local unencrypted listing storage and third-party service behavior.

The Foundation's [terms](https://signpath.org/terms.html) and [application process](https://signpath.org/apply.html) govern eligibility. This document does not assert acceptance or a right to use its certificate.
