# Optional remote-study upload

This feature is **off by default**. It provides a configurable connector, not a provisioned
storage account or a ready-to-run study. There is no live streaming or startup upload.

Choose the variables and storage in relation to your research questions. Separately establish
participant information/consent, ethics review where required, institutional permission,
data-processing agreements, access, retention, withdrawal and deletion. Test the actual receiver
and study setup with synthetic sessions before recruitment. Pseudonymous data and code layout
can remain sensitive. A successful connection test is not ethics approval or validation of the study.

## In the plugin

1. In **Session**, choose **Configure remote upload (optional)…**. It is also available in
   **Results** and **Tools → Implicit Gaze → Configure Remote Study Upload…**.
2. Leave it off until you have a receiver. Choose **Research server (HTTPS)** or
   **Google Drive (Apps Script receiver)**. The latter is not built-in Google OAuth sign-in.
   You can preview a stopped recording's reduced data before choosing or connecting storage;
   unconfigured study/task codes remain blank in that preview. Preview sends nothing.
3. Enter the receiver address, study code, participant pseudonym, optional task code and the
   participant's upload token. Codes use 1–80 letters/digits/dots/underscores/hyphens. Tokens
   use 32–256 letters/digits/underscores/hyphens; use a distinct high-entropy token per participant.
   Never enter a database password, Google password, service-account key or cloud admin secret.
4. Read the notice, acknowledge the need for separate setup/testing, enable the connection and
   choose **Save connection**. Save does not contact the receiver. Blank token input keeps the
   saved token only for the same project/connector/address/study/participant binding.
5. **Test connection (synthetic)** sends a test message with the configured study/participant codes
   and a new random upload ID, without recording events. For Drive,
   it checks authentication and folder lookup; it does not test a recording write. In
   **Upload & queue**, preview a synthetic recording, upload it, and verify the actual stored file.
6. Manual uploads use the selected stopped/reopened recording with the configured participant/task
   codes. Check that they match the recording; local free-text labels are not used. Automatic uploads are a separate,
   default-off switch: it must be enabled before Start recording and unchanged when Stop occurs.
   It makes one attempt and never retroactively uploads old recordings. A busy uploader skips the
   automatic attempt and gives instructions to upload the locally retained recording manually.
7. Failures remain in the local reduced-data outbox. **Retry pending uploads** is explicit and
   sends only to their original connector/address/study/participant. A changed task code never
   relabels a queued recording. Retries reuse their original UUID. An acknowledgement prevents
   further delivery of that session for that connection.
8. **Disconnect & remove token** disables uploads and removes the current stored token. It does
   not delete cloud files. **Delete pending uploads** deletes local unsent reduced payloads only,
   retaining full local recordings and acknowledged receipts. A server may have stored data even
   when its acknowledgement was lost; inspect the cloud before re-uploading a discarded pending
   session, which would receive a new UUID. Cloud withdrawal/deletion remains a researcher procedure.

The main workflow remains Input → Scenario → Session → Review setup → Start recording.
Recording cannot depend on the receiver being online. Network and password-storage operations
run off the UI thread, with HTTPS, bounded acknowledgements and request timeouts.

## Upload schema 1 versus full local exports

The upload is built field by field; it is **not the existing session JSON, CSV or HTML export**.

Included:

- Protocol/schema/plugin version; random upload UUID; configured study, participant and task codes.
- Session duration, relative visit times, durations, code-line/column positions, session-local
  `f1`, `r1` file/region IDs and region bounds. IDs reflect first encounter, not source identity.
- Explicit WEBCAM/MOUSE_PROXY/MOUSE_SIMULATION provenance. Legacy dwell-trigger flags describe
  the old `explained` field, not evidence that an AI request was sent or a response understood.
- Initial scenario/features, response settings, cursor visibility, calibration quality summaries,
  display size, personalization summaries, review purpose, quiet-offer policy and configuration
  changes including pauses.

Excluded:

- Source text, identifiers, region labels, file paths, project names and local participant/task/
  condition free-text labels. Use separate study/task codes in the connection dialog.
- Notes, walkthroughs, AI prompts/answers, raw gaze/video, event epoch times, calibration coefficients
  and profile creation/check times.

Opaque file/region IDs restart per recording. This default cannot establish which source file a
participant viewed across multiple sessions. A study requiring stable supplied-code mapping,
condition codes, raw tracking samples or additional outcomes needs a separately reviewed schema
extension; do not silently infer them from these events. Visit duration is not a physiological
fixation measure or comprehension outcome. Duration includes pauses; visit exposure excludes them.

The server still observes network metadata such as IP addresses and arrival times. Configure
server/provider logs, account access and retention separately; minimization is not anonymity.

## Google Drive template

The dialog's **Data & setup** tab contains complete researcher instructions and a button to save
the bundled [Apps Script receiver](../src/main/resources/remote/google-drive-receiver.js).
The same [setup text](../src/main/resources/remote/setup.txt) is available in this checkout.

Use a private folder and an institution-approved account, preferably dedicated to the study.
The researcher grants the script Google Drive access; the plugin receives only the web-app address
and participant upload token. Deploy as the researcher, accessible without Google sign-in. The
public endpoint authenticates individual tokens before accessing Drive and has no read/list API.
If the institution disallows such deployments, use its approved HTTPS service instead.

Set Apps Script properties `STUDY_ID`, `FOLDER_ID` and `PARTICIPANTS_JSON`, the latter mapping
participant codes to unique random tokens. For example, generate tokens with `openssl rand -hex 32`.
Do not send participants the token list or grant them access to the destination folder. Removing
their property entry revokes future submissions. Tokens authorize submission for their participant
code; protect them accordingly. Files are named by UUID and retries are checked under a script
lock, without overwriting conflicting content. The receiver rejects unknown fields recursively.

Apps Script quotas, public-endpoint abuse, Drive permissions, Google logs, support, retention and
residency require separate assessment. This template is intended for a pilot and provides neither
an administrative dashboard nor production rate limiting. It makes no EU-only storage guarantee.
Google's ContentService redirects are followed only to the exact HTTPS content host/path, as GET
requests without authorization headers or request bodies. Google sign-in/other redirects fail.

References: [Apps Script web apps](https://developers.google.com/apps-script/guides/web),
[ContentService redirects](https://developers.google.com/apps-script/guides/content#redirects).

## Other databases/storage

Place a managed HTTPS receiver in front of Supabase, S3 or institutional storage. Do not expose
database credentials through the plugin. Generic receivers accept a JSON payload with a participant
Bearer token and return `{"ok":true,"uploadId":"same UUID"}` only after durable storage.
`kind=connection_test` must authenticate/validate and acknowledge without storing a participant
recording. Enforce study/participant authorization, recursive field allowlists, limits and
idempotency. The Drive script and shared Java/JavaScript fixture document schema 1.

Generic connectors follow no redirects. Tokens are never put in query strings. The Drive connector
uses a `{token,payload}` envelope because Apps Script doPost does not expose arbitrary HTTP headers.
No token is stored inside the dataset or receiver response. There is no payload logging.

Limits: 8 MiB request, 50,000 visits, 5,000 configuration changes, 16 KiB acknowledgement, a
15-second connection timeout and 30-second request timeout per request. Limits fail explicitly
without truncating the local recording. The Drive envelope also counts against the request limit.

## Local state and validation boundaries

Non-secret configuration is project-local in the IDE workspace file. Tokens use IntelliJ
PasswordSafe ([SDK documentation](https://plugins.jetbrains.com/docs/intellij/persisting-sensitive-data.html));
its persistence depends on the IDE's configured password provider. Tokens are read/written on
background threads. Neither tokens nor endpoints are exported in session data.

The reduced outbox and receipts are under the IDE system folder:
`implicit-gaze/remote-outbox/<project-hash>/`. They are separate from full local sessions,
contain no credentials/endpoints/source snapshots, and do not retry on startup. Clearing IDE
caches or moving the project can affect access; these files are not a study backup system.

Automated checks cover payload minimization, input provenance, credential/redirect handling,
acknowledgements, limits, queue restart/idempotency and schema parity, receiver authorization/
deduplication and native panel integration. Mock receivers and synthetic Swing previews do not
validate a deployed Google account, OS password provider, cloud durability/permissions/retention,
physical gaze accuracy, accessibility, usability or a study's ethical/legal readiness. No live
cloud deployment or participant-data transfer is part of this local implementation.
