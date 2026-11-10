# Third-party notices — webcam gaze tracking

The optional **Webcam eye tracker** input source bundles code and a model from
[**UnitEye**](https://github.com/wgnrto/uniteye) (Tobias Wagner et al., MuC '24), specifically
its standalone browser pipeline (`webgl/uniteye-core.js`, `webgl/uniteye-cv.js`,
`webgl/models/eyemu.onnx`), vendored under `public/vendor/`. The browser adapter
`uniteye-cv.js` contains local changes for scheduling, raw-coordinate routing,
startup progress, frame validity, camera playback failure handling, bilateral eyelid observations and separate face/gaze scheduling. The core
and model are retained from the vendored source; this is not an unmodified distribution.

| File | Source | License |
|---|---|---|
| `public/vendor/uniteye-core.js` | UnitEye `webgl/uniteye-core.js` | GPL-3.0 |
| `public/vendor/uniteye-cv.js` | UnitEye `webgl/uniteye-cv.js` | GPL-3.0 |
| `public/vendor/models/eyemu.onnx` | UnitEye `webgl/models/eyemu.onnx` — the EyeMU gaze model | GPL-2.0 as declared by [FIGLAB/EyeMU](https://github.com/FIGLAB/EyeMU); inherited as a distinct model asset through UnitEye |

The UnitEye GPL-3.0 text and the separately declared EyeMU GPL-2.0 text are included alongside these notices as `LICENSE-uniteye.txt` and `LICENSE-EyeMU.txt`. Retain both notices and source links. GAZEAIDE project code is licensed GPL-3.0-only under its project LICENSE. The third-party code/model retain their separate upstream terms; the project license does not relicense the model. See LICENSING.md and NOTICE.md in the source/standalone distribution, or META-INF/NOTICE.md in the plugin JAR, for attribution.

The ONNX Runtime 1.20.1 runtime (MIT), MediaPipe Tasks Vision 1.0.0 runtime
(Apache-2.0), required WASM variants and Google's version-1 float16 face-landmarker
model are bundled for offline use. The runtime manifest records original download
URLs, sizes and SHA-256 checksums. Upstream license texts accompany the assets;
model assets retain their upstream terms. No CDN is contacted at camera startup.

The tracked source is `standalone/public/vendor/runtime/`. Gradle copies that same
asset set into `gaze/web/runtime/` in the plugin. Both editions verify the manifest
before serving/extracting the runtime; the standalone release manifest also covers
these files. See the runtime's `MANIFEST.json` for exact provenance.
