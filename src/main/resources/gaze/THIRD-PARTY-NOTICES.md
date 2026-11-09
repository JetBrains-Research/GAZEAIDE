# Third-party notices — webcam gaze tracking

The optional **Webcam eye tracker** input source bundles code and a model from
[**UnitEye**](https://github.com/wgnrto/uniteye) (Tobias Wagner et al., MuC '24), specifically
its standalone browser pipeline (`webgl/uniteye-core.js`, `webgl/uniteye-cv.js`,
`webgl/models/eyemu.onnx`), vendored under `gaze/web/`. The browser adapter
`uniteye-cv.js` contains local changes for scheduling, raw-coordinate routing,
startup progress, frame validity, camera playback failure handling, bilateral eyelid observations and separate face/gaze scheduling. The core
and model are retained from the vendored source; this is not an unmodified distribution.

| File | Source | License |
|---|---|---|
| `web/uniteye-core.js` | UnitEye `webgl/uniteye-core.js` | GPL-3.0 |
| `web/uniteye-cv.js` | UnitEye `webgl/uniteye-cv.js` | GPL-3.0 |
| `web/models/eyemu.onnx` | UnitEye `webgl/models/eyemu.onnx` — the EyeMU gaze model | GPL-2.0 as declared by [FIGLAB/EyeMU](https://github.com/FIGLAB/EyeMU); inherited as a distinct model asset through UnitEye |

The UnitEye GPL-3.0 text and the separately declared EyeMU GPL-2.0 text are included alongside these notices as `LICENSE-uniteye.txt` and `LICENSE-EyeMU.txt`. Retain both notices and source links. GAZEAIDE project code is licensed GPL-3.0-only under its project LICENSE. The third-party code/model retain their separate upstream terms; the project license does not relicense the model. See LICENSING.md and NOTICE.md in the source/standalone distribution, or META-INF/NOTICE.md in the plugin JAR, for attribution.

At runtime, the bundled `uniteye-cv.js` additionally loads, over the network, from their own
CDNs (not vendored, not modified):

- [`onnxruntime-web@1.20.1`](https://www.npmjs.com/package/onnxruntime-web) (MIT) via jsDelivr
- [`@mediapipe/tasks-vision@1.0.0`](https://www.npmjs.com/package/@mediapipe/tasks-vision) (Apache-2.0) via jsDelivr, plus Google's `face_landmarker.task` model via `storage.googleapis.com`

This means webcam mode requires **internet access** on first use (and thereafter, unless the
embedded browser's HTTP cache retains them).
