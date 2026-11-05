# Manual IntelliJ checks

Use the installable `implicit-gaze-1.0.0.zip` with IntelliJ IDEA and JCEF support. Automated release checks used the 2026.2.1 SDK; run these checks on each intended research setup. See [installation and first run](GETTING_STARTED.md#intellij-plugin).

## Camera-free workflow

1. Open **Tools → Implicit Gaze → Open Gaze Research…**. Choose **Mouse proxy**, **Follow my reading**, and **Open example code**.
2. Create a session, review setup and start recording. Pause over code blocks; the selected block stays clear while surrounding text dims.
3. Pause and resume. Pausing reveals the code and stops new exposure. Stop recording, inspect reports and export JSON/CSV/HTML.
4. Restart the IDE and reopen the saved recording. Check that the selected session's captured context and walkthrough remain available.
5. Try each scenario with a fresh recording. Viewed-line green tint belongs only to **Highlight viewed lines**; matching-name highlights are lexical matches, not resolved references.

Mouse proxy and mouse simulation must retain their distinct provenance labels. They do not validate webcam behavior.

## Webcam, calibration and response

1. Select **Webcam eye tracking**, connect explicitly, allow camera access and confirm valid samples. No camera should connect on startup.
2. Calibrate using nine training and five independent check targets. Review position, vertical error and jitter before saving.
3. Interrupt sampling by looking away. Collection may extend to six seconds; after three failures, **Retry this target** must retain completed targets. Repeat a failed batch, then retry successfully. An incomplete run must never offer Save.
4. Cancel a run and confirm the previous calibration remains. Save a completed run, then use **Check accuracy…** without changing the saved transform.
5. Compare response presets and **Extra steady** with fresh recordings. Smoothing adds delay and cannot repair a large position bias. Check face loss, blinks and camera disconnection; invalid/stale samples must stop attribution.
6. Change seating/display geometry and recalibrate. Check scrolling, zooming, folding and editor switching. Calibration and response controls must be locked during recording.
7. Pause recording and stop camera separately. Confirm camera tracks are released and recording/export states remain consistent.

## Assistant and research records

1. Set up native JetBrains AI Assistant or supported Junie chat separately. Choose **Gaze-assisted review**, view a block for the configured dwell and inspect captured context.
2. Open **Ask JetBrains AI about Viewed Code…**. Cancel must leave the clipboard/chat unchanged. **Copy prompt & open AI Chat** copies the reviewed text; paste and send in native chat.
3. Test missing or signed-out AI integration. Expect setup guidance; visual recording must remain usable. Edit a captured source before handoff and check that stale snapshots do not select unrelated current text.
4. Open **Agent Research Records…**, inspect the local question/context record, optionally paste a reply and label feedback. Pasted replies must remain identified as user transcriptions. Export JSONL and review its contents before sharing.
5. Compare an attached-context question with an explicit question-only baseline. Viewing or saving an example must not itself trigger a model request.

## Optional study upload

Use an approved test receiver and synthetic data. Preview the reduced payload, test authentication/failure/retry, and check exported fields against [the upload guide](remote-upload.md). No receiver is preconfigured. Confirm that source, file paths, prompts, answers, conversation IDs, free-text notes and raw gaze/video are absent.

Record the IDE, OS, camera, display, response preset and result of each physical check. The [automated validation record](VALIDATION-1.0.md) does not establish hardware accuracy or research benefit.
