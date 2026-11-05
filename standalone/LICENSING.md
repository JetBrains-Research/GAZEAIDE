# Attribution and licensing

## Project code

GAZEAIDE's project code is licensed under the **GNU General Public License, version 3 (GPL-3.0-only)**. See [LICENSE](LICENSE) for the complete terms.

GAZEAIDE was started by **Heidi Albarazi** during her internship at **JetBrains Research**, and was subsequently updated by **Ilia Zaharov**. The project includes the original IntelliJ plugin and a standalone edition of the same research tool.

The IntelliJ edition derives from [Heidi Albarazi's original Implicit / GazeAIDE plugin](https://github.com/wedalb/implicit-gaze-triggered-ai-assistance-for-code-review-in-the-jetbrains-ide), source base `83ca0e5ce9db9f48b21241fb0eb1132b574d67c1`. The standalone ports its domain/tracking workflow and replaces IntelliJ services/UI with a Node/browser/Codex host. Subsequent research UI, context, recording and recovery changes are described in [the changelog](CHANGELOG.md). Retain this attribution and the dependency notices when distributing copies.

## Third-party code and model assets

The project license does not relicense third-party assets. Their original terms remain in effect:

- UnitEye browser code: [wgnrto/uniteye](https://github.com/wgnrto/uniteye), GPL-3.0 as declared upstream. Local adapter modifications are listed in the edition-specific notices.
- EyeMU gaze model: [FIGLAB/EyeMU](https://github.com/FIGLAB/EyeMU), GPL-2.0 as declared upstream, inherited through UnitEye as a distinct model asset. The model is not relabelled GPL-3.0 by this release.
- The full UnitEye and EyeMU license texts, attribution and upstream source links are retained in both editions. Preserve the source/corresponding-source obligations of the applicable licenses; a model file is not proof of permission to relicense another work.
- At runtime the tracker loads ONNX Runtime Web (MIT) and MediaPipe Tasks Vision (Apache-2.0), plus the face-landmarker model, from the pinned upstream/CDN locations. Those dependencies retain their upstream terms.

See [third-party notices](THIRD-PARTY-NOTICES.md) and [NOTICE.md](NOTICE.md). The standalone ZIP includes `LICENSE`, `NOTICE.md` and separate webcam license files at its root.
