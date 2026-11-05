# Implicit / GAZEAIDE

A local research prototype for gaze-guided code review in JetBrains IDEs. IntelliJ edition
**1.0.0** fixes historical context windows and adds resumable calibration retries with longer
collection. The shared workflow includes recent context at the explicit native-chat handoff, local question/context records,
optional manually transcribed answers and feedback, JSONL export, and an Extra steady response.
See [Agent research workflow](../docs/agent-research.md). The matching standalone edition is **1.0.0**.

Optional remote-study upload provides connectors, a reduced-data preview, synthetic testing
and a local retry queue. Nothing uploads unless configured and enabled; the feature requires
separate study-specific setup and testing. It retains webcam startup recovery, a reusable personal response profile, question-specific
gaze-assisted review, and quiet assistance offers. A built-in Help guide explains the workflow without prior context. Green line shading remains exclusive to **Highlight viewed lines**.
Explanations use installed JetBrains AI Assistant / Junie; no model is chosen by GAZEAIDE.

## First try: no camera or AI account needed

1. Open **Tools → Implicit Gaze → Open Gaze Research…**.
2. Choose **Mouse proxy → Next: scenario → Follow my reading → Open example code**.
3. Continue to **Session**, keep **New session**, then **Review setup → Start recording**.
4. Return to the example. Move the pointer between methods: the viewed block stays clear and
   surrounding code dims. The pointer is a gaze proxy, not eye tracking.
5. **Stop recording → Viewing report** or **Replay viewing trace** shows what was recorded.

**Help** at the top of every step explains scenarios, sessions, calibration, personalization,
AI context, exports and research limits. A *scenario* is what the plugin does with viewing
input. A *session* is one recording for a task. Setup stays paused until you start.

## Start an experiment

Open **Tools → Implicit Gaze → Open Gaze Research…**. The Experiment tab follows:

**Input → Scenario → Session → Review setup → Start recording**

Back/Next stay directly below the instructions at the top of the panel. Setup stays paused.

1. **Input:** Mouse proxy needs no camera. For Webcam eye tracking, click the separate
   **Connect webcam** button, wait for valid samples, then **Calibrate…**. Click
   **Next: scenario** above. Mouse simulation tests screen routing without a camera.
   The blue gutter dot is hidden by default; enable **Show gaze cursor** if useful.
   For webcam input, **Steady — less jitter** is the standard response. Expand
   **Response settings & personal profile (optional)** to change it or select a saved profile.
2. **Scenario:** choose a preset and read its instructions. **Open example code** provides
   a caller/validator task about whitespace-only names and a Guest badge.
3. **Session:** create a new labelled session, or reopen saved results. Participant ID,
   review task and condition are optional. Use participant codes for studies.
4. **Review setup:** check the summary, then **Start recording** once. It resumes processing
   and recording together. Return to the editor to perform the task.
5. **Stop recording** shows reports, exports, walkthrough and viewing trace. Stopped sessions
   save locally. Export a copy for your study files.

**Open saved recording…** on the first step bypasses camera setup. Reopening shows results;
it does not append to an old recording. **New experiment** starts a fresh setup.

## Check webcam accuracy

**Calibrate…** collects all nine training targets (about 2.7 seconds each when stable), retries
insufficient/unstable captures up to twice automatically, then pauses with **Retry this target**.
Explicit retries are unlimited and retain completed targets while the dialog remains open. All
nine training targets and five different check targets are still required.
About 40 seconds without retries. The result reports median/90th-percentile position and
vertical error, vertical error in code-line heights, and within-target jitter. Review it and
click **Use calibration** to save. Cancel or failure retains your earlier calibration.
**Check accuracy…** repeats only the five checks without refitting. It can reveal drift.

Use the primary display, comfortable stable seating and camera placement, even frontal lighting,
and larger editor text when needed. A harder task alone does not make calibration more accurate.
Recalibrate after changing the setup. Previous versions calibrated browser-filtered coordinates;
0.8 uses genuine raw coordinates and requires recalibration. A changed primary-display layout
also requires recalibration. Smoothing cannot turn several lines of position error into reliable
individual-line selection, and accepted line visits are not measured physiological fixations.

**Show gaze cursor** is also available on the Recording page. It changes only the blue marker;
tracking, scenario effects and recording continue. Visibility changes are logged. Response and
calibration and profile selection are locked during recording. Personal profiles are fitted
only in the explicit Personalization task; they do not update silently while you work.

## Personal response profile

Calibration aligns estimated gaze with screen positions. **Personalization** chooses how steady
or responsive tracking feels. It does not learn reading speed, comprehension, physiological
fixations, assistance timing or question intent.

1. Connect and calibrate a real webcam in Input. Mouse proxy/simulation cannot fit a profile.
2. Choose **Personalization → Session → Review setup → Start personalization**.
3. Follow the moving ring between code rows: six targets select among Steady, Balanced and
   Responsive; four different targets check the frozen choice. About 24 seconds without retries.
4. Review the measured target-row sample accuracy, vertical error, jitter and acquisition time
   versus standard Steady on the same check targets. Acquisition includes human reaction and
   tracker delay. These task measurements do not establish reading or review accuracy.
5. **Save & use profile** replaces the single profile for this IDE user. Cancel/failure retains
   the old one. **Choose another scenario** returns directly to scenario selection.

The profile is available in every scenario. In Input's optional response section or in Session,
**Use my personal response profile** switches between it and standard settings. It stays fixed
during recording. Rerun Personalization to update it; **Reset personal profile…** deletes it
with confirmation. Changing calibration/display disables reuse until a new profile is fitted.
Recheck after changes to camera, seating or lighting. Filtering cannot correct large position bias.

Only the preset and summary check measurements are persisted; raw task samples are discarded.
The recording's JSON metadata includes the selected profile, effective response, question
purpose and offer policy. Older recordings without these fields still open.

## Scenarios

| Scenario | How gaze is used |
| --- | --- |
| Personalization | Guided target following selects one response preset and checks it on fresh targets; save one IDE-user profile for every scenario |
| Follow my reading | Stable viewed block stays prominent while surrounding code dims |
| Highlight viewed lines | Only this scenario tints nonblank lines green after 500 ms of accumulated exposure |
| Fade previously viewed code | Earlier viewed blocks fade; looking back restores them |
| Highlight matching names | The stable viewed identifier gets lexical matches within its block |
| Gaze-assisted review | Explain a block, compare viewed regions or review for possible problems; automatically prepare context and optionally offer a question quietly |
| Gaze-guided review walkthrough | Stable viewed code selects a captured code step; ask AI and review marks are explicit |
| Record viewing only | Baseline line-visit recording without adaptations or automatic context preparation |
| Custom features | Independent switches and timing; Next applies the combination |

Default stability is 500 ms, viewed exposure before fading 1,500 ms, context-preparation dwell
3,000 ms within a code region; moving between its lines does not reset preparation. Green
line backgrounds are off in every other scenario, including Custom. Identifier matches are lexical, not resolved references or data flow. Viewing does
not establish comprehension, correctness or completed review. Inputs are labelled **WEBCAM**,
**MOUSE_PROXY** and **MOUSE_SIMULATION**; the latter two are not ocular eye tracking.

Pause/Resume pauses processing and new visits while keeping the camera running. **Stop camera**
releases it, pauses processing and switches to Mouse proxy; stop the recording separately.
No camera connects automatically on IDE startup. Setup/input changes are locked while any
project records because input and feature settings are application-wide.

## Ask JetBrains AI

Enable/sign in to AI Assistant or unified Junie in IntelliJ. Models and agent mode are chosen
in native AI Chat. Use Junie **Ask** mode for explanations.

With **Gaze-assisted review**, choose **Explain this block**, **Compare viewed regions**, or
**Review for possible problems**, then read within one block for 3 seconds. Compare needs two
distinct viewed blocks within 45 seconds. The recording panel shows preparation progress,
**Preview captured AI context…**, and optional quiet offers. Offers appear only in the panel,
at most once every 30 seconds; open or dismiss them. Preparation never inserts a block into
the editor or opens chat. An offered question retains its own captured snapshot while newer
context is prepared. Source edits clear pending offers. Then use the
optional blue gutter dot or
**Ask JetBrains AI about viewed code…** in Gaze Research. Review/edit the prompt and click
**Copy prompt & open AI Chat**. **Paste and send it in native chat.** This copies to the
clipboard; it does not automatically insert or submit the prompt. Answers remain in native
chat. No generic provider settings, Llama default, API key or direct AI HTTP client remains.

The prompt carries bounded code and optional recent viewing history. Opening the research
panel, recording, dwelling and exporting do not make AI requests. Availability means a native
action is registered, not that an account/model has been tested. The context inspector follows
the selected session: it shows the original locally prepared prompt when retained, or explicitly
reconstructs a new draft from recorded source snapshots after reopening. Reconstruction is not
proof of preparation or sending during that session. See [AI details](../docs/ai-layer.md).

## Review walkthrough and viewing trace

**Review walkthrough** is overview → files → code. It captures open source files or recorded
regions. Gaze can select the discussed step; Previous/Next and Follow let you control selection.
It never marks review complete or scrolls source merely because you look at code.

**Ask JetBrains AI about this step…** previews a prompt about behavior, inferred rationale,
checks and missing context. The response stays in native chat; add findings to **Your notes**
and tick **I have reviewed this step** yourself. Notes/review marks save locally with the
session. **Back to experiment** at the top returns to the current session/recording controls
without resetting them. **Export HTML…** stays visible in both walkthrough views and opens
IntelliJ’s save dialog for a portable copy. Older saved AI drafts remain visible.

The design adapts [Trust-Calibrated Code Review](https://doi.org/10.4230/LIPIcs.ESEM.2026.89),
§4.4–4.5: overview → file analysis → code snippets. That study used static narration and does
not validate this live gaze implementation. Tours cover snapshots, not complete Git diffs,
generation history or actual author reasoning. Source/version mismatches block current-source
navigation; captured code remains inspectable.

**Replay viewing trace** is a separate chronological exposure route with revisits. You can
filter, annotate/prune and export HTML. Trace edits persist only through that export.

## Data and storage

Recordings contain line visits, labels, source snapshots, input provenance and configuration
changes, including pauses/fallback. CSV, JSON schema 3 and self-contained HTML exports are
available. Visits are not raw fixation samples. Session duration includes pauses; visit exposure
does not. Legacy `explained` counts dwell triggers; in 0.7 it means local context preparation,
not an AI request. Earlier versions counted direct-request triggers. None means an answer was read.

Stopped sessions and tour notes save under the IDE system folder at
`implicit-gaze/sessions/<project-hash>/`, outside the repository. Moving the project or clearing
IDE caches can affect access; keep exported study copies. External exports cannot be imported.
Recording exports omit native chat answers; walkthrough HTML includes notes and saved older
drafts. The native model is not observed by GAZEAIDE; record it in study notes if needed.

## Optional remote study upload (experimental)

Use **Configure remote upload (optional)…** in Session, **Remote upload…** in Results, or
**Tools → Implicit Gaze → Configure Remote Study Upload…**. No storage is connected by default.
Choose a research HTTPS receiver or a Google Drive Apps Script receiver; save configuration,
send a synthetic connection test, then preview and upload a selected recording. You can optionally
upload after future recordings stop. Failures stay in a reduced-data queue for explicit retry.

Remote schema 1 excludes code, file paths, project names, local free-text labels, notes, AI
content, raw gaze/video and absolute event times. It retains study/participant/task codes,
relative viewing events, within-session file/region IDs and configuration/quality summaries.
Local JSON schema 3 and local source snapshots are unchanged. The connection needs a deployed
receiver; a Drive folder URL alone does not work. A receiver template and setup instructions
are available under **Data & setup** in the dialog and in [remote-upload documentation](../docs/remote-upload.md).

**This is an experimental research connector, not a preconfigured collection service.** Select
variables and storage for the research questions, establish consent/ethics/institutional requirements,
and test end-to-end access, upload, retry, retention and withdrawal before collecting participant data.
The synthetic test establishes connectivity/authentication, not study readiness or ethics approval.

## Local build and test

Open this checkout as a Gradle project. Java output is 17. Use an installed JDK/JBR:

```bash
./gradlew '-PlocalIdePath=/path/to/IntelliJ IDEA.app/Contents' test buildPlugin verifyPluginStructure
node --test src/test/js/*.test.mjs
```

Install `build/distributions/implicit-gaze-1.0.0.zip` manually using **Settings → Plugins → gear →
Install Plugin from Disk…**. The build does not install or start IntelliJ. Default SDK is IDEA
Community 2023.1; native AI additionally needs a compatible installed AI plugin.

See [manual test steps and validation](../docs/local-testing.md), [gaze input](../docs/gaze-input.md),
and [release notes](../CHANGELOG.md).

Webcam inference uses [UnitEye](https://github.com/wgnrto/uniteye), MediaPipe and EyeMU in a
windowless embedded browser. No separate webcam browser window is created. Pinned runtime
URLs may require internet; caching is not an offline guarantee. See
[third-party notices](../src/main/resources/gaze/THIRD-PARTY-NOTICES.md). Live accuracy, native AI
handoff and usability require manual IDE checks. No empirical benefit is claimed.

## Standalone edition

A local browser-based GAZEAIDE edition with the same tracker assets, viewing scenarios, captured
context, records and review walkthrough is available in [standalone/](../standalone/README.md). It
connects to Codex through App Server and an optional read-only MCP bridge. See its
[review and parity report](../standalone/docs/REVIEW-AND-PARITY.md) for reuse, host differences and
validation limits. The plugin and standalone remain separate hosts of the same research tool.
