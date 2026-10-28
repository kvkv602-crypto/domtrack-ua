# Third-party components — review notes

This is an inventory for the proposed public project, not a new license grant. Preserve upstream license/notice files byte-for-byte. The owner must confirm the license for DomTrack's own code before publication; the current application metadata still says `UNLICENSED`.

| Component | Observed version / provenance | License and public-build action |
|---|---|---|
| Node.js | Local bundle reports 24.22.1, copied from the Codex dependency runtime, signed by OpenAI OpCo, LLC | Node.js and bundled notices are in `runtime/LICENSE.node.txt`. Do **not** publish/reuse that environment-specific executable for OSS CI. Select an available official pinned Windows x64 release from `nodejs.org`, verify its official checksums/signature, retain its exact license/notices and record provenance. Do not claim the old binary is a reproducible official upstream download. |
| Microsoft.Web.WebView2 SDK | 1.0.4314.35; net462 Core/WinForms assemblies and x64 loader | The included Microsoft `LICENSE.txt` contains BSD-style three-clause redistribution conditions. Preserve it and the entire third-party `NOTICE.txt`. Fetch the pinned official NuGet package, verify its digest and vendor signatures, and verify that the redistributed files match that package. Do not re-sign Microsoft binaries with the project certificate. |
| Microsoft Edge WebView2 Runtime | Installed separately; not bundled by the current app | Microsoft component with its own terms/privacy behavior. Do not silently redistribute/install it or claim it is covered by the proposed project license. Document the prerequisite. |
| parse5 | 7.3.0, official npm package | MIT. Preserve `lib/vendor/node_modules/parse5/LICENSE`. Pin archive/integrity in the public build and do not run package install scripts. |
| entities | 6.0.1, official npm package; parse5 dependency | BSD-2-Clause. Preserve `lib/vendor/node_modules/entities/LICENSE`. Pin archive/integrity and do not run package install scripts. |
| .NET Framework / Windows APIs | Platform prerequisites | System components; no claim of ownership or relicensing. Record minimum supported versions. |

The original WebView2 package also includes third-party notices (including ANTLR and StringTemplate); keeping only the top-level Microsoft license is insufficient. The reviewed local provenance files are `runtime/webview2/ORIGIN.txt`, `runtime/webview2/LICENSE.txt`, `runtime/webview2/NOTICE.txt`, `runtime/NOTICE.txt` and `lib/vendor/NOTICE.md`.

Public package sources to pin and verify:

- Node.js: https://nodejs.org/dist/
- WebView2 SDK: https://www.nuget.org/packages/Microsoft.Web.WebView2/1.0.4314.35
- parse5: https://registry.npmjs.org/parse5/7.3.0
- entities: https://registry.npmjs.org/entities/6.0.1

Rieltor, DOM.RIA, OBYAVA, NBU and OpenStreetMap are external data/services, not code owned by this project. Public accessibility is not a license to republish entire listing datasets or site HTML. Use synthetic/minimal fixtures for the public tests; exclude downloaded pages, private records and scraped datasets unless their separate reuse rights have been established.

SignPath's final dependency/project eligibility decision belongs to the Foundation. This inventory does not assert approval, endorsement or a right to use its certificate.