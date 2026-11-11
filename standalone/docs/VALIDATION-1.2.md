# GAZEAIDE 1.2.0 validation

Checked on macOS on 11 November 2026 with Node.js 24.13.1 and the installed
IntelliJ JBR. This update adds visualization and export; it does not change the
webcam estimator, calibration, interaction filters or assistant context threshold.

- **148 Java/component tests:** pass, including the bundled map resource,
  compact snapshot references and escaping of hostile source text. The upload
  golden-fixture expectation now reads the canonical generated version.
- **100 JavaScript tests:** pass (80 standalone, 20 plugin-side). Map cases cover
  clipped intervals, partial/boundary visits, sub-200 ms visits, unsorted legacy
  data, source/version isolation, missing versus blank/unvisited text, nested
  regions, calibration configuration intervals, shared snapshots, hostile text,
  CSV formula escaping, bounded SVG pages and trace exclusions.
- **Plugin packaging/compatibility:** `test buildPlugin verifyPluginStructure
  verifyPlugin` passes with the cached 2023.1 SDK. Plugin Verifier reports
  compatible with IntelliJ Community 2023.1 and 2025.2. Existing deprecation and
  scheduled-removal warnings remain.
- **Portable package:** version/asset checks pass; all 80 standalone tests pass
  from a fresh extraction of `gazeaide-standalone-1.2.0.zip`. Manifest sizes/hashes
  and the shared component inside the plugin archive are checked against source.
- **Browser checks:** an isolated library with synthetic records displays the
  dedicated full-width companion map. Interval/source filters, region mode,
  visit-count mode and recorded calibration summaries work. HTML produced by the
  Java map exporter renders in a browser. PNG and SVG downloads were inspected;
  the companion and plugin-map pages logged no warnings/errors in those checks.
- **Large synthetic fixture:** 100,128 visits sharing a 6.6 KB block retain
  100,128 visits and 10,012,800 ms exposure with one snapshot in map data. This
  checks aggregation and compact transport, not tracking performance.

No real camera, participant measurements, live IDE menu interaction or assistant
request was used for these checks. Displayed calibration numbers in synthetic
browser fixtures are test data, not measured webcam accuracy. The native plugin
entry points are compiled and compatibility-verified; camera/physical-device
accuracy and usability remain separate evaluations.

See [viewing maps](VIEWING_MAPS.md) for workflow, aggregation policy, image-page
scope, data exports and interpretation. Build steps are in
[BUILD_AND_TEST.md](BUILD_AND_TEST.md). Existing published 1.0.0/1.1.0 release
assets are preserved.
