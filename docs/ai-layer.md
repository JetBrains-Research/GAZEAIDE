# JetBrains AI Chat integration

GAZEAIDE uses the installed **JetBrains AI Assistant / unified Junie AI Chat**. It does not
choose a model, store API credentials or send AI HTTP requests.

## Setup

Enable AI Assistant / Junie in IntelliJ's Plugins settings and complete sign-in/setup in
native AI Chat. Choose the assistant, model and account there. For explanations with Junie,
choose its read-only **Ask** mode. GAZEAIDE does not switch agent mode or run agent tasks.

The integration indicator detects registered actions, not account readiness or a successful
model response. AI features are optional; visual adaptations and recording work without them.
Older IDEs or separate Junie versions may not expose the verified action IDs. The plugin
shows an unavailable message in that case; it does not fall back to a remote provider.

## Prepare and ask

1. Choose **Gaze-assisted review**, then a purpose: **Explain this block**, **Compare viewed
   regions** or **Review for possible problems**. The choice is explicit, never inferred from
   gaze. Explain includes only the target block; Compare/Review may include up to 12 recent
   region visits over 45 seconds and at most three recent source snapshots.
2. Read within one block for 3 seconds; moving between its lines does not restart the timer.
   Context prepares locally without changing editor layout. Compare needs two distinct viewed
   regions before an offer is eligible. Optional quiet offers stay in the recording panel,
   at most once per 30 seconds. Open or dismiss an offer; it retains its original captured
   prompt while newer context is prepared. Editing source clears pending offers. With offers
   disabled, preparation still runs and **Preview captured AI context…** remains available.
   **Open offered question…**, **Ask JetBrains AI about viewed code…**, or the optional blue
   gutter dot opens an editable prompt preview. Looking alone sends nothing and opens no chat.
3. **Copy prompt & open AI Chat** copies the reviewed prompt to the clipboard and invokes
   the installed native action. If a matching source/version is open, it selects that code
   for the native action. It does not attach unrelated current code to an old snapshot.
4. **Paste and send in AI Chat.** The prompt is not automatically inserted or submitted.
   Responses appear in native chat. Account, model, permissions and data handling belong to
   the JetBrains tool you use there.

The action path uses public IntelliJ Action System APIs and verified installed IDs
`AIAssistantAskInChatAction`, or `ActivateElectroJunToolWindowToolWindow` when the first action
is absent. A disabled Ask action is not bypassed by invoking the fallback. Native action
availability/ignore/indexing checks run before clipboard or selection changes. Registration
IDs may change with JetBrains releases; live integration still needs manual verification.

## Walkthrough

Gaze selects a captured step. **Ask JetBrains AI about this step…** previews a bounded prompt
with the review goal and at most 12 snapshots (about 20,000 code-context characters), selected
step first. It asks for observable behavior, rationale labelled as inference, a review check
and missing context. It requests explanations without edits or command execution.

There is no public response-return API used here. Answers are **not automatically imported**
into the walkthrough. Add findings to **Your notes** and mark steps reviewed explicitly.
Saved AI drafts from earlier plugin versions remain viewable/exportable. Existing structured
JSON validation remains for loading/testing these older draft data; it is not a live provider.

## Research interpretation and data

Viewing exposure is not confusion, intent, understanding or correctness. Source comments are
passed as data. Model claims still need checking against code. Snapshots are not a complete
Git diff, generation history or actual author reasoning. Native AI may use additional project
context under its own controls, so this is not a controlled comparison of a fixed prompt/model.

**Inspect AI context for this session** shows the retained local draft for the selected recording,
including paths and snapshots. After reopening a recording, it reconstructs a new draft from
its captured source snapshots and explicitly labels it **Reconstructed**. This also works for
recordings where the earlier single-line dwell never fired. Reconstruction honors the recorded
question purpose and history setting, separates input segments and excludes earlier source versions. It never reads
today's source files to replace recorded code. A missing snapshot produces actionable instructions.
It is not a request/response log and does not inspect tour prompts. Editing source invalidates
all transient sendable context and pending offers; pause/input/setup changes clear sendable context. Starting a new recording
clears the previous live draft. A retained original draft remains inspectable only for its session. The legacy `explained` event
field now means **context-preparation dwell fired**, not that AI was called or read. Pre-0.7
recordings used it for direct-request triggers. Neither proves an answer arrived.

Session metadata identifies the integration as JetBrains AI Chat; the actual native model is
not observed. Add model/account/agent condition to study notes if you need it. Recording exports
do not contain chat responses. Tour notes can contain any findings you manually add.

Official behavior references:
- [Explain code in AI Assistant](https://www.jetbrains.com/help/ai-assistant/explain-code-with-ai.html)
- [AI Chat](https://www.jetbrains.com/help/ai-assistant/chat-mode.html)
- [Junie IDE integration and Ask mode](https://junie.jetbrains.com/docs/junie-ide-plugin.html)
- [IntelliJ Action System](https://plugins.jetbrains.com/docs/intellij/action-system.html)

The recording panel labels **Context prepared locally**, **Prompt preview opened**, and
**Prompt copied and native chat opened** separately. The last handoff action is not a send
receipt. Quiet offers are separate from the optional gaze cursor; hiding the cursor does not
stop preparation or offers. No external model/provider or automatic native-chat submission
is performed by this integration.

## Research records

The explicit handoff can append recent context and save a local example. See
[Agent research workflow](agent-research.md) for limits, feedback and JSONL export.
Native delivery/replies remain unobserved; directly typed native prompts bypass this path.
