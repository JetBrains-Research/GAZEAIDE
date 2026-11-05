# GAZEAIDE · Standalone

A standalone edition of the same GAZEAIDE research tool as the IntelliJ plugin. It runs locally,
without IntelliJ or Java, in a browser with its own conversation and text viewer. Version **1.0.0**, based on the
IntelliJ plugin **1.0.0**. It uses the same UnitEye/EyeMU assets, scenario vocabulary,
interaction timing and exposure-recording concepts. This is a working research prototype;
automated and synthetic tests do not establish live gaze accuracy or research benefit.

GAZEAIDE was started by **Heidi Albarazi** during her internship at **JetBrains Research**, and was subsequently updated by **Ilia Zaharov**. The project includes the original IntelliJ plugin and a standalone edition of the same research tool.

## Start

New to the project? Read [the first-run guide](docs/GETTING_STARTED.md).

Requirements: **Node.js 22+**, a modern browser. No npm installation is required.

Double-click **Start GAZEAIDE.command** to start the server and open your browser, or run from this directory:

```sh
npm start
```

Open [GAZEAIDE at http://127.0.0.1:8768](http://127.0.0.1:8768). The default project contains the
same `GazeReviewDemo.java` source used by the plugin. To browse a project:

```sh
node server.mjs --project '/absolute/path/to/your/project'
```

Use `--port 8769` for a different port, and `--data '/absolute/path/to/a/separate/data/folder'`
for an isolated study library. Use a separate data folder for simultaneously running instances.
Version 1.0.0 enforces one running owner per data folder, including symbolic-link aliases.
A second copy exits before recovering records. A port conflict also leaves research records untouched.
Stop older versions before starting this update. After an ordinary crash, the next launch reclaims
the exited process's lock and marks unfinished answers as unknown. If startup reports an incomplete
lock or interrupted lock recovery, close all copies first and follow the reported path; never delete
a lock belonging to a running copy.
`GAZEAIDE_CODEX_BIN` can select an absolute Codex CLI executable if it is not on PATH.
Stop the server with Ctrl+C in its terminal. Close browser tabs or click Stop camera to release
camera input. No camera, model call, upload or Codex connection starts automatically.

The local app is a browser application, not a signed native macOS executable. The portable ZIP
contains the complete app source, vendored tracker/model, notices, receiver template and tests.
It needs the installed Node runtime and browser. It can be copied independently of the plugin
checkout. `npm run package` builds that portable ZIP; no installation or publication occurs.
See [release validation and limitations](docs/VALIDATION-1.0.md).

## First try

1. Click **Try sample conversation** for a local example without a model request. Keep **Mouse proxy → Follow my reading**. To use the Java example, choose **Files & text**.
2. Enter optional session codes, click **Review setup & start**, then **Start recording**.
3. Move the pointer between passages or code blocks. After 500 ms, the viewed block stays clear and surrounding content dims.
4. Stop recording. Inspect the report, replay visits and export JSON, CSV or self-contained HTML.
5. Choose **Gaze-assisted review** for a new recording. Dwell within one block for 3 seconds,
   including movement between its lines. Preview the captured question; explicitly send it to Codex.

Mouse proxy is direct pointer targeting. Mouse simulation exercises the filtering/line-hold
route. Both are explicitly labelled and are not ocular eye tracking. Green viewed-line tint is
exclusive to **Highlight viewed lines**, after 500 ms nonblank line exposure. Custom features do
not silently enable it. Names are lexical matches, not resolved program references.

## Webcam, calibration and response

Choose Webcam, explicitly connect, and allow camera access in the browser if desired. The
vendored adapter loads pinned ONNX Runtime and MediaPipe dependencies from their existing CDNs;
network access is required unless cached. Frames are processed locally and are not recorded or
uploaded. Camera hardware and browser permission behavior require manual validation.

Calibration uses nine training targets followed by five distinct check targets. An attempt allows
650 ms to settle and normally finishes after 2.7 seconds; slow/insufficient samples extend collection
up to six seconds. After three failed attempts, the guide pauses with **Retry this target**.
Completed targets stay in memory while the guide remains open, including if a fresh check fails.
Adjust front lighting/seating, keep the guide in focus and click Retry this target; retries are
unlimited. The status shows valid/stable sample counts, stable duration, spread and face/blink
feedback. Quality gates are unchanged: ten stable samples, at least 70% retained, 700 ms stable
span and bounded spread. Targets are never skipped to manufacture a successful check.
Cancel, close or reload discards this unfinished run; an earlier saved calibration stays intact.
Repeated failure after adjustments is a reason to use Mouse proxy for the software workflow
and troubleshoot tracking separately, not to label proxy data as webcam tracking.
It fits raw samples with a
centered/scaled affine transform and reports median/P90 position and vertical errors, line-height
error and within-target jitter. Review the result, then explicitly Save & use. Cancel and failed
checks retain the prior calibration. Check accuracy reuses the saved fit on five check targets.

Calibration maps screen-scaled EyeMU estimates directly to **this browser viewport**. Resizing or
moving the window, changing display geometry or browser scale requires recalibration. The app
pauses webcam attribution when its geometry fingerprint changes. It targets only its content viewer;
it does not track arbitrary host-application editors. Larger text may make coarse targeting usable,
but smoothing cannot establish reliable line selection when errors span several rows.

Fit personal response runs six target rows, freezes the best of Steady/Balanced/Responsive, and
checks that choice on four fresh rows against Steady. It reports row hit rate, P90 vertical error,
jitter and acquisition time, including human reaction. Only the preset and summary measurements
persist, not raw calibration/personalization samples. Profiles are bound to calibration geometry.
This task does not learn reading speed, comprehension, physiological fixations or assistance intent.

Pause stops attribution but retains camera ownership. Stop camera releases input, pauses and selects
Mouse proxy; it does not stop a recording. Input/configuration changes are locked during recording.
The cursor toggle is visual only. Browser blur, hidden tabs, code scrolling, invalid frames and
stale samples clear active attribution. The transient viewing history is source-specific and bounded
to twelve region entries and 45 seconds; revisits are retained.

## Codex: two supported connections

**Review local Codex sessions:** click **Find sessions**, select a title and **Open session**.
Search and archived-history controls are available on the left. The central viewer shows requests,
assistant explanations, generated code blocks and recorded file diffs. **Include progress messages &
command output** expands the additional activity. Opening a session jumps to its latest answer.
Use **Latest answer**, **Next recorded diff**, and **Load earlier turns** to navigate. It loads the
newest 20 turns first; load earlier pages as needed. Long individual diffs/outputs are visibly shortened.
**Expand viewer** hides the setup/research sidebars while you read; **Show setup & research** restores them.
Loading earlier turns switches off Follow new output; refreshing starts again at the newest page.
Only local sessions available to the installed Codex CLI appear; a cloud or another computer's
session may not be accessible. Reading history does not resume or send a request to that session.
The app does not reproduce the native Codex UI, image/PDF viewers, tool interfaces or hidden reasoning.

**Follow new output** checks the selected session every five seconds and refreshes changed/active
sessions outside recording. During recording, the displayed snapshot stays fixed and a banner
offers **Refresh session**. This prevents a streaming answer from moving the text being measured.
For native sessions this reads saved history; unpersisted UI events and live interactive widgets
are not mirrored.
Gaze effects, viewing visits, context capture and walkthroughs work on conversation passages and
generated code as well as on source files. Captures retain session/turn/item IDs and content kind;
a recorded diff is historical content, not a check of the current file or Git working tree.
Recordings therefore can contain reviewed conversation passages and command output. Treat those
local exports as potentially sensitive study data. Reduced uploads omit their text and IDs.

**Files & text** is optional: it can display code, Markdown, plain text, JSON and other text files.
It is a text viewer, not a document renderer for PDFs/images. Open files from disk or launch with
`--project` to browse a project. No file-by-file selection is needed for Codex conversation review.

**In-app conversation:** install and sign in to the official Codex CLI as usual (`codex login`).
Choose Connect Codex or explicitly send a captured question. The backend starts `codex app-server`
over stdio, initializes it, creates/resumes its own review thread and receives streamed events.
Replies appear in the central **Codex conversation** viewer. Follow-ups preserve the companion thread. Interrupt cancels the turn; New
conversation starts a fresh thread on the next send. Questions always go to the companion's separate
review chat, even when another session is being displayed. To ask about the viewed passage, capture
it and review the prompt before **Send to Codex**. The CLI owns authentication; the app never
reads, exports or copies auth tokens. Actual plan/model access depends on that CLI configuration.

This edition requests a read-only sandbox and declines command/file/permission approval requests;
it is for explaining and reviewing captured code. Codex's own sandbox and configured environment
remain the execution boundary. Project instructions and configured tools may still influence the
harness; review mode is not a general-purpose isolation system for malicious repositories.
No code editing agent UI is included. Source comments are explicitly treated as untrusted data.

**Native Codex context through MCP:** the Codex tab displays the exact registration command. From
the checkout, for example:

```sh
codex mcp add gazeaide -- node '/absolute/path/to/standalone/mcp.mjs'
```

Add `--data '/your/data/folder'` after the script when using a nondefault library. The standalone
app must be running. Enable/reload that MCP server in your Codex client. No global Codex settings
are modified by building or opening this app. The bridge provides four read-only tools:

- `get_agent_context`: bounded, versioned recent-context packet with input and uncertainty labels.
- `get_gaze_context`: captured prompt, provenance, recent sequence and snapshot age.
- `get_gaze_status`: recording/input/pause state.
- `get_review_walkthrough`: captured steps and explicit user notes/review marks.

MCP exposes captured state. It does not submit questions, select text or paint highlights in
Codex's own UI. The app publishes state every five seconds and at context/workflow changes; tool
responses show their age. This is deliberately separate from a raw live gaze stream.

Official references: [Codex App Server](https://learn.chatgpt.com/docs/app-server),
[Codex MCP configuration](https://learn.chatgpt.com/docs/extend/mcp?surface=cli).

## Automatic context and linked research examples (0.3)

**Attach recent context** adds a bounded snapshot when you explicitly Send a question. It
includes recent recording visits, the selected target and visible conversation/source content,
with versions, input labels and uncertainty. Preview it first or switch it off for a question-only
baseline. **Save research example locally** links the question, context and observed answer;
use **Codex → Agent research records & feedback** to inspect, label helpfulness and export JSONL.
No automatic extra questions or model training occur. See [the workflow and schema](docs/AGENT-RESEARCH.md).
Both editions also offer **Extra steady** smoothing, with additional interaction delay.

## Research data and exports

Sessions and preferences live in `.local/` beside this app by default (or `--data`). It contains
captured source and free-text notes and should be handled as study data. Capability/connection
files have mode 0600; generated state is excluded from the release. Source files are read-only;
edit in your editor and use Reload source. Binary/secret files, symlink escapes and files over
500 KB are rejected by the project reader. File listing is bounded to 2,500 entries.

Stopped sessions save atomically. During recording a five-second checkpoint includes the current
visit as of that checkpoint without splitting it in the live recorder. A recovered checkpoint is
labelled in-progress, not a complete recording. Browser close/crash may lose time after the last
successful checkpoint. Exports/imports are capped at 32 MiB for this prototype. The session library
is the selected data folder; use distinct folders to separate projects or studies.

Local **JSON schema 3** preserves plugin-compatible event field names and adds edition/version,
walkthrough and explicit AI-request metadata. `documentStamp` is a deterministic safe-integer
content fingerprint in this edition, not the IDE's modification counter or a Git revision. The
plugin itself does not import external exports. Standalone can import plugin JSON and its own
JSON for inspection; it does not append to an old recording. Legacy `explained` means local dwell
preparation in current versions, not sending, receiving or reading an AI answer.

For conversation content, `line` refers to a row in the displayed transcript. JSON regions and
the additional CSV columns retain content kind, block kind, Codex session/turn/item IDs and a
recorded-change path where present. Keep these separate from current source-file lines in analysis.

CSV has formula protection. HTML is dependency-free, escapes source/notes, includes a viewing
trace/replay and walkthrough notes/marks. Replay can filter input sources. Trace annotations and
pruning affect HTML export only; original JSON visits remain unchanged. Exposure totals and groups
keep file/version/input distinctions. Session duration includes pauses; exposure excludes them.

Walkthrough uses overview → files → captured lexical code steps. Gaze can follow a matching step;
notes and review marks require explicit user input and save with a selected recording. Captured
code is inspectable even when the current source differs. No source-navigation action silently
opens a mismatched version. A tour created without a recording is retained through HTML export.

The recording includes passages actually viewed in the central viewer, with their provenance;
it is not a full chat archive. The Codex CLI maintains the original history. The app stores its owned
thread ID and reloads up to 50 recent turns on connection after a restart.
No model identity is invented when the runtime has not reported one.

## Optional remote upload

No receiver is preconfigured. Select a stopped recording, enter an approved HTTPS receiver and
study/participant/task codes, review data handling, run a synthetic test, then preview and explicitly
upload the reduced payload. The participant token stays in memory and is never saved in preferences,
queue files or exports. Reduced data omits paths, source, free-text local labels/notes, AI content,
raw gaze/video and absolute event times. Code/column/region indices and relative timing remain.

`docs/google-drive-receiver.js` is the plugin's receiver with a backward-compatible optional
`edition` / `editionVersion` extension. Deploy/update that template to accept standalone labels.
Legacy plugin payloads still validate. Standalone payloads carry `edition=standalone`,
`editionVersion=1.0.0`; `pluginVersion=1.0.0` denotes the compatible plugin workflow. Existing
receiver deployments must be updated before they can accept the new optional fields.

Generic HTTPS receivers use a bearer token. Google Apps Script uses `{token,payload}` and follows
only its response-only `script.googleusercontent.com/macros/echo` redirect with GET and no token.
Responses are bounded and the exact upload ID must be acknowledged. Failed uploads retain one
reduced-data payload and its original endpoint/codes/ID for explicit retry; there are no background
retries or automatic uploads. Reenter a token to retry after restart. A successful synthetic test
checks connection/authentication, not consent, retention/withdrawal or study readiness. This local
build did not deploy or validate an actual research receiver.

## Validation and review

```sh
npm test
npm run check
```

See [review, reuse and parity](docs/REVIEW-AND-PARITY.md) and
[release validation](docs/VALIDATION-1.0.md). Third-party notices and the included GPL text travel with
the tracker/model. Confirm the original source/model redistribution requirements before distributing
a research release; this build remains local.

## Project license

GAZEAIDE project code is [GPL-3.0-only](LICENSE). See [attribution and dependency terms](LICENSING.md).
