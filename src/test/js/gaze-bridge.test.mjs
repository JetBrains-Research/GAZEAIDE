import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
const html = await readFile(new URL('../../main/resources/gaze/web/bridge.html', import.meta.url), 'utf8');
const callback = html.match(/onGaze: (\(finalGaze, raw, blink, facePresent, features, atMonoMs\) => \{[\s\S]*?\n          \})/)[1];
test('bridge reports early script failures even before the native query is installed', () => {
  const listeners = new Map();
  const context = vm.createContext({ window: { addEventListener: (name, fn) => listeners.set(name, fn) } });
  vm.runInContext(html.match(/<script>([\s\S]*?)<\/script>/)[1], context);
  vm.runInContext("line('STATUS RUNTIME')", context);
  listeners.get('error')({ message: 'A script failed' });
  listeners.get('unhandledrejection')({ reason: new Error('Model rejected') });
  assert.deepEqual(Array.from(context.window.__implicitQueue), ['STATUS RUNTIME', 'ERR A script failed', 'ERR Model rejected']);
  const sent = []; context.window.implicitBridge = line => sent.push(line);
  vm.runInContext("line('STATUS CAMERA')", context);
  assert.deepEqual(sent, ['STATUS CAMERA']);
});
function sample(raw, blink = false, present = true) {
  const lines = [];
  const onGaze = vm.runInNewContext('(' + callback + ')', { line: text => lines.push(text), params: new URLSearchParams('screenX=-1920&screenY=50') });
  onGaze({ x: 900, y: 800 }, raw, blink, present, null, 123);
  return lines;
}
test('bridge emits genuinely raw coordinates with desktop origin, not browser-filtered values', () => {
  assert.deepEqual(sample({ x: 100.3, y: 200.6 }), ['OK -1820 251 123']);
});
test('blinks, lost face and nonfinite gaze cannot be routed or used for calibration', () => {
  assert.deepEqual(sample({ x: 1, y: 2 }, true), ['BAD']);
  assert.deepEqual(sample({ x: 1, y: 2 }, false, false), ['BAD']);
  assert.deepEqual(sample({ x: NaN, y: 2 }), ['BAD']);
  assert.deepEqual(sample({ x: 1, y: Infinity }), ['BAD']);
  assert.deepEqual(sample(null), ['BAD']);
});
