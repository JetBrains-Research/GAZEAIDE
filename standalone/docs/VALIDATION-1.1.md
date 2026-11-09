# GAZEAIDE 1.1 development validation

Checked on macOS, 6 November 2026, using Node.js 24.13.1 and the installed IntelliJ IDEA 2026.2.1 JBR/SDK. This records software checks for the unreleased 1.1 source/builds; the published 1.0 release has its own validation record.

- **138 Java/component tests:** pass, including the native wizard, cross-session persistence, legacy records, blink-only export/context, and a timestamped eye-observation fixture matched to standalone.
- **70 JavaScript tests:** pass (50 standalone, 20 plugin-side bridge/receiver/tracker tests). New cases cover bilateral blinks, winks/uncertainty, threshold noise, interruptions, sparse frames, duplicate timestamps, duration bounds, minute boundaries, missing versus zero observations, reference isolation/future exclusion, context bounds/opt-out, authenticated persistence, CSV safety, and inference scheduling.
- **Plugin packaging:** Gradle `test buildPlugin verifyPluginStructure` succeeds against the local SDK. Existing IntelliJ API deprecation warnings remain; this is not a compatibility verification against all IDE versions.
- **Portable standalone:** packaged source passes all 50 standalone tests from its staging folder. Its manifest records each bundled file's size/hash; the ZIP has a separate SHA-256 file. Tracker/model and receiver copies match their asset provenance hashes.
- **Browser UI:** an isolated loopback app with synthetic recordings displays a stored blink-only recording and personal-reference indicator, exports blink CSV, rejects mouse input for blink collection, and completes ordinary recording/pause/resume/stop/save. No browser warning/error logs were observed in that check. The user's running app/library was not used for QA.

These tests do not validate actual blink detection, webcam timing/accuracy, fatigue/workload inference, human usability, native IDE camera operation or improved agent answers. A physical webcam/JCEF check and independent physiological/construct validation remain necessary. The indicator is an exploratory closure-deviation heuristic with provisional thresholds. See [blink measurements, workflow and validation requirements](BLINK_ACTIVITY.md).

Reproduce the software checks using [build instructions](BUILD_AND_TEST.md) in the repository. In a portable standalone folder run `node --test test/*.test.mjs`. No real participant recordings, camera frames, assistant requests or private source are needed for these tests.
