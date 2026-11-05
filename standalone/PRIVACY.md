# Privacy and sensitive material

GAZEAIDE is a local research application, not an anonymity or confidentiality guarantee. Use synthetic/public code for initial checks. Obtain the necessary permissions before collecting participant data, exporting recordings or sharing nonpublic source with an assistant.

## What stays local

Webcam frames are processed locally by the browser/JCEF tracker; this application does not record or upload those frames. Runtime/model assets are downloaded from the listed CDNs unless cached, so those services receive ordinary network metadata. Calibration and response summaries are saved locally; raw calibration-task samples are discarded after the task.

The standalone stores settings, sessions and agent examples in `standalone/.local/` by default, or your `--data` directory. The plugin uses the IDE's local system/settings storage, with records separated by project. Stopped recordings and exported JSON/CSV/HTML can include captured source text, file paths, timing, participant/task codes, conversation excerpts and manual notes. These exports may be sensitive even without video.

## What can leave the machine

- **Standalone Send question:** sends the reviewed question and attached context to the configured Codex provider. The CLI handles sign-in and provider configuration. GAZEAIDE requests read-only behavior, but configured instructions/tools and the Codex environment still matter.
- **Plugin Copy prompt & open AI Chat:** replaces the clipboard with the reviewed text and opens native JetBrains AI Chat. You paste and send. Native chat can use its own context under its own settings.
- **MCP tools:** provide bounded captured context to a configured local assistant when it calls them. Inspect the context and its age. They do not automatically submit a question.
- **Optional remote study upload:** only after explicit configuration and acknowledgement, sends a reduced allowlisted schema to your receiver. It excludes source, paths, prompts, answers, conversation IDs, free-text notes and raw gaze/video. Participant/task codes and timing summaries still require appropriate study governance. There is no preconfigured collection endpoint.

Viewing, recording, previewing and exporting alone do not trigger a model request. Saving a research example is a local operation; sending it to an assistant or exporting it is a separate disclosure. Automatic attachment applies only to questions sent through the companion or plugin handoff, not all messages typed in native Codex/AI Chat.

## Keeping a repository clean

Never commit `.local/`, connection files, credentials, `.env` files, participant recordings or exported research examples. A custom `--data` folder should be outside source control. `.gitignore` protects common paths but cannot recognize every custom export. Review staged files and Git history before publication; deleting a file from the latest commit does not erase its earlier versions.

Bundled examples and test fixtures are synthetic. Review your own recordings, exports and configuration before sharing them.
