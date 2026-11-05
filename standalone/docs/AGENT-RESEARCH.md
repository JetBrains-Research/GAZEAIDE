# Agent context and research records — plugin 1.0.0 / standalone 1.0.0

The two editions collect interaction context for studying agent assistance. The effects in the
viewer provide feedback; the research deliverable is versioned context linked to explicit
questions, observed or transcribed answers, and optional user feedback. No extra model requests
are triggered by looking, recording, previewing, saving feedback or exporting.

## Try it in the standalone

1. Start the app and open its local URL. Try **Sample conversation** with **Mouse proxy** first.
2. For viewing history, choose a scenario, **Review setup & start**, then **Start recording**.
   Read passages, generated code or recorded diffs in the central viewer. Stop when finished.
   You can also send questions without recording; visible content is still available as context.
3. Keep **Attach recent context** enabled. **Preview attachment** shows what is available now.
   Sending captures a fresh packet; the preview is not a frozen promise of the later payload.
4. Keep **Save research example locally** enabled and click **Send question**. That is the only
   model request. The companion uses its own Codex conversation; an opened history session is
   never resumed for the follow-up.
5. Open **Codex → Agent research records & feedback**. After completion, inspect the question,
   answer and context; select a helpfulness label and save optional feedback. Refresh records
   if needed. Export **agent dataset JSONL** for offline analysis.
6. For a question-only condition, use the plain question composer and switch off **Attach recent context**. The exact question is
   sent unchanged. If research saving stays on, the available-but-unused packet is retained
   locally with `contextAttached=false`. Use **New conversation** between experimental conditions
   so earlier attached context cannot leak through conversation history.

Records are atomic files under `.local/agent-examples/`, or the selected `--data` directory.
Existing recordings and settings are preserved. No raw camera frames or raw task gaze samples
are added. Context and answers can contain sensitive source or conversation text; they stay in
this local library unless explicitly exported or sent to the configured assistant. Reduced
remote-study uploads exclude these examples, source, prompts, answers and feedback.

## Try it in IntelliJ

1. Install `implicit-gaze-1.0.0.zip` manually, then restart if requested. No automatic installation.
2. Use **Tools → Implicit Gaze → Ask JetBrains AI about Viewed Code…**, or a captured offer.
3. The editable prepared question, **Attach recent context**, **Preview attachment** and
   **Save research example locally** are shown before **Copy prompt & open AI Chat**.
   The packet is captured when this preview opens. Reopen it to refresh the snapshot.
4. Copy/open explicitly, then paste and send in native chat. The plugin cannot observe delivery
   or replies. It records `copied_to_native_chat; delivery_unobserved`, with `response=null`.
   Questions typed directly into native chat bypass this handoff and are outside this recording.
5. Open **Tools → Implicit Gaze → Agent Research Records…** to inspect records. Optionally paste
   the native answer, select helpfulness and save. Pasted answers are labelled
   `observed=false, origin=user_transcription`. Export JSONL from the same dialog.

Plugin records are in the project-specific IDE system library at
`implicit-gaze/sessions/<project-hash>/agent-examples/`. They are separate from recording exports.
New recording JSON includes `researchRecordingId=intellij-<startMs>` for joining; older recordings
can use the same start-time convention. Native action/account behavior still requires manual
IDE testing. This update does not expose a private JetBrains AI API or intercept other chat UIs.

## Shared data contract

Both editions export `schemaVersion=1`, `recordType=agent-example` JSONL rows. Each row has an ID,
edition, request time, optional recording ID, the editable base `question`, exact composed `text`,
`context`, `contextAttached`, `questionOrigin`, outcome status, `response` and `feedback`. Standalone rows also
include the actual thread and turn IDs. Model identity is null when unobserved, never guessed.
The standalone stores answers observed through its owned Codex connection; `complete` distinguishes
successful completion from a partial answer on interruption/failure. If the connection or app
closes before completion, outcome is unknown. A restarted unfinished record is marked unknown.

Context uses `recordType=agent-context`, policy `recent-exposure-v1`:

- Most recent 5,000 available visits within ten minutes; visits ending after the request are excluded.
  The live standalone snapshot includes the active visit only up to the request time; the plugin
  uses completed visits plus the selected target and visible editor viewport.
- Selected target first, visible content next, recent visited regions after those. Up to eight
  regions and 12,000 JSON characters in total. Individual excerpts start at 1,800 characters;
  older regions are dropped or text shortened to meet the budget. This is a character budget,
  not a model token budget. Aggregated dwell of 200 ms is an inclusion heuristic, not fixation detection.
- Path, captured document version, line span, captured text, truncation flag, recency, exposure,
  visit count, origin and input label. Codex passages retain thread/turn/item provenance and
  distinguish prose, generated code, recorded diffs and command output. Transcript line numbers
  refer to the displayed snapshot, not source-file line numbers. Viewport visibility is labelled
  `VIEWPORT` with zero viewing dwell; it is not gaze evidence.
- Calibration check time, vertical error/jitter when available, response/hold configuration and
  an explicit uncertainty statement. Request-time configuration does not silently describe all
  historical visits; join the recording configuration timeline for earlier settings.
- `WEBCAM`, `MOUSE_PROXY`, `MOUSE_SIMULATION` remain distinct. Different document versions and
  input modes are never merged. Captured snapshots are not checked against current disk content.

The extra text is clearly marked as untrusted captured data. The agent should use it only where
relevant to the question; source comments and conversation passages do not become instructions.
The exact transmitted prompt plus context are retained to support reproducibility. Existing
purpose-specific prepared prompts already include selected source; disabling the extra packet does
not remove that source from the base prompt. `questionOrigin` separates the composer from a
prepared/edited prompt. Existing prepared prompts remain unchanged; the automatic attachment is a separate layer.

MCP adds a fourth read-only tool, `get_agent_context`, exposing the same packet with snapshot age.
It does not inject context into arbitrary Codex sends. Automatic attachment is implemented in
the companion's own Send action; other Codex clients must explicitly request the MCP context.

## Combine exports

In the standalone folder, with Node.js 22+:

```sh
node research-export.mjs plugin-examples.jsonl standalone-examples.jsonl recording.json --out dataset.jsonl
```

The converter accepts multiple JSON/JSONL inputs and refuses to overwrite an existing output.
Viewing-only recordings become minute-based `context-window` rows with null question and answer;
it never fabricates training pairs. Agent examples and context windows should be analyzed as
separate record types. JSONL export is an analysis dataset, not automatic model training.

## Research interpretation

These records make context selection and answer evaluation testable; they do not establish that
larger context improves answers. Compare question-only, conventional context at a matched model
token budget, and interaction-selected context. Keep model/task conditions fixed and start fresh
conversations. Split by participant, project and time to avoid overlapping excerpts crossing
train/test boundaries; never use future visits or the evaluated answer as request-time features.
Earlier answers already visible before a follow-up are legitimate history, labelled with provenance.

User helpfulness is a subjective label. Keep it separate from task tests, independently assessed
correctness, completion time and other outcome measures. Missing native answers remain missing.
Raw-video/point-level retraining is not possible from these visit summaries alone. Any future
raw-sample study would need a separate explicit capture design and data-handling workflow.

## Extra steady

Both editions offer **Extra steady** (260 ms smoothing / 260 ms line hold). Steady (130/160 ms)
remains the default. It reduces synthetic alternating jitter but adds delay; it cannot correct
systematic bias or manufacture missing camera samples. Calibration continues to use raw input.
Personalization evaluates all four presets. Update an existing remote receiver template before
uploading recordings using `EXTRA_STEADY`; old deployments may reject the new enum value.
