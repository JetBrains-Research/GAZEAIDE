# Blink activity and a personal reference

Available in both editions starting with **1.1.0**. This feature records webcam eye-closure observations alongside viewing events. It does not adapt the interface, train an agent, send questions automatically, or diagnose fatigue.

## What the measurements mean

**Cognitive workload** concerns the demands of the current task relative to available resources. **Mental fatigue** develops with sustained activity; **sleepiness** concerns the propensity to fall asleep. They can occur together. Blink frequency and duration also depend on visual attention, task, lighting, eye comfort, glasses, camera angle and individual differences. Blinks alone do **not** reliably disentangle these constructs. A low rate must not be interpreted as either concentration or fatigue by itself.

The display therefore reports blink rate, estimated duration, threshold-closed fraction, sampling quality and an experimental **closure deviation** from a personal rested reference. A higher score means larger eye-closure measurements relative to that reference. It is not a fatigue probability, a validated fatigue scale, a cognitive-load estimate or a medical assessment. A low score does not establish alertness. The threshold-closed fraction is not standardized PERCLOS.

## First recording

1. Start the edition you want to use. Connect the real webcam and follow the gaze calibration/check instructions. Mouse proxy and simulation cannot supply blink observations.
2. Select **Personal baseline & blink activity**. This enables blink collection and leaves visual effects off. Alternatively enable **Collect webcam blink observations** in Session while using another scenario.
3. Set a participant code and camera/reference group. Blank participant means `local-user`; the default group is `default-camera`. Use the same values for the same person/setup. Change the group when changing camera, glasses, lighting or placement enough to affect eyelid measurement. Never share a participant/group identity between people.
4. If you feel rested and want this recording to contribute to the reference, explicitly tick **Rested reference recording**. Optional fatigue and mental-effort ratings are separate 0–10 self-reports at recording start; `−1` means unreported. A fatigue rating above 2 excludes that recording from the rested reference even if ticked.
5. Review the setup and start recording. Read normally for at least three minutes with the app/IDE in focus. Blink activity is observed whenever the recording is active and the relevant window is focused; it is not restricted to time spent looking at a code line. Pause, focus loss and unavailable face observations interrupt collection.
6. Stop and inspect the blink summary in Results. JSON includes all events and minute windows. **Export blink events CSV** is available in the standalone; plugin **Export recording** adds a `-blinks.csv` file alongside the existing CSV/JSON/HTML files. The ordinary viewing CSV still contains viewing visits only.
7. Start a **later** recording using the same participant/group to use the saved reference. Self-ratings and the rested checkbox reset after finishing; mark each reference recording intentionally. Camera collection begins only during an explicitly started recording with collection enabled.

The reference is local to the current edition's recording library (project-scoped in the plugin, data-folder scoped in standalone). There is no automatic pooling between editions or projects. The existing short **Personalization** scenario in the plugin, and **Fit personal response** under Response & personal profile in standalone, choose a gaze-smoothing preset; it does not build this longer-term reference.

## If the display says insufficient or stale

A window is usable after at least **45 seconds of observed time**, at least **75% observation coverage**, and at least **25 valid eye observations/second** across its elapsed duration. Complete reference windows are 60 seconds long. At least **three** usable complete windows and **ten** counted blinks are needed for a reference. They can come from several saved rested recordings. These are engineering quality gates, not empirically validated accuracy thresholds.

No score appears before the reference is ready. Low-quality, interrupted or short windows are kept with flags rather than turned into valid zeros. A measured rate of zero is different from no valid observed time. Keep collecting, check face visibility/lighting, and inspect sampling speed. Slow hardware may not meet the gates. Do not force a score by treating missing observations as blinks. A prior complete minute may remain displayed while the next minute fills; the state becomes stale on an observation interruption, after a one-second observation timeout, or when that window is more than one minute old.

## What is saved

The additive `blink` object in recording schema 4 has its own schema version 1 and detector policy `webcam-blink-v2`:

- `config`: participant/group, explicitly rested flag, optional start self-ratings and suggestion preference.
- `events`: epoch timestamps, `BLINK`, `SHORT_CLOSURE`, `PROLONGED_CLOSURE` or `INTERRUPTED`, estimated duration, sample bounds, maximum sampling gap or interruption reason.
- `windows`: minute timing, valid observed milliseconds, threshold-closed milliseconds, frame/invalid/gap counts, blink and prolonged-closure counts, rolling rate per observed minute, median duration, closed fraction, coverage, effective sampling frequency, complete/usable flags.
- `reference`: frozen statistics from earlier matching rested recordings, number of windows/sessions, readiness and learning cutoff.
- `indicator`, `summary`, last valid observation timestamp and truncation flag.

No camera video, images, raw eyelid landmarks or continuous raw eye-ratio stream are retained. The recording still contains the normal viewing/source context; it can be sensitive. Blink events and self-reports add personal behavioral data. Delete local recordings under the edition's normal data-retention procedure to remove them; exported copies and already sent prompts need separate handling.

## Detector and indicator policy

The tracker observes bilateral eye aspect ratios (EAR) from face landmarks before gaze prediction. It schedules at most one gaze prediction at a time; eye observations can continue while that prediction runs. Actual frame rate depends on hardware. Repeated reads of one video frame are excluded. Gaze smoothing is not applied to eye-closure observations.

Both eyes must fall below EAR 0.15 to enter closed state and reach 0.18 to reopen. The band between them uses hysteresis; one clearly open and one clearly closed eye is uncertain. A counted blink requires a preceding stable-open period of at least 60 ms, at least two closed observations, and an estimated closure of 40–700 ms. Longer episodes are separately marked prolonged; shorter ones are retained but not counted as blinks. Duration is first-closed to first-reopened sample. Bounds are last-closed minus first-closed, and first-reopened minus last-open. These are sampling bounds, not confidence intervals or guarantees of physiological onset/offset.

Gaps above 120 ms, face/landmark loss, asymmetric uncertainty, pauses and loss of focus cancel incomplete episodes. Only contiguous valid intervals enter observed-time denominators; only intervals closed at both ends enter closed time. A cross-minute event is counted in the minute where reopening is observed. At most 50,000 episodes and 1,440 minute bins are retained per recording; exceeding a limit sets `truncated`.

The personal reference takes up to the latest 240 usable complete windows from earlier, untruncated, explicitly rested recordings with matching participant/group and detector policy. The reference is frozen at recording start, so the current recording cannot normalize its own indicator. It can contribute to the next recording if explicitly marked rested. Imported usable flags alone do not qualify a window: timing and metric consistency are also checked.

Reference medians and median absolute deviations (MADs) are computed over window medians/fractions, with the upper middle value for even counts. The score averages positive normalized changes in threshold-closed fraction and (when at least one blink exists) median duration. Denominators are `max(0.01, 1.4826 × fraction MAD)` and `max(40 ms, 1.4826 × duration MAD)`. Multiply the average by 25, round, and cap at 100. At 50 or above the state is `ELEVATED_CLOSURE`. Floors, scaling and cutoff are provisional engineering choices. Blink frequency has **no fixed directional contribution**. Optional suggestions default off; enabling them permits a nonmodal break suggestion at elevated closure, with no automatic action.

## AI context and research use

With collection enabled, the existing explicit-send context mechanism can include a bounded blink summary, quality state, reference readiness and optional self-ratings. Review the context preview or turn off **Attach recent context** before sending if you do not want that disclosure. Participant/group codes and individual blink events are not included in this summary. Read-only MCP clients connected to the companion can retrieve its published context summary as well. No additional model request is triggered. Native Codex messages outside the companion/handoff are not intercepted. The model receives observations and their limitations, not a fatigue label.

Historical research context uses only windows ending at or before that context timestamp. A window older than two minutes is omitted; a reference learned later is not used to score an earlier window. Recording JSON/JSONL supports exploratory model development, but the closure score must not become ground-truth fatigue or workload. The reduced remote study-upload schema excludes blink data and self-ratings. Redeploy the bundled receiver template before uploading a session with the new `BLINK_BASELINE` scenario ID; older receivers reject that new ID.

Before research claims, validate event precision/recall and duration error against consented manually annotated or reference-tracker recordings across relevant cameras, lighting, glasses and participants. Report missingness and sampling speed. Assess workload, fatigue and sleepiness separately using appropriate independent measures; vary task demands/time on task and control relevant confounds. Evaluate personalized thresholds with participant/session holdouts and a frozen reference. Software and synthetic tests establish deterministic behavior, not physiological validity, human usability or improved agent answers.

## Research background

- Bafna & Hansen (2021), [Mental fatigue measurement using eye metrics: A systematic literature review](https://pubmed.ncbi.nlm.nih.gov/33825234/), DOI: 10.1111/psyp.13828. Background on eye metrics and mental fatigue; this implementation is not validated by that review.
- Nyström et al. (2024), [What is a blink? Classifying and characterizing blinks in eye openness signals](https://link.springer.com/article/10.3758/s13428-023-02333-9). Definitions, signal quality and measurement method affect detected blinks and durations. Their eye-tracker results do not validate this webcam detector.
- Gorin et al. (2024), [A Review of the Use of Gaze and Pupil Metrics to Assess Mental Workload in Gamified and Simulated Sensorimotor Tasks](https://pmc.ncbi.nlm.nih.gov/articles/PMC10975796/), DOI: 10.3390/s24061759. Task-dependent evidence motivates keeping workload separate from fatigue.
- Stern, Boyer & Schroeder (1994), [Blink Rate: A Possible Measure of Fatigue](https://journals.sagepub.com/doi/10.1177/001872089403600209). An association does not establish a universal blink-rate threshold or a unique mental-state inference.

## Sampling policy v2

Current recordings use `webcam-blink-v2`. A usable window requires at least 25
valid observations per second on average, in addition to the coverage and observed-time
gates. Below this cadence, short closures can disappear between samples even when
coverage is high. This quality gate does not establish accuracy at or above 25 Hz.
Earlier `webcam-blink-v1` recordings remain readable but are not used as v2 references.
Both editions preserve monotonic capture timing; brief unavailable-tracking gaps pause
region dwell for up to 500 ms without counting the gap as observed exposure.
