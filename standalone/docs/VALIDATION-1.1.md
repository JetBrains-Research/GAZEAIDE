# GAZEAIDE 1.1.0 validation

Checked on macOS, 6 November 2026, using Node.js 24.13.1 and the installed IntelliJ IDEA 2026.2.1 JBR/SDK. These original development checks are retained below alongside the November 10 release checks. The 1.0 release has its own validation record.

- **138 Java/component tests:** pass, including the native wizard, cross-session persistence, legacy records, blink-only export/context, and a timestamped eye-observation fixture matched to standalone.
- **70 JavaScript tests:** pass (50 standalone, 20 plugin-side bridge/receiver/tracker tests). New cases cover bilateral blinks, winks/uncertainty, threshold noise, interruptions, sparse frames, duplicate timestamps, duration bounds, minute boundaries, missing versus zero observations, reference isolation/future exclusion, context bounds/opt-out, authenticated persistence, CSV safety, and inference scheduling.
- **Plugin packaging:** Gradle `test buildPlugin verifyPluginStructure` succeeds against the local SDK. Existing IntelliJ API deprecation warnings remain; this is not a compatibility verification against all IDE versions.
- **Portable standalone:** packaged source passes all 50 standalone tests from its staging folder. Its manifest records each bundled file's size/hash; the ZIP has a separate SHA-256 file. Tracker/model and receiver copies match their asset provenance hashes.
- **Browser UI:** an isolated loopback app with synthetic recordings displays a stored blink-only recording and personal-reference indicator, exports blink CSV, rejects mouse input for blink collection, and completes ordinary recording/pause/resume/stop/save. No browser warning/error logs were observed in that check. The user's running app/library was not used for QA.

These tests do not validate actual blink detection, webcam timing/accuracy, fatigue/workload inference, human usability, native IDE camera operation or improved agent answers. A physical webcam/JCEF check and independent physiological/construct validation remain necessary. The indicator is an exploratory closure-deviation heuristic with provisional thresholds. See [blink measurements, workflow and validation requirements](BLINK_ACTIVITY.md).

Reproduce the software checks using [build instructions](BUILD_AND_TEST.md) in the repository. In a portable standalone folder run `node --test test/*.test.mjs`. No real participant recordings, camera frames, assistant requests or private source are needed for these tests.


## November 10 recording/security hardening

Checked with Node.js 24.13.1 on macOS and the installed IntelliJ JBR. See
[the hardening notes](HARDENING-2026-11-10.md) for implemented scope, compatibility,
recovery bounds and deferred work.

- **146 Java/component tests:** pass. New cases cover append-journal/torn-tail recovery,
  active checkpoints, canonical/legacy input provenance, incomplete export flags,
  language heuristics, blink cadence and unreadable upload entries.
- **88 JavaScript tests:** pass (68 standalone and 20 plugin-side). Coverage adds
  100,128-visit append persistence with a shared 6.6 KB block stored once in the
  journal, lost-response retries, HTTP/restart recovery, access-key isolation,
  report escaping, new import IDs, monotonic clocks, tracking gaps, wrapped-row
  geometry, text-offset contracts, lock recovery and incomplete export flags.
- **Plugin packaging/compatibility:** `./gradlew test buildPlugin
  verifyPluginStructure verifyPlugin` passes with the default 2023.1 SDK.
  Plugin Verifier reports compatible with IC-231.8109.175 (2023.1) and
  IC-252.23892.409 (2025.2); scheduled-removal/deprecation warnings remain.
  A local-SDK build/verifier check also passed against IU-262.9437.185 before the
  final additive incomplete-export labels.
- **Browser smoke checks:** mouse recording/start/stop/results worked in an
  isolated library. Bundled ONNX Runtime/EyeMU and MediaPipe/face-landmarker
  initialization also succeeded without opening a camera. Temporary QA pages
  were removed from the source and portable package.
- **Fresh portable extraction:** all 68 standalone tests pass from a newly extracted ZIP.
  All 70 release-manifest entries and the plugin's 11 bundled runtime assets match
  their recorded sizes/SHA-256 hashes.
- **Version/runtime checks:** canonical-version consistency and all bundled runtime
  manifest sizes/SHA-256 hashes pass. Portable packaging retains those checksums
  and excludes tokens, local recordings and temporary QA pages.

Reproduce the JavaScript checks from the repository root with
`node --test standalone/test/*.test.mjs src/test/js/*.test.mjs`; from a freshly
extracted standalone ZIP use `node --test test/*.test.mjs`. CI runs those checks,
portable packaging and the default Plugin Verifier matrix on pushes/pull requests.
No physical webcam, multi-monitor device validation, real assistant request,
participant data collection or physiological accuracy evaluation was performed.
