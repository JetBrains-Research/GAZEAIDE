# GAZEAIDE 1.0.0 validation

Release checks on 5 November 2026 used macOS, Node.js 24.13.1 and the IntelliJ IDEA 2026.2.1 SDK with its bundled JBR.

| Check | Result |
| --- | --- |
| Java/component tests | 132 passed, no failures or skips |
| Standalone tests | 40 passed, no failures or skips |
| Plugin JavaScript tests | 17 passed, no failures or skips |
| Plugin build and structure verification | Passed |
| Independently extracted standalone | 40 tests passed |
| Standalone manifest and packaged assets | SHA-256 and source equality verified |
| Local documentation links | Resolved in the source tree and portable standalone |

These are 189 distinct automated tests; extracted-folder execution repeats the standalone suite. Transport tests use loopback HTTP and local stdio fixture subprocesses with synthetic replies. They make no model request and collect no participant data. Existing IntelliJ/JCEF deprecation warnings remain.

## Covered reliability behavior

- Both editions select the requested historical window before applying the 5,000-visit context cap. Shared golden fixtures check the context policy across Java and JavaScript.
- Calibration retains completed targets, extends insufficient collection up to six seconds and pauses after three failed attempts. Explicit retry batches can continue without skipping required training or independent check targets. Cancellation retains the previous saved fit.
- Standalone libraries permit one running owner per canonical data folder. Tests cover second launches, symbolic-link aliases, occupied ports, concurrent startup/reclamation and interrupted initialization.
- Fast Codex completions and disconnections clear active-turn state while preserving research linkage.
- Remote-upload tests check the reduced allowlisted schema and reject sensitive fields. Bundled examples and transport fixtures are synthetic.

## Manual validation still required

Physical webcam accuracy, camera permission and real tracking recovery require checks on the intended machine. Native IntelliJ calibration controls and JetBrains AI handoff require an in-IDE check. Automated tests do not establish human usability, research efficacy, improved assistant answers or equivalent behavior across all operating systems and IDE versions.

Webcam dependencies require external runtime/model downloads unless cached. Calibration depends on display/window geometry and cannot be transferred between editions. Preserve `WEBCAM`, `MOUSE_PROXY` and `MOUSE_SIMULATION` labels in research analyses.

See the build/test guide for repeatable commands and the privacy guide before collecting or sharing data.

[Build and test](BUILD_AND_TEST.md) · [Manual IntelliJ checks](local-testing.md) · [Privacy](../PRIVACY.md)
