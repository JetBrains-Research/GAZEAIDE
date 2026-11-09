/**
 * GAZEAIDE Google Drive receiver template — experimental, NOT a deployed service.
 * Review with your institution and test with synthetic recordings before recruitment.
 * Deploy as a web app executing as the researcher, accessible without Google sign-in.
 * Requests are authorized by individual high-entropy participant upload tokens.
 * Never make the Drive folder public. Do not log request bodies, tokens or exception details.
 * Apps Script/Google account quotas and institutional policies apply.
 *
 * Script Properties (Project Settings):
 * STUDY_ID: a study code matching the plugin configuration
 * FOLDER_ID: the ID of a private, researcher-owned destination folder
 * PARTICIPANTS_JSON: {"P-unique-code":"a-unique-random-64-character-hex-token"}
 * Use a distinct token per participant; remove its entry to revoke uploads.
 */
function doPost(e) {
  try {
    if (!e || !e.postData || e.postData.length > 8 * 1024 * 1024) return response_({ok: false});
    const request = JSON.parse(e.postData.contents);
    object_(request, {token: token_, payload: objectValue_}, ['token', 'payload']);
    const p = request.payload;
    validatePayload_(p);
    const properties = PropertiesService.getScriptProperties();
    if (p.studyId !== properties.getProperty('STUDY_ID')) return response_({ok: false});
    const participants = JSON.parse(properties.getProperty('PARTICIPANTS_JSON') || '{}');
    if (!Object.prototype.hasOwnProperty.call(participants, p.participantId)
        || !token_(participants[p.participantId]) || !constantTimeEqual_(request.token, participants[p.participantId]))
      return response_({ok: false});
    const folder = DriveApp.getFolderById(properties.getProperty('FOLDER_ID'));
    // Test authenticates and checks folder lookup; it writes no participant recording.
    if (p.kind === 'connection_test') { folder.getId(); return response_({ok: true, uploadId: p.uploadId}); }
    const lock = LockService.getScriptLock();
    if (!lock.tryLock(5000)) return response_({ok: false});
    try {
      // UUID naming and a lock make retries idempotent; never overwrite another payload.
      const name = p.uploadId + '.json';
      const existing = folder.getFilesByName(name);
      const content = JSON.stringify(p);
      if (existing.hasNext()) {
        const file = existing.next();
        if (existing.hasNext() || file.getBlob().getDataAsString('UTF-8') !== content) return response_({ok: false});
      } else {
        folder.createFile(name, content, 'application/json');
      }
      return response_({ok: true, uploadId: p.uploadId});
    } finally { lock.releaseLock(); }
  } catch (ignored) { return response_({ok: false}); }
}
function doGet() { return response_({ok: false}); }
function response_(value) { return ContentService.createTextOutput(JSON.stringify(value)).setMimeType(ContentService.MimeType.JSON); }
function constantTimeEqual_(a, b) {
  let different = a.length ^ b.length;
  for (let i = 0; i < Math.max(a.length, b.length); i++) different |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  return different === 0;
}
function objectValue_(v) { return v !== null && typeof v === 'object' && !Array.isArray(v); }
function token_(v) { return typeof v === 'string' && /^[A-Za-z0-9_-]{32,256}$/.test(v); }
function code_(v) { return typeof v === 'string' && /^[A-Za-z0-9._-]{1,80}$/.test(v); }
function integer_(v) { return Number.isSafeInteger(v) && v >= 0; }
function number_(v) { return typeof v === 'number' && Number.isFinite(v) && v >= 0; }
function boolean_(v) { return typeof v === 'boolean'; }
function oneOf_(values) { return function(v) { return values.indexOf(v) !== -1; }; }
function object_(v, fields, required) {
  if (!objectValue_(v)) throw new Error('schema');
  Object.keys(v).forEach(function(key) {
    if (!Object.prototype.hasOwnProperty.call(fields, key) || !fields[key](v[key])) throw new Error('schema');
  });
  (required || []).forEach(function(key) { if (!Object.prototype.hasOwnProperty.call(v, key)) throw new Error('schema'); });
  return true;
}
function configuration_(v) {
  const feature = function(f) { return object_(f, {
    focus: boolean_, fadeViewed: boolean_, relationships: boolean_, historyContext: boolean_, explanations: boolean_,
    stableMs: integer_, fadeAfterMs: integer_, explainAfterMs: integer_
  }); };
  const calibration = function(c) { return object_(c, {
    version: integer_, displayWidth: integer_, displayHeight: integer_, medianErrorPx: number_, p90ErrorPx: number_,
    medianVerticalPx: number_, p90VerticalPx: number_, jitter90Px: number_, samples: integer_, targets: integer_
  }); };
  const personal = function(p) { return object_(p, {
    version: integer_, active: boolean_, response: oneOf_(['RESPONSIVE', 'BALANCED', 'EXTRA_STEADY', 'STEADY', 'UNKNOWN']),
    p90VerticalPx: number_, jitter90Px: number_, targetHitRate: number_, acquisitionMs: number_,
    standardHitRate: number_, standardAcquisitionMs: number_, rowHeight: integer_, samples: integer_, targets: integer_
  }); };
  const tracking = function(t) { return object_(t, {
    showCursor: boolean_, response: oneOf_(['RESPONSIVE', 'BALANCED', 'EXTRA_STEADY', 'STEADY', 'BYPASSED', 'UNKNOWN']),
    smoothingMs: integer_, lineHoldMs: integer_, pipelineVersion: oneOf_(['raw-eyemu-median3-time-lowpass-line-hold-v2', 'mouse-direct-v1', 'UNKNOWN']),
    calibration: calibration, personalResponse: personal
  }); };
  const review = function(r) { return object_(r, {
    purpose: oneOf_(['EXPLAIN', 'COMPARE', 'REVIEW', 'UNKNOWN']), quietOffers: boolean_, cooldownMs: integer_
  }); };
  return object_(v, {
    input: oneOf_(['WEBCAM', 'MOUSE_PROXY', 'MOUSE_SIMULATION', 'UNKNOWN']),
    scenario: oneOf_(['PERSONALIZATION', 'BLINK_BASELINE', 'FOCUS', 'COVERAGE', 'FADE', 'NAMES', 'RELATIONSHIPS', 'WALKTHROUGH', 'OBSERVE', 'CUSTOM', 'UNKNOWN']),
    calibrated: boolean_, features: feature, tracking: tracking, review: review
  });
}
function event_(v) {
  return object_(v, {
    elapsedMs: integer_, fileId: function(s) { return typeof s === 'string' && /^f[1-9][0-9]*$/.test(s); },
    line: integer_, column: integer_, dwellMs: integer_, input: oneOf_(['WEBCAM', 'MOUSE_PROXY', 'MOUSE_SIMULATION']),
    legacyDwellTrigger: boolean_, regionId: function(s) { return typeof s === 'string' && /^r[1-9][0-9]*$/.test(s); },
    regionFirstLine: integer_, regionLastLine: integer_
  }, ['elapsedMs', 'fileId', 'line', 'column', 'dwellMs', 'input', 'legacyDwellTrigger']);
}
function validatePayload_(p) {
  const common = {
    protocol: oneOf_(['gazeaide-upload-v1']), schemaVersion: oneOf_([1]),
    uploadId: function(s) { return typeof s === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(s); },
    kind: oneOf_(['connection_test', 'session']), studyId: code_, participantId: code_,
    // Optional edition fields distinguish standalone sessions while accepting all legacy plugin payloads.
    edition: oneOf_(['jetbrains', 'standalone']),
    editionVersion: function(s) { return typeof s === 'string' && /^[0-9]+\.[0-9]+\.[0-9]+$/.test(s); }
  };
  const required = ['protocol', 'schemaVersion', 'uploadId', 'kind', 'studyId', 'participantId'];
  if (objectValue_(p) && p.kind === 'session') {
    common.pluginVersion = function(s) { return typeof s === 'string' && /^[0-9]+\.[0-9]+\.[0-9]+$/.test(s); };
    common.taskCode = function(s) { return s === '' || code_(s); };
    common.durationMs = integer_;
    common.initialConfiguration = configuration_;
    common.configurationChanges = function(changes) {
      return Array.isArray(changes) && changes.length <= 5000 && changes.every(function(c) {
        return object_(c, {elapsedMs: integer_, paused: boolean_, configuration: configuration_}, ['elapsedMs', 'paused', 'configuration']);
      });
    };
    common.events = function(events) { return Array.isArray(events) && events.length <= 50000 && events.every(event_); };
    required.push('pluginVersion', 'taskCode', 'durationMs', 'configurationChanges', 'events');
  }
  return object_(p, common, required);
}
