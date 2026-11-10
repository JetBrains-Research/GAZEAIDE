# Changelog

## 1.1.0 — 2026-11-10

- Journal recordings during capture, recover interrupted sessions, and store standalone region snapshots once with retryable append batches.
- Remove secrets from unauthenticated pages, isolate imported recording IDs, escape report fields, and bundle checksum-verified webcam runtime dependencies for offline use.
- Pause region dwell across brief tracking gaps; correct word attribution, wrapped-row hysteresis, browser text columns, mouse-scroll targets and exported input provenance.
- Use monotonic capture timing and stricter blink sampling quality gates; preserve legacy recordings without pooling older detector policies into new references.
- Recover abandoned library locks, isolate damaged upload queue entries, queue colliding automatic uploads, and cache large-file fading regions.
- Add both test suites and Plugin Verifier to CI; migrate startup to ProjectActivity and generate runtime version metadata from VERSION.

- Add opt-in webcam blink events, minute windows and quality flags in both editions.
- Add Personal baseline & blink activity with saved rested-reference recordings, participant/camera isolation, optional separate fatigue/effort ratings and an experimental closure-deviation indicator.
- Add local JSON/blink CSV/report exports and bounded explicit-send context summaries. Reduced remote uploads continue to exclude blink observations and self-reports.
- Observe eyelids independently of pending gaze predictions; cancel interrupted closures and reject pre-loss gaze results. No automatic adaptation or extra AI requests.
- See [blink measurements and limits](docs/BLINK_ACTIVITY.md) for the detector policy, novice workflow, privacy and validation requirements.

## 1.0.0 — 2026-11-05

First coordinated GAZEAIDE research release of the IntelliJ and standalone editions. This snapshot follows plugin 0.11.1 and standalone 0.3.1; the 1.0 promotion changes release metadata and documentation rather than adding a new tracking algorithm.

- Both editions filter visits by the requested time window before applying the 5,000-visit context cap. Earlier windows in long recordings retain their historical context.
- Plugin calibration extends collection up to six seconds, pauses after three failed attempts, and supports unlimited explicit retries of the same target while retaining completed targets.
- Standalone research libraries have a single owner; second launches, data-folder aliases and occupied ports cannot recover another running instance's records.
- Fast Codex completions and disconnections clear active-turn state while preserving research linkage.
- Shared agent-context and JSONL examples retain source/input provenance and uncertainty. Standalone replies are observed; plugin replies require labelled transcription.
- Fresh project-only source snapshot, instructions for first-time readers, privacy notes, attribution, and separate dependency notices.

## Version policy

Both editions now use the same release number for coordinated snapshots. Patch releases correct bugs or documentation; minor releases add compatible capabilities. A major release identifies a deliberately supported research workflow, not an empirical efficacy or hardware-accuracy guarantee. Persisted/exported schema versions are separate from release versions and remain explicitly recorded.
