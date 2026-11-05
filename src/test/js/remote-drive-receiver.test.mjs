import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(new URL('../../main/resources/remote/google-drive-receiver.js', import.meta.url), 'utf8');
const fixture = JSON.parse(await readFile(new URL('../resources/remote-session-v1.json', import.meta.url), 'utf8'));
const token = 'a'.repeat(64);
function harness() {
  const files = new Map(); let lookups = 0, writes = 0, locks = 0;
  const properties = new Map([['STUDY_ID', 'study-1'], ['FOLDER_ID', 'private-folder'], ['PARTICIPANTS_JSON', JSON.stringify({P01: token})]]);
  const context = vm.createContext({
    PropertiesService: {getScriptProperties: () => ({getProperty: name => properties.get(name)})},
    DriveApp: {getFolderById: id => {
      assert.equal(id, 'private-folder'); lookups++;
      return {
        getId: () => id,
        getFilesByName: name => {
          let found = files.has(name);
          return {hasNext: () => found, next: () => {found = false; return {getBlob: () => ({getDataAsString: () => files.get(name)})};}};
        },
        createFile: (name, content, mime) => {assert.equal(mime, 'application/json'); writes++; files.set(name, content);},
      };
    }},
    LockService: {getScriptLock: () => ({tryLock: () => {locks++; return true;}, releaseLock: () => {locks--;}})},
    ContentService: {MimeType: {JSON: 'json'}, createTextOutput: text => ({setMimeType: () => JSON.parse(text)})},
  });
  vm.runInContext(source, context);
  function send(payload, participantToken = token, extra = {}) {
    const contents = JSON.stringify({token: participantToken, payload, ...extra});
    return context.doPost({postData: {contents, length: Buffer.byteLength(contents)}});
  }
  return {context, send, files, properties, get lookups() {return lookups;}, get writes() {return writes;}, get locks() {return locks;}};
}
test('receiver accepts the same reduced-schema fixture verified by Java', () => {
  const h = harness(); const result = h.send(fixture);
  assert.equal(result.ok, true); assert.equal(result.uploadId, fixture.uploadId); assert.equal(h.writes, 1); assert.equal(h.locks, 0);
  const saved = [...h.files.values()][0]; assert.deepEqual(JSON.parse(saved), fixture); assert.equal(saved.includes(token), false);
});
test('repeat delivery acknowledges original upload without duplicate or overwrite', () => {
  const h = harness(); assert.equal(h.send(fixture).ok, true); assert.equal(h.send(fixture).ok, true); assert.equal(h.writes, 1);
  const changed = structuredClone(fixture); changed.events[0].dwellMs++;
  assert.equal(h.send(changed).ok, false); assert.equal(h.writes, 1); assert.equal(h.locks, 0);
});
test('connection test authenticates and checks the private folder without writing sessions', () => {
  const h = harness(); const p = Object.fromEntries(['protocol', 'schemaVersion', 'uploadId', 'studyId', 'participantId'].map(k => [k, fixture[k]]));
  p.kind = 'connection_test'; assert.equal(h.send(p).ok, true); assert.equal(h.lookups, 1); assert.equal(h.writes, 0);
});
test('wrong or revoked participant credentials and wrong study never reach Drive', () => {
  const h = harness(); assert.equal(h.send(fixture, 'b'.repeat(64)).ok, false);
  const wrongStudy = structuredClone(fixture); wrongStudy.studyId = 'another-study'; assert.equal(h.send(wrongStudy).ok, false);
  const wrongParticipant = structuredClone(fixture); wrongParticipant.participantId = 'P02'; assert.equal(h.send(wrongParticipant).ok, false);
  h.properties.set('PARTICIPANTS_JSON', '{}'); assert.equal(h.send(fixture).ok, false);
  assert.equal(h.lookups, 0); assert.equal(h.writes, 0);
});
test('unknown fields anywhere in the schema are rejected before accessing Drive', () => {
  const modifications = [
    p => {p.sourceCode = 'private source';},
    p => {p.events[0].lineText = 'private source';},
    p => {p.events[0].filePath = '/Users/private/source';},
    p => {p.initialConfiguration.tracking.calibration.a = 1;},
    p => {p.initialConfiguration.tracking.personalResponse.createdAtMs = 123456789;},
    p => {p.configurationChanges[0].configuration.secret = 'private';},
    p => {p.initialConfiguration.features.secret = 'private';},
    p => {p.initialConfiguration.review.prompt = 'private source';},
  ];
  for (const change of modifications) {
    const h = harness(); const p = structuredClone(fixture); change(p);
    assert.equal(h.send(p).ok, false); assert.equal(h.lookups, 0);
  }
  const h = harness(); assert.equal(h.send(fixture, token, {unexpected: 'private'}).ok, false); assert.equal(h.lookups, 0);
});
test('malformed data, missing fields, unsafe IDs and oversize arrays are rejected', () => {
  const modifications = [
    p => {delete p.events[0].input;}, p => {p.events[0].input = 'EYE_TRACKING_ASSUMED';},
    p => {p.events[0].elapsedMs = -1;}, p => {p.uploadId = '../../escape';}, p => {p.schemaVersion = 2;},
    p => {p.events = Array(50001).fill(fixture.events[0]);}, p => {p.configurationChanges = Array(5001).fill(fixture.configurationChanges[0]);},
    p => {p.initialConfiguration.tracking.personalResponse.targetHitRate = null;},
    p => {p.initialConfiguration.scenario = 'private free text';}, p => {p.participantId = 'Real Person Name';},
  ];
  for (const change of modifications) {
    const h = harness(); const p = structuredClone(fixture); change(p);
    assert.equal(h.send(p).ok, false); assert.equal(h.lookups, 0);
  }
  const h = harness();
  assert.equal(h.context.doPost({postData: {contents: '{broken', length: 7}}).ok, false);
  assert.equal(h.context.doPost({postData: {contents: '{}', length: 8 * 1024 * 1024 + 1}}).ok, false);
  assert.equal(h.context.doGet().ok, false); assert.equal(h.lookups, 0);
});
test('exceptions return only a generic failure without private details or tokens', () => {
  const h = harness(); h.context.DriveApp.getFolderById = () => {throw new Error('PRIVATE ' + token);};
  const result = h.send(fixture); assert.deepEqual(result, {ok: false});
  assert.equal(JSON.stringify(result).includes(token), false);
});
