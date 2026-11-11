# Build and test

## Standalone

From the repository root, with Node.js 22+:

```sh
node --test standalone/test/*.test.mjs src/test/js/*.test.mjs
node scripts/sync-version.mjs --check
node --input-type=module -e "import {verifyAssets} from './standalone/lib/assets.mjs'; await verifyAssets();"
node standalone/build-release.mjs
```

The final command creates `standalone/artifacts/gazeaide-standalone-1.2.0.zip`, an extracted staging folder and a SHA-256 file. The portable ZIP includes source, tracker/model assets, notices, documentation and tests; `.local`, tokens and QA output are excluded. Test the extracted folder independently with `node --test test/*.test.mjs`.

## IntelliJ plugin

Use an installed JDK/JBR and the checked-in Gradle wrapper. `JAVA_HOME` must point to the JDK home. Example on macOS; substitute your installed IDE path:

```sh
./gradlew '-PlocalIdePath=/path/to/IntelliJ IDEA.app/Contents' test buildPlugin verifyPluginStructure verifyPlugin
```

The output is `build/distributions/implicit-gaze-1.2.0.zip`. The build does not install or launch the IDE. Dependencies/Gradle may need downloading on a fresh machine; use `--offline` only after they are cached. Without `localIdePath`, the build downloads the default 2023.1 SDK and verifies against IntelliJ Community 2023.1 and 2025.2. An additional local 2026.2.1 build/verifier check is recorded; physical checks on other IDE/device combinations remain separate.

On Windows use `gradlew.bat` and the corresponding installed IDE directory. On Linux use the installation directory containing the IDE SDK. These platforms need their own runtime/device validation.

## Release verification

A coordinated 1.0 build reruns 132 Java/component tests, 40 standalone tests and 17 plugin JavaScript tests. Check actual results before publishing. Plugin validation includes ZIP/descriptor/structure checks. Portable validation includes all manifest hashes and extracted-folder tests. Fixture transport tests use synthetic local subprocesses; they do not measure gaze accuracy or assistant efficacy.

Physically test camera permission, face tracking, calibration retry/cancel/save and line accuracy. In IntelliJ also test the native dialog and AI handoff. Record environment/version and any untested platforms with the release. Preserve `WEBCAM`, `MOUSE_PROXY` and `MOUSE_SIMULATION` labels.

See [release validation](VALIDATION-1.0.md), [manual IntelliJ checks](local-testing.md) and [privacy](../PRIVACY.md).

The [1.1 validation record](VALIDATION-1.1.md) includes the November 10 hardening checks and exact test counts. GitHub Actions runs both suites, asset/version checks, packaging and Plugin Verifier on pushes and pull requests. The default verifier matrix is IntelliJ Community 2023.1 and 2025.2; a supplied local IDE is verified instead. Physical webcam accuracy and construct validity remain unverified. See [hardening details](HARDENING-2026-11-10.md) and [blink activity](BLINK_ACTIVITY.md).

For a version update, edit root `VERSION` and run `node scripts/sync-version.mjs`.
Gradle generates the Java runtime version from that file; CI checks the standalone
package/browser metadata for drift. Schema versions are independent of release versions.

The [1.2 validation record](VALIDATION-1.2.md) covers the shared viewing maps, portable image/data exports and current test counts.
