# Build and test

## Standalone

From the repository root, with Node.js 22+:

```sh
node --test standalone/test/*.test.mjs src/test/js/*.test.mjs
node standalone/build-release.mjs
```

The second command creates `standalone/artifacts/gazeaide-standalone-1.1.0.zip`, an extracted staging folder and a SHA-256 file. The portable ZIP includes source, tracker/model assets, notices, documentation and tests; `.local`, tokens and QA output are excluded. Test the extracted folder independently with `node --test test/*.test.mjs`.

## IntelliJ plugin

Use an installed JDK/JBR and the checked-in Gradle wrapper. `JAVA_HOME` must point to the JDK home. Example on macOS; substitute your installed IDE path:

```sh
./gradlew '-PlocalIdePath=/path/to/IntelliJ IDEA.app/Contents' test buildPlugin verifyPluginStructure
```

The output is `build/distributions/implicit-gaze-1.1.0.zip`. The build does not install or launch the IDE. Dependencies/Gradle may need downloading on a fresh machine; use `--offline` only after they are cached. Without `localIdePath`, the build downloads its configured default SDK; the release evidence uses the installed 2026.2.1 SDK rather than asserting every IDE was tested.

On Windows use `gradlew.bat` and the corresponding installed IDE directory. On Linux use the installation directory containing the IDE SDK. These platforms need their own runtime/device validation.

## Release verification

A coordinated 1.0 build reruns 132 Java/component tests, 40 standalone tests and 17 plugin JavaScript tests. Check actual results before publishing. Plugin validation includes ZIP/descriptor/structure checks. Portable validation includes all manifest hashes and extracted-folder tests. Fixture transport tests use synthetic local subprocesses; they do not measure gaze accuracy or assistant efficacy.

Physically test camera permission, face tracking, calibration retry/cancel/save and line accuracy. In IntelliJ also test the native dialog and AI handoff. Record environment/version and any untested platforms with the release. Preserve `WEBCAM`, `MOUSE_PROXY` and `MOUSE_SIMULATION` labels.

See [release validation](VALIDATION-1.0.md), [manual IntelliJ checks](local-testing.md) and [privacy](../PRIVACY.md).

The [current 1.1 validation record](VALIDATION-1.1.md) reports 138 Java/component, 70 combined JavaScript and 50 portable standalone tests. The build adds timestamped synthetic blink fixtures shared by Java and JavaScript, lifecycle/quality/reference tests and eyelid/gaze scheduling tests. Physical webcam accuracy and construct validity remain unverified. See [blink activity](BLINK_ACTIVITY.md).
