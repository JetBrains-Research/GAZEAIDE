# Build and test the standalone edition

Install Node.js 22+ and extract the standalone ZIP. No npm dependency installation is needed. From the extracted folder:

```sh
node --test test/*.test.mjs
node build-release.mjs
```

The first command runs 68 standalone tests. Loopback sockets and local fixture subprocesses must be permitted. Tests use synthetic replies; no Codex account, webcam or model request is needed.

The second command creates `artifacts/gazeaide-standalone-1.1.0.zip`, a staging folder and a SHA-256 file. `MANIFEST.json` lists included files and their SHA-256 hashes. The archive excludes `.local/` and runtime records. Re-extract it into a fresh folder and run the test command there to check portable operation.

In the complete GAZEAIDE source repository, use `node --test standalone/test/*.test.mjs src/test/js/*.test.mjs` and `node standalone/build-release.mjs` from its root. The root build/test guide covers the IntelliJ plugin.

For a manual check, follow [the first-run guide](GETTING_STARTED.md). Test physical webcam permission, calibration retry/cancel/save, tracking recovery and accuracy on the intended machine. Check optional Codex sign-in and explicit sends using public/synthetic text. These checks are separate from automated fixture tests; see [validation and limitations](VALIDATION-1.1.md) and [privacy](../PRIVACY.md).
