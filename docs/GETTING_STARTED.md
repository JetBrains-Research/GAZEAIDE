# Getting started with GAZEAIDE 1.0.0

Choose **Standalone** if you want to explore Codex output without installing an IDE plugin. Choose **IntelliJ plugin** if you want the effects directly in your code editor. They share the research workflow, but their calibrations and saved libraries are separate.

## Standalone

### Requirements and launch

You need [Node.js 22+](https://nodejs.org/en/download) and a modern browser. A webcam or AI account is not needed for the first test. Development checks used macOS and Node 24.13.1; other browser/OS combinations need their own device checks.

1. Download/extract the standalone release ZIP, or download/clone this source repository.
2. In the packaged standalone folder, double-click **Start GAZEAIDE.command** on macOS, or open Terminal there and run `node launch.mjs`. From the repository root, run `node standalone/launch.mjs`.
3. Keep the terminal open. Open <http://127.0.0.1:8768/> if needed. No web hosting or npm install is required.
4. If the launcher says Node is missing, install Node, reopen Terminal and check `node --version`. If the port is occupied, open the existing app or stop its terminal with Ctrl+C. Independent instances need both a different `--port` and a different `--data` folder.

Do not change browser window size or display geometry during webcam recording. Stop older GAZEAIDE versions before running an update against the same saved library.

### Two-minute camera-free test

1. Click **Try sample conversation**. The sample is synthetic text already on disk; it makes no model request.
2. Keep **Mouse proxy**, choose **Follow my reading**, then **Review setup & start → Start recording**.
3. Move the pointer between passages and pause over each. After a short dwell, the selected block stays clear and surrounding text dims. This tests the interface, not eye tracking.
4. Click **Pause** to reveal the text and stop collecting new exposure. Resume if desired, then **Stop recording**.
5. Inspect results/replay and export JSON, CSV or HTML. Your original recording stays in the local library. Restarting the server with the same data directory lets you reopen it.

### Connect Codex and review its output

Install the [official Codex CLI](https://learn.chatgpt.com/docs/codex/cli) using the instructions for your platform. In Terminal, check `codex --version`, then run `codex login` and complete sign-in. Account/provider availability and any model charges belong to that configuration. GAZEAIDE does not ask you to paste an API key into its interface.

- To read existing work: **Find sessions → choose a title → Open session**. The viewer shows local saved assistant messages, generated code and recorded diffs. **Load earlier turns**, **Latest answer** and **Next recorded diff** navigate them. Reading does not resume or send a request to that session.
- To ask a question: **Connect Codex**, type in the central composer, inspect **Preview attachment**, and click **Send question**. This creates/resumes GAZEAIDE's own companion conversation. It does not send into another session being displayed.
- **Attach recent context** adds the captured source/viewing context to that explicit send. **Save research example locally** stores the question, context and observed reply. Inspect these in **Codex → Agent research records & feedback**, label helpfulness if desired and export JSONL.
- For a question-only baseline, start **New conversation**, disable attachment and type a plain question. A prepared prompt may already contain source; disabling attachment does not remove source already present in the question.

Only local history accessible to that CLI is available. The viewer does not mirror all native Codex widgets, remote/cloud sessions, images/PDFs or hidden reasoning. During recording, the displayed snapshot stays fixed so streaming output does not move the measured text. Refresh after stopping to see newer output.

If Codex is not found, verify it in the same terminal used to start GAZEAIDE. You can set `GAZEAIDE_CODEX_BIN` to its absolute executable path before launch. A failed connection leaves the camera-free workflow usable.

### Optional native Codex MCP connection

The companion's own chat works without registering MCP. To let another local Codex chat inspect the companion's captured context, register the bridge deliberately:

```sh
codex mcp add gazeaide -- node '/absolute/path/to/GAZEAIDE/standalone/mcp.mjs'
```

With the portable folder, use its own `mcp.mjs` path. Add `--data '/absolute/path/to/library'` after the script if using a custom library. Keep the companion running, then enable/reload the server in your Codex client. `codex mcp list` lists configured servers; `codex mcp remove gazeaide` removes this entry. Registration changes your Codex MCP configuration; GAZEAIDE never does it automatically. See [official MCP instructions](https://learn.chatgpt.com/docs/extend/mcp).

MCP supplies captured context when called. It cannot paint highlights in native Codex, intercept all questions or send questions automatically.

## IntelliJ plugin

### Install

1. Use IntelliJ IDEA with its JCEF browser support. The local release build was checked against IntelliJ IDEA 2026.2.1; other supported IDE builds need a manual runtime check. A webcam and AI Assistant are optional.
2. Download `implicit-gaze-1.0.0.zip` from the published release assets, or [build it from source](BUILD_AND_TEST.md). Keep this ZIP intact; GitHub's source ZIP is not an installable plugin.
3. In IntelliJ, open **Settings → Plugins → gear → Install Plugin from Disk…**, choose the plugin ZIP, and restart if requested.
4. Open **Tools → Implicit Gaze → Open Gaze Research…**. The menu retains the plugin's original Implicit name so existing users/settings remain compatible.

### First test

1. Choose **Mouse proxy**, then **Next: scenario → Follow my reading**.
2. Use **Open example code**, create a new session, continue to **Review setup**, then **Start recording**.
3. Move the pointer over the example source and pause within blocks. The selected block stays clear; surrounding code dims. **Pause** reveals all code and stops new visits.
4. **Stop recording** opens reports and exports. Reopen stopped sessions through the saved-recording option. Nothing is sent to AI by reading or recording.

### Optional JetBrains AI handoff

Install/enable JetBrains AI Assistant or the supported native Junie chat integration and complete its own account setup. In GAZEAIDE choose **Gaze-assisted review**, select Explain/Compare/Review, then read a block until context is ready. Open **Ask JetBrains AI about viewed code…**, review/edit the text and attachment/record options, and choose **Copy prompt & open AI Chat**. Paste and send in native chat yourself. The selected text replaces your clipboard.

Replies stay in native AI Chat and are not automatically observed. **Tools → Implicit Gaze → Agent Research Records…** supports optional pasted replies, feedback and JSONL export; pasted replies are labelled as user transcriptions. Account/model selection belongs to the native AI tool. If its integration action is unavailable, the visual/recording workflow still works.

## Webcam in either edition

1. Select **Webcam eye tracking**, click **Connect webcam**, and allow camera access if prompted. Pinned dependencies load from external CDNs; check network, camera permission, face visibility and other camera-using apps if startup fails.
2. Click **Calibrate**, follow nine training targets and five independent accuracy-check targets. Insufficient captures can extend to six seconds. After three failed attempts, **Retry this target** keeps completed targets while the guide remains open. You may repeat it; targets are never skipped to force success.
3. Improve frontal lighting and face framing when samples are missing. Review the accuracy result before saving. Cancel/close/reload discards an unfinished run and retains the earlier saved calibration.
4. Try **Extra steady** for more smoothing and delay. It can reduce jitter, not correct a large position error. Use larger text or Mouse proxy while troubleshooting. Recalibrate after moving camera, seating or display/window geometry.

Calibrate each edition separately. Mouse records must not be relabelled as webcam data. Measured line visits are estimated exposure, not proof of reading, comprehension or a completed review.

## Saved data and stopping

Stop recording before closing. In standalone, stop/release the camera and use Ctrl+C in the server terminal. The default library is `.local/` inside the standalone folder; a portable copy starts its own library. In the plugin, stopped sessions are saved in the IDE's local system storage, outside project source. Exports can contain source/conversation text and participant codes: read [privacy](../PRIVACY.md) before sharing them.
