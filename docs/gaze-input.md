# Gaze input and adaptation pipeline

## Sources

**Settings → Tools → Implicit Gaze** selects `MOUSE` (mouse-dwell proxy) or `WEBCAM`
(UnitEye's webcam → MediaPipe FaceLandmarker → EyeMU model). Both feed `GazeEngine.onGaze`.
The webcam settings' **Verify without a camera** mode polls the desktop pointer every 50 ms
and routes screen points without opening JCEF/camera. It is recorded separately as simulation
and bypasses webcam calibration; it does not validate the browser/model pipeline.

`UniteyeGazeService` extracts bundled web assets and starts a loopback-only HTTP server on
`127.0.0.1`. A windowless JCEF off-screen surface runs the page; no native host window is created.
Only that loopback origin receives media permission. Its dimensions are 640 × 480; primary
display bounds are passed explicitly for gaze coordinates, independent of that surface.
A sequential timer runs sampling without relying on animation frames; inference does not
overlap, and stop/restart invalidates old callbacks. Disabled JCEF OSR produces a clear
error and mouse fallback rather than creating a visible browser window. Bundled model/UnitEye assets are accompanied by
[third-party notices](../src/main/resources/gaze/THIRD-PARTY-NOTICES.md); pinned ONNX/MediaPipe
runtime URLs are fetched at runtime. Browser caching is not an offline guarantee.

Browser lifecycle and incoming protocol handling run on the EDT. Asset/server preparation
runs in the pool; generation checks discard interrupted starts and obsolete callbacks.
There is no lifecycle monitor held while waiting for the UI thread.

## Samples and loss of tracking

```text
READY       browser pipeline started
OK x y      raw screen-coordinate sample
BAD         blink, missing face or nonfinite estimate: pause targeting, reset filtering
ERR message notify and return to mouse proxy
```

The browser bridge sends the second (`raw`) EyeMU callback argument, not its browser-filtered
`finalGaze`. Blink, missing-face and nonfinite samples are rejected before calibration or routing.
Java alone applies the saved affine correction and interaction filtering:

1. Rolling coordinate median over up to three samples, at most 180 ms old.
2. Time-based low-pass, `alpha = 1 - exp(-dt / smoothingMs)`.
3. Code-line candidate hold, with a ±0.68-line-height boundary hysteresis on the accepted row.

| Fixed response | Smoothing time constant | New-line hold |
| --- | ---: | ---: |
| Steady (default) | 130 ms | 160 ms |
| Balanced | 85 ms | 110 ms |
| Responsive | 45 ms | 70 ms |

These are engineering interaction defaults, not empirically validated fixation criteria. They
add delay and omit brief/unsettled visits; they cannot fix spatial bias. Filters reset on
start/stop, invalid samples, timestamp reversal, >500 ms sample gap, calibration/response change
and tracking loss. A 250 ms watchdog invalidates tracking after one second without valid input.
Mouse proxy and mouse simulation bypass all webcam correction/filtering/line hold.

`GazeDispatcher` maps points only inside visible editor content in the active IDE window. It
resets line selection after editor, document, viewport, font-height, wrapping or folding changes.
Pending targets do not credit the previous line or run its dwell timer. Brief (<500 ms) pending
moves within the same lexical block pause block-context progress; unsettled time is excluded.
Different blocks or longer uncertainty reset that progress. Ordinary line changes within a block
therefore still permit context preparation. Leaving an editor or mapping below the document ends
the visit. These checks do not establish which exact character a person read.

The **Show gaze cursor** switch controls only the blue gutter marker (including its optional
click-to-ask action); default is off. Research-panel Ask remains available. Live visibility changes
are logged without resetting context dwell or splitting visits. Other scenario effects are
independent, including green line backgrounds only in **Highlight viewed lines**.

## Gaze-driven behavior

`GazeEngine` nominates a lexical block and identifier, waiting for a configurable stable
target (default 500 ms). `GazePresentation` applies reversible markup:

- Surrounding code dims while the stable block stays prominent.
- Previously viewed blocks fade after accumulated stable exposure (default 1,500 ms).
- Returning to a block restores it, including overlaps/nested regions.
- Matching identifiers in the current block receive boxes, excluding comments/strings.

No source is changed, folded, or scrolled by these effects. Regions use lexical braces,
Python indentation, or a stable eight-line fallback. Large files use bounded local region
extraction; name matching is limited to the first 20,000 characters of the active block and
100 occurrences. This is not a language-aware reference or data-flow analysis.

A separate block-residence timer (default 3 s) prepares local AI context and an ask affordance.
Only an explicit native-chat handoff followed by Paste/Send in chat uses AI. See
[AI context handling](ai-layer.md). Editing source or pausing invalidates sendable context. Features and delays are independent in **Implicit Gaze Features**.

## Calibration and limits

Choose Webcam → Connect webcam, wait for valid samples, then **Calibrate…**. Keep simulation off.
The protocol runs on the primary display using desktop logical pixels:

- Nine training targets, each with a 900 ms settling period and 1,800 ms nominal capture.
  Capture extends to 6,000 ms if needed, then retries twice automatically. After three failed
  attempts the guide pauses: **Retry this target** starts another batch without discarding completed
  targets. Explicit retries are unlimited; all training and independent check targets are still required.
- Training uses coordinate medians after radial outlier rejection (radius limit is the larger
  of 12 px and three times the median radius). Each target needs ≥10 retained samples,
  ≥70% retained, ≥700 ms retained span and 90th-percentile spread ≤max(25 px, 3.5% screen diagonal).
- All nine targets are required; equal-weight point medians fit a normalized affine transform.
  Degenerate or nonfinite fits fail. These quality thresholds are engineering defaults.
- Five held-out targets at (25%,25%), (75%,25%), (25%,75%), (75%,75%), (50%,35%) use fresh raw
  samples and the candidate transform. They need ≥10 finite samples spanning ≥700 ms each.
  Validation errors include all finite samples, including outliers; stable bias is not hidden.
- Results report pooled median/p90 radial and absolute vertical error, vertical p90 in current
  code-line heights, and p90 radial jitter around each target's corrected median. These are
  setup checks, not confidence intervals or guarantees. Over half a line of vertical p90 error
  prompts a warning about individual-line reliability. Calibration is saved only with **Use calibration**.
- **Check accuracy…** runs only the five validation targets on the current correction. Keeping
  the result updates quality metadata, never refits/replaces that correction. Cancel discards it.

The ~40-second protocol (without retries) is longer because it checks data quality and independent
accuracy, not because looking at the targets should be difficult. Samples bypass interaction
filtering and are never routed to the editor during calibration. Closing/cancelling removes the
listener, timer and window. Failures/cancel leave the previous transform and quality intact.

Version 2 calibration stores scalar coefficients, primary-display bounds, check timestamp,
validation errors/jitter and counts. Older browser-filtered calibrations are explicitly marked
for recalibration and no longer applied; changed display bounds also disable the correction.
Response/calibration changes are locked during recording. Calibration corrects a person's
spatial bias, but no online learning of individual reading patterns or adaptive response is added.

Use even frontal lighting, a stable camera, comfortable steady posture, and larger editor text
if vertical error spans lines. Recheck after moving/changing lighting or seating. The model assumes
the primary display; multi-monitor and HiDPI behavior require physical validation. Automated tests
check math and control logic, not live eye-tracker accuracy. The camera stays active while processing
is paused; Stop camera releases it. No raw calibration samples or video are retained in sessions.

## Recording and exports

The opt-in recording buffers line visits, not raw fixation samples. A visit is flushed on
line exit, tracking loss, editor exit, recording boundaries, or a source edit. It records:

- End timestamp, elapsed time, duration, file path, 1-based line and 0-based column.
- The line snippet and last stable identifier estimate (not a most-viewed-word statistic).
- `explained`: legacy name for a dwell trigger. In 0.7 it means local AI context preparation;
  earlier versions used it for direct-request triggers. It never proves an answer arrived.
- `source`, `simulated`, and explicit `inputKind`: `WEBCAM`, `MOUSE_PROXY`, or `MOUSE_SIMULATION`.
- A captured region with bounds, label, source text, input source, and document version.

Versions are IDE document modification stamps, not persistent Git identifiers. JSON schema 3
preserves snapshots and additive `tracking` configuration (cursor visibility, fixed response,
timing, pipeline version, affine coefficients/display bounds and calibration-check metrics);
older recordings leave the new field unknown. The configuration timeline logs live visibility changes.
The recorded visits are filtered interaction targets, not a raw gaze/fixation time series. CSV adds region/version fields; HTML replay separates source/version
groups. Viewing durations do not establish comprehension or completed review.

**Replay Viewing Trace…** stops an active recording if needed, groups adjacent visits to the same
versioned region, filters brief stops, and retains revisits. Gaps over one second remain
separate. The route can be annotated/pruned and exported as standalone HTML; current-source
navigation requires matching source text and the recorded document stamp. Trace edits are
kept only by HTML export.

The separate **Review walkthrough** captures open editor code or recorded regions as an
overview → files → code tour. Stable viewed regions select matching steps; native AI handoff and
review marks are explicit actions. See [the workflow guide](INTELLIJ.md#review-walkthrough-and-viewing-trace).
Stopped recordings save to the local IDE system folder; exports remain portable study copies.

### Extra steady

Extra steady uses a 260 ms time constant and 260 ms line hold in both editions. Steady
(130/160 ms) remains the default. Choose Extra steady before recording if jitter makes
interaction difficult, then compare the added delay. Personalization now evaluates four
presets. Smoothing affects interaction only; calibration still fits raw samples. It cannot
repair position bias, missing face samples or insufficient camera information.
