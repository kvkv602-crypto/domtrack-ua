# Code signing policy

This is a private project. No SignPath Foundation application has been submitted, no free Foundation signing eligibility is claimed, and no signed release exists.

- Owner: kvkv602-crypto. Only the human owner may authorize a publisher identity, signing credential, release or external application.
- Preserve upstream publisher signatures and notices. The official pinned Node.js and WebView2 SDK are external build inputs, never a copy of a private tool runtime.
- Sign own executables/scripts before computing payload hashes. For the self-extracting format, sign the outer executable after sealing the already-signed payload; compute final download hashes last.
- A policy-denied component fails closed. No inline retry, trust-root insertion, protection change or repackaging is a substitute for legitimate publisher trust.
- Manual approval and an enforced-device test of startup, upgrade, vault and notifications are needed before calling an artifact a Windows release.
- No CI job automatically requests signatures, publishes releases or consumes hosted-runner minutes on repository pushes.

Private source hosting does not fix Windows signing. Free SignPath Foundation support requires an eligible open-source project; a different signing/distribution route must be selected while this repository remains private.

The selected route is MSIX through Microsoft Store, initially with Private audience. Store submission requires a verified publisher and exact product identity; new personal registration requires ID/selfie verification by the human owner. The unsigned source folder and any unsigned submission package are preparation artifacts, never a trusted alternative startup path. Store certification/signing and testing of the actual installed package remain required. No Store submission, identity verification or hosted CI run is performed automatically.
