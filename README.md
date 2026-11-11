# GAZEAIDE

**GAZEAIDE** is a research tool for exploring how estimated viewing context can support code review and AI-assisted work. This repository contains two editions of the same tool. Start with a mouse; a webcam and AI account are optional for the first demonstration.

| Edition | Where it runs | AI connection | Start here |
| --- | --- | --- | --- |
| IntelliJ plugin | Inside IntelliJ IDEA | Prepares a prompt for JetBrains AI Chat; you paste and send it | [Install and try the plugin](docs/GETTING_STARTED.md#intellij-plugin) |
| Standalone | A local Node.js server and your browser | Its own Codex conversation, plus optional read-only MCP tools | [Run the standalone](docs/GETTING_STARTED.md#standalone) |

Both provide viewing-driven visual scenarios, smoothing, calibration with retry, local recordings, captured context, research records, feedback and exports. The standalone also displays local Codex conversation history and records its own observed replies. The plugin cannot observe native AI replies; optional pasted answers are labelled as user transcriptions.

## Viewing maps (1.2.0 source)

Both editions now provide captured-code maps by viewing time or visit count, with time/input filters, recorded calibration quality, and SVG/PNG/CSV/JSON exports. See the [viewing-map guide](docs/VIEWING_MAPS.md). The published 1.1.0 packages below predate these additions.

## Blink activity and recording reliability (1.1.0)

Both editions support opt-in blink observations and a longer-term personal rested reference. Version 1.1.0 also adds recording journals/recovery, security fixes and bundled offline webcam runtimes. See the [step-by-step blink guide and measurement limits](docs/BLINK_ACTIVITY.md).

See the [recording, security and measurement hardening notes](docs/HARDENING-2026-11-10.md) for current-source behavior and remaining validation limits.

## Project origin

GAZEAIDE was started by **[Heidi Albarazi](https://heidialbarazi.me/)** during her internship at **JetBrains Research**, and was subsequently updated by **Ilia Zaharov** ([LinkedIn](https://www.linkedin.com/in/ilya-m-zakharov/), [Google Scholar](https://scholar.google.com/citations?user=MTOEI-oAAAAJ&hl=en)). The project includes the original IntelliJ plugin and a standalone edition of the same research tool.

## First run without a camera or AI account

Install [Node.js 22 or newer](https://nodejs.org/en/download), download/extract this repository, then open a terminal in its root and run:

```sh
node standalone/launch.mjs
```

Open the private launch URL printed in the terminal if a browser does not open automatically. It contains a per-launch access key; keep it private. Click **Try sample conversation**, keep **Mouse proxy**, then **Review setup & start → Start recording**. Move the pointer across passages. The viewed block stays clear while surrounding text dims. **Stop recording** opens results and exports. Keep the terminal open; Ctrl+C stops the server. No npm dependency installation is needed for GAZEAIDE.

For the packaged editions, use the ZIPs attached to the [v1.1.0 release](https://github.com/JetBrains-Research/GAZEAIDE/releases/tag/v1.1.0). The plugin ZIP is `implicit-gaze-1.1.0.zip`; the standalone ZIP is `gazeaide-standalone-1.1.0.zip`. Do not install GitHub's automatic source ZIP as an IntelliJ plugin.

## Instructions

- [Start here: requirements, installation, camera-free test, webcam and Codex](docs/GETTING_STARTED.md)
- [Detailed IntelliJ workflow](docs/INTELLIJ.md) and [standalone workflow](standalone/README.md)
- [Research examples, feedback and JSONL](docs/agent-research.md)
- [Privacy, sensitive code and data handling](PRIVACY.md)
- [Build, tests and release checks](docs/BUILD_AND_TEST.md)
- [Changes and version policy](CHANGELOG.md)
- [Attribution and licensing](LICENSING.md)

## Research scope

Version **1.0.0** marks the first jointly documented research release. Earlier plugin 0.11.1 and standalone 0.3.1 were separate development iterations. A 1.0 version does not establish hardware accuracy, human usability or improved AI answers. The tool records estimated exposure and optional labelled examples; it does not automatically train a model or infer comprehension, confusion or review correctness.

Webcam estimates depend on lighting, camera, seating and calibration. Smoothing reduces jitter and adds delay; it does not correct a large position bias. Mouse modes are labelled **MOUSE_PROXY** or **MOUSE_SIMULATION** and are not eye tracking. Calibration is separate for each edition and display/window geometry.

Both editions run locally. Webcam mode uses bundled, checksum-verified runtime/model dependencies and can start offline. Optional assistant sends share reviewed text with the configured provider; exported recordings may include source code and conversations. See [privacy](PRIVACY.md) before using nonpublic code or participant data.

## License

Project code is [GPL-3.0-only](LICENSE), with [author attribution and separate dependency terms](LICENSING.md).

## Source layout

The repository root contains the IntelliJ plugin (`src/`, Gradle wrapper, `build.gradle.kts`). `standalone/` contains the browser edition, its Node server, tests, MCP bridge and exporter. `docs/` covers both editions. Examples and test fixtures are synthetic. Runtime data is stored separately from source; see [privacy](PRIVACY.md).
