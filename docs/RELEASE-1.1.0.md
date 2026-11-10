GAZEAIDE 1.1.0 improves recording reliability, local access security and measurement consistency in the IntelliJ plugin and standalone browser companion. It also includes optional webcam blink observations and personal rested references. The original v1.0.0 release remains available unchanged.

## Downloads and installation

- **IntelliJ plugin:** install `implicit-gaze-1.1.0.zip` intact through **Settings → Plugins → gear → Install Plugin from Disk**. GitHub's automatic source archives are not installable plugins.
- **Standalone:** extract `gazeaide-standalone-1.1.0.zip`, install Node.js 22+, and run `node launch.mjs` in the extracted folder. Open the private launch URL printed in the terminal and keep its access key private. Try the sample conversation with Mouse proxy; no webcam, AI account or npm dependency installation is needed for that first test.
- **Source:** `gazeaide-1.1.0-source.zip` contains both editions, build tools, tests, bundled runtime/model assets, instructions and license notices.
- **Checksums:** `SHA256SUMS` covers all three ZIPs. On macOS, run `shasum -a 256 -c SHA256SUMS`; on Linux, use `sha256sum -c SHA256SUMS` after downloading all three ZIPs to the same folder.

## Changes

- Recordings are journaled during capture and recovered after interruption. Standalone visits use bounded, retryable append batches and share region snapshots, avoiding the old whole-session checkpoint size limit.
- The public start page no longer exposes the local API capability. Imports receive new IDs, report fields are escaped, and abandoned locks/damaged upload entries no longer block unrelated work.
- Webcam runtime/model dependencies are bundled with verified SHA-256 manifests, allowing offline startup.
- Brief tracking gaps pause region dwell; word attribution, wrapped-row targeting, browser text columns, mouse scrolling, simulation metadata and exported input provenance are corrected. Frame-read timestamps propagate through asynchronous predictions, and durations use monotonic clocks.
- Opt-in blink activity includes events, minute windows, quality flags and frozen personal rested references. Policy v2 requires at least 25 valid observations/second alongside the other quality gates. The closure-deviation indicator remains experimental.
- CI runs both test suites, version/runtime checks, portable packaging and JetBrains Plugin Verifier. Plugin startup now uses `ProjectActivity`.

## Compatibility and recovery

New local/exported recordings use schema 4 and explicit `WEBCAM`, `MOUSE_PROXY` or `MOUSE_SIMULATION` provenance. Legacy recordings remain readable. Recovered recordings retain an incomplete flag; unfinished sessions and older blink policies are excluded from new personal references. Stop older application instances before opening the same data folder with this release.

Recovery is bounded: a crash can still lose queued writes and the interval since the last active checkpoint. Active visits checkpoint every second in the plugin and every five seconds in standalone. Imports retain the legacy size limits; new append recordings avoid those whole-session limits. Large loaded recordings still require memory proportional to their size. The plugin constrains webcam recording to the primary display.

## Validation and limits

**234 distinct automated tests pass:** 146 Java/component tests and 88 JavaScript tests (68 standalone, 20 plugin-side). The 68 standalone tests also pass from a fresh portable ZIP extraction. Plugin build/structure checks and Plugin Verifier pass for IntelliJ Community 2023.1 and 2025.2. Bundled runtime/package hashes and a camera-free browser smoke check pass; a separate browser check initialized the bundled inference models without opening a camera.

These checks do not establish physical webcam accuracy, blink precision/recall, fatigue or workload inference, native AI handoff, human usability or improved assistant answers. Validate the intended research setup independently. Raw-sample recording and a broad PSI-based region rewrite remain deferred. Runtime bundling makes the ZIPs larger than 1.0.0.

[Getting started](https://github.com/JetBrains-Research/GAZEAIDE/blob/v1.1.0/docs/GETTING_STARTED.md) · [Hardening details](https://github.com/JetBrains-Research/GAZEAIDE/blob/v1.1.0/docs/HARDENING-2026-11-10.md) · [Validation](https://github.com/JetBrains-Research/GAZEAIDE/blob/v1.1.0/docs/VALIDATION-1.1.md) · [Blink measurements and limits](https://github.com/JetBrains-Research/GAZEAIDE/blob/v1.1.0/docs/BLINK_ACTIVITY.md) · [Privacy](https://github.com/JetBrains-Research/GAZEAIDE/blob/v1.1.0/PRIVACY.md)

GAZEAIDE was started by [Heidi Albarazi](https://heidialbarazi.me/) during her internship at JetBrains Research and subsequently updated by Ilia Zaharov. Project code is GPL-3.0-only; bundled dependencies retain their separate upstream terms and notices.
