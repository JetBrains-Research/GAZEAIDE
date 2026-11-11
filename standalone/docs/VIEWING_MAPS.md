# Gaze-derived viewing maps

## Open a map

In IntelliJ, stop a recording or open a saved one, then choose **Viewing map…** in
Session results, or **Tools → Implicit Gaze → Viewing Map…**. The plugin writes a
local HTML file in the IDE system folder and opens it in your browser. The same
map is included in **Export recording…** HTML reports. No server, camera, AI
account or internet connection is needed to inspect an existing recording.

In the standalone companion, record a session or select/import one in **Results**,
then open the **Viewing map** tab. Exported HTML reports include the same map.
Both editions use `standalone/lib/viewing-map.mjs`; Gradle bundles that component
into the plugin rather than maintaining a second implementation.

## Read and filter a map

- **Attributed seconds / Visit count** changes the colour measure. Every row also
  shows both values. One common colour scale applies across the filtered map,
  including pages, rather than normalizing each file separately.
- **Lines / Recorded regions** switches granularity. A region receives each visit
  assigned to it once. Nested regions are distinct; exposure is not added to all
  ancestors. These are captured lexical/display regions, not inferred semantic
  attention or physiological fixations.
- **Input source** filters webcam, mouse proxy or mouse simulation. Even the
  all-sources view keeps sources and document versions in separate groups.
  Missing legacy versions remain labelled unknown.
- **From / To** selects an interval in seconds since recording start. Click
  **Apply interval**. Exposure is clipped to the overlapping part of each visit;
  visit count includes each visit with positive overlap once. A brief visit is
  included even below the assistant-context eligibility threshold of 200 ms.
- White captured lines mean **no recorded visit in the selected interval**.
  Stripes mean **missing captured content**; blank source lines are preserved.
  Missing ranges and truncated snapshots are labelled rather than filled with
  current source. No recorded visit does not mean unread.

Text is reconstructed from captured regions across the recording, including
regions captured outside the selected interval. Only exposure and counts refer
to that interval. Saved snapshots take precedence over current files; opening a
map never reads or sends current editor/disk contents.

The setup panel shows recorded calibration error/jitter when available, with
configuration intervals and check timestamps. Missing measurements remain
unknown. These are setup checks, not demonstrated accuracy during natural
reading. A viewing map summarizes attributed exposure and does not establish
comprehension, intent, completed review or tracker accuracy.

## Export

**SVG / PNG** export the displayed page (up to 120 entries) with source/version
labels, interval, scale, units and a setup summary. PNG is rendered locally from
the SVG, without external assets. Region images show a label/first-line summary;
the full captured text is retained in the values. Pagination bounds image size
and DOM work on large recordings.

**Values JSON / CSV** export every entry under the current filters, including
file/version/source, line bounds, capture state, attributed milliseconds and
overlapping visit count. JSON also includes all applicable recorded quality
segments, interpretation and aggregation policy. CSV text is protected against
spreadsheet formula execution. Captured region text is stored once in portable
HTML map data; repeated visits reference that snapshot.

Standalone trace exclusions apply to exported HTML reports and their maps; the
live map and original JSON recording retain the original visits. Map controls
never modify the recording. Exported files and the plugin's local map HTML can
contain sensitive code and conversations; handle them like the original records.
