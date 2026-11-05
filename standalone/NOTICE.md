# GAZEAIDE attribution

GAZEAIDE 1.0.0 project code: GNU General Public License, version 3 (GPL-3.0-only). The complete project license is included as LICENSE (or META-INF/LICENSE.txt in the plugin JAR).

Started by [Heidi Albarazi](https://heidialbarazi.me/) during her internship at JetBrains Research.
Subsequently updated by Ilia Zaharov ([LinkedIn](https://www.linkedin.com/in/ilya-m-zakharov/),
[Google Scholar](https://scholar.google.com/citations?user=MTOEI-oAAAAJ&hl=en)), including the standalone edition and
research context, recording and interaction updates.

Based on Heidi Albarazi's Implicit / GazeAIDE plugin:
https://github.com/wedalb/implicit-gaze-triggered-ai-assistance-for-code-review-in-the-jetbrains-ide
Source base: 83ca0e5ce9db9f48b21241fb0eb1132b574d67c1. The source has been modified; the standalone edition adapts its workflow to a Node/browser/Codex host.

Bundled browser tracker code comes from UnitEye (Tobias Wagner et al.):
https://github.com/wgnrto/uniteye — GPL-3.0 as declared upstream.
The EyeMU model comes through UnitEye from:
https://github.com/FIGLAB/EyeMU — GPL-2.0 as declared upstream.
These third-party works retain their separate licenses; the project license does not relicense the model.

Full upstream texts and detailed notices are in gaze/LICENSE-uniteye.txt, gaze/LICENSE-EyeMU.txt and gaze/THIRD-PARTY-NOTICES.md inside the plugin JAR; the standalone includes the corresponding files at its root. Preserve attribution, modification notices, license texts and applicable corresponding source when redistributing.
