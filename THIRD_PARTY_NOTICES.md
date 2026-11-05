# Third-party components

This inventory describes the reviewed build inputs for DomTrack UA 1.3.0. The project's own code is licensed under the approved MIT LICENSE. Changing the project's license does not change any upstream license. Preserve the upstream license and notice files.

| Component | Version / provenance | License and distribution treatment |
|---|---|---|
| Node.js | 24.22.1, restored from the pinned official `nodejs.org` Windows x64 archive | Its license and bundled component notices are restored as `app/runtime/LICENSE.node.txt`. CI verifies the fixed archive/executable digests and the vendor signature. The old development executable copied from the Codex environment is excluded and is not an upstream release dependency. |
| Microsoft.Web.WebView2 SDK | 1.0.4314.35, pinned official NuGet package; net462 Core/WinForms assemblies and x64 loader | The package's `LICENSE.txt` contains BSD-style three-clause redistribution terms. Preserve it and the complete `NOTICE.txt`, verify the package digest and vendor signatures, and retain Microsoft's signatures. The project license does not replace these terms. |
| Microsoft Edge WebView2 Runtime | Installed separately, not bundled in the application payload | Separate Microsoft system component with its own terms and privacy behavior. It is not the SDK package above and is not relicensed under the project's MIT license. The application does not silently install it. |
| parse5 | 7.3.0, vendored official npm package | MIT; preserve `app/lib/vendor/node_modules/parse5/LICENSE` and the source provenance in `app/lib/vendor/NOTICE.md`. |
| entities | 6.0.1, vendored official npm package used by parse5 | BSD-2-Clause; preserve `app/lib/vendor/node_modules/entities/LICENSE` and the vendored provenance notice. |
| .NET Framework and Windows APIs | Platform prerequisites | System components, not project-owned code. The application configuration targets .NET Framework 4.6.2; compatibility of the final distribution still requires acceptance testing. |

The WebView2 notice includes additional upstream components; retaining only its top-level license is insufficient. Restored build outputs include `runtime/webview2/LICENSE.txt`, `runtime/webview2/NOTICE.txt`, `runtime/webview2/ORIGIN.txt`, `runtime/NOTICE.txt` and `runtime/LICENSE.node.txt`. The source tree deliberately does not check in the official runtime binaries; [dependency-lock.json](ci/dependency-lock.json) and [Restore-Dependencies.ps1](ci/Restore-Dependencies.ps1) describe the fixed inputs.

The vendored HTML parsers are used to construct inert document trees, not to execute website scripts. Their original package licenses are kept byte-for-byte; package installation scripts are not part of the release build.

Official sources:

- [Node.js distributions](https://nodejs.org/dist/).
- [WebView2 SDK 1.0.4314.35](https://www.nuget.org/packages/Microsoft.Web.WebView2/1.0.4314.35).
- [parse5 7.3.0](https://registry.npmjs.org/parse5/7.3.0).
- [entities 6.0.1](https://registry.npmjs.org/entities/6.0.1).

Rieltor, DOM.RIA, OBYAVA, NBU and OpenStreetMap are external data/services, not code owned by this project. Public accessibility does not grant a license to republish entire listing datasets or site HTML. Public tests should use synthetic or minimal fixtures with appropriate reuse rights; user databases, saved pages, raw scraped datasets and private search notes are excluded.

SignPath Foundation decides whether the complete project's dependencies and distribution satisfy its eligibility conditions. This inventory does not assert its approval, endorsement or a right to use its certificate.
