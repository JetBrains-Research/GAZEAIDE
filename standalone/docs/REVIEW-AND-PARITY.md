# IntelliJ and standalone editions

GAZEAIDE 1.1.0 provides the same research workflow in two hosts: estimated viewing input → stable region selection → visual adaptation or captured context → an explicit question → optional research records and exports. Shared functionality does not establish empirical equivalence between hosts.

| Capability | IntelliJ edition | Standalone edition |
| --- | --- | --- |
| Host | IntelliJ editor, JCEF, Java services | Local Node.js server and browser viewer |
| Content | Open project source in the IDE | Files/text, local Codex messages, generated code and recorded diffs |
| Webcam inference | UnitEye/EyeMU in JCEF | Byte-identical vendored tracker/model assets in the browser |
| Calibration | OS/editor coordinates | Browser viewport coordinates |
| Filtering, dwell and context | Java domain implementation | Ported JavaScript algorithms and shared context fixtures |
| Visual scenarios | IDE highlights, gutter and presentation services | DOM/CSS adaptations within the companion viewer |
| Assistant interaction | Review/copy prompt, open JetBrains AI Chat, paste and send | Explicit question sent through Codex App Server, response shown in the companion |
| Response records | Optional pasted answer, labelled user transcription | Observed reply with turn linkage |
| Native Codex connection | No native Codex integration | Four read-only MCP tools expose captured context on request |
| Storage and export | IDE-local settings/session library, JSON/CSV/HTML/JSONL | Local data directory, corresponding exports and checkpoint recovery |
| Optional study upload | Explicit configuration, manual/stop-time upload and multi-item outbox | Explicit preview/test/upload, one durable retry payload |
| Code editing | Full IDE editing and project tools | Read-only companion viewer; edit in your usual editor and reload |

## Reused assets and ported behavior

`public/vendor/uniteye-core.js`, `uniteye-cv.js`, `models/eyemu.onnx`, the Java demo and receiver template are copied from the plugin. [ASSET-PROVENANCE.json](ASSET-PROVENANCE.json) records their hashes and source mapping.

Filtering/line hold, affine calibration, quality summaries, personal response selection, lexical regions, dwell/history, scenarios, bounded context, walkthroughs and recording/export concepts are ported to JavaScript. IntelliJ services, document coordinates, inlays, settings and packaging are replaced by browser rendering and Node services. Codex App Server supplies the standalone conversation; MCP supplies bounded context to other configured local assistants.

## Research interpretation

Calibration and response profiles must be created separately in each host. Changing display/window geometry requires recalibration. The standalone targets its own viewer; MCP cannot draw highlights in native Codex or intercept every question. Transcript rows, recorded diffs and source-file lines are different content units; preserve content kind, snapshot/version and input provenance when analyzing them.

The companion's displayed history is separate from its own question conversation. Reading a saved session does not resume or send a request into that session. Recorded diffs are historical snapshots, not a check of the current working tree.

Both editions preserve `WEBCAM`, `MOUSE_PROXY` and `MOUSE_SIMULATION`. Viewing exposure is not evidence of understanding, confusion, review completion or improved answer quality. Webcam accuracy and host usability need physical validation; see [release validation](VALIDATION-1.0.md), [agent research](AGENT-RESEARCH.md) and [third-party terms](../LICENSING.md).
