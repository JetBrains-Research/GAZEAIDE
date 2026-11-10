# Recording, security and measurement hardening

These fixes are included in the [v1.1.0 release](https://github.com/JetBrains-Research/GAZEAIDE/releases/tag/v1.1.0)
and its source. Existing 1.0.0 release ZIPs remain unchanged and do not contain them.

## Recording reliability and compatibility

The plugin writes completed visits to a background append journal and checkpoints
open visits and blink state every second. Project closing finalizes the recording;
service disposal gives queued disk writes a bounded drain period. Restarting loads
unfinished journals as `inProgress` recordings. A torn final journal record is
ignored; corruption in the middle is not silently treated as valid data.

The standalone uses an NDJSON journal under the selected data folder's
`recordings/` directory. Completed visits share a dictionary: each region snapshot
is sent and stored once in that journal. New visits are scheduled for saving within 250 ms in
idempotent batches of at most 128 events. A separate state file checkpoints the
open visit (including its current region) and mutable session metadata every five
seconds. Stop awaits saving.
Exact batch retries after a lost response do not duplicate visits. A restart can
inspect/export a recovered recording; it does not resume writing that old session.

These are bounded recovery mechanisms. A crash can still lose queued writes and
the interval since the last active checkpoint; they do not guarantee lossless
recovery from power failure or disk failure. Recovery preserves incomplete status
and excludes unfinished recordings from personal blink references.

New local/exported recordings use schema 4. Both `source` and `inputKind` use
`WEBCAM`, `MOUSE_PROXY` or `MOUSE_SIMULATION`. Legacy plugin `MOUSE`/`WEBCAM`
records and standalone schema 3 snapshots remain readable. Simulation is inferred
from an old `simulated` flag when present; missing historical provenance cannot
be reconstructed. Imports receive a fresh local ID and retain `originalId`.

The legacy 32 MiB request and 100,000-event snapshot limits remain on imports and
snapshot APIs. New append recordings do not repeatedly submit their history and
are not capped by those whole-session limits. A regression test saves 100,128
visits sharing a 6.6 KB block, with the block appearing only once in the journal
and journal size below 32 MiB. Long-session viewing/export still needs memory
proportional to the loaded session; recording storage is not a streaming analysis
engine. Remote reduced-upload limits remain separate.

## Access and offline dependencies

The unauthenticated start page contains no API capability. The launcher prints
and opens a private URL with a per-launch key in its fragment. The browser moves
it into tab session storage and removes it from the visible URL. The data folder
uses mode 0700 and generated connection/state files use mode 0600. Existing
loopback, Host, Origin and request-key checks remain in place. Keep the launch URL
private. Processes running as the same OS user remain inside that user's trust
boundary; this is not authentication between people sharing one OS account.

Imported HTML-report values are escaped, including fields normally holding
numbers, and imported visits require finite numeric timing/line fields. Importing
an ID cannot overwrite the original via the browser import workflow.

ONNX Runtime 1.20.1, MediaPipe Tasks Vision 1.0.0, required WASM variants and the
face-landmarker model are bundled. Both editions verify the same tracked runtime
manifest with SHA-256 checksums. Gradle copies that asset set into the plugin;
portable packaging includes it directly. Startup makes no CDN requests. Original
download URLs, sizes, hashes and license texts accompany the assets. The roughly
40 MB source asset set increases download/build size. Checksum failure is an
installation error and requires restoring or rebuilding the package.

## Interaction and measurement corrections

- Tracking gaps of up to 500 ms pause region dwell. Returning to the same region
  resumes the trigger timer with the gap excluded. Longer gaps or changed targets
  reset it. Line visits still end at tracking loss so missing time is not counted
  as line exposure.
- A line change clears the stable identifier; a short visit cannot inherit the
  preceding line's word.
- Standalone row hysteresis uses the active row's height. Columns use browser
  caret/text offsets, including tabs, spans, font differences and wrapped rows.
- Plugin mouse mode closes/remaps the target after scrolling with a stationary
  pointer. Webcam routing and recording start require the primary display;
  this constrains unsupported geometry rather than adding multi-display tracking.
- Capture timestamps use a common epoch anchor backed by a monotonic clock.
  Webcam frame times propagate through the async prediction callback. The plugin
  aligns its browser monotonic clock to the native clock at the bridge and drops
  stale/invalid timestamps. These are frame-read times, not hardware sensor
  exposure timestamps. Durations do not follow later wall-clock adjustments.
- Standalone simulation records the smoothing and line-hold settings it applies.
  Simulation/proxy exports retain their distinct provenance.
- Blink policy `webcam-blink-v2` requires at least 25 valid observations/second,
  in addition to existing coverage and observed-time gates. This prevents slow
  sampling windows from qualifying as usable evidence of absent short closures.
  It does not repair missed blinks or establish accuracy above the gate. Legacy
  v1 recordings remain readable but are excluded from v2 personal references.
- Language-aware lexical heuristics preserve Rust lifetimes and JavaScript
  private fields; hash comments are limited to matching languages. These remain
  heuristics, with language-specific limitations.

Other repairs recover abandoned lock files/guards, cache structural regions for
large-file fading, preserve blank self-ratings as missing, isolate unreadable
upload entries and persist automatic uploads before handling a busy uploader.
Busy automatic uploads remain queued for explicit retry.

## Validation and follow-up

GitHub Actions runs version consistency, runtime checksums, both JavaScript test
suites, portable packaging/extracted-folder tests, Java/component tests, plugin
packaging/structure and JetBrains Plugin Verifier on pushes and pull requests.
The default verifier matrix is IntelliJ Community 2023.1 and 2025.2. A local IDE
build verifies that supplied IDE instead. Startup now implements `ProjectActivity`.
`VERSION` is the canonical release version; `scripts/sync-version.mjs` generates
standalone runtime/package metadata, and Gradle generates the Java version class.

Automated validation includes crash/torn-tail recovery, HTTP recording round trips,
long-session persistence, retry idempotency, report escaping, capability isolation,
clock consistency, brief tracking gaps, provenance, row geometry, text-offset
contracts, low-cadence blink quality and upload/lock recovery. The accompanying
validation record lists exact commands and results.

A browser smoke check exercised the mouse recording workflow. A separate browser
check initialized the bundled ONNX/EyeMU and MediaPipe/face-landmarker models
without opening a camera. Neither check validates live eye tracking, physical
multi-monitor behavior, physiological blink detection, native AI handoff or
research efficacy.

Opt-in raw-sample recording is deferred: it needs an explicit retention/privacy
contract and capture/storage design. A broad PSI-based region/identifier rewrite
is also deferred; it needs language coverage and performance tests. Existing IDE
API deprecation warnings outside the replaced startup/action dispatch remain
follow-up work. See [blink measurements and limits](BLINK_ACTIVITY.md) before
using the data for research claims.
