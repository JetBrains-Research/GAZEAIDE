import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

// Load the real module without running init(): no browser, webcam, models or network.
const source = await readFile(new URL('../../main/resources/gaze/web/uniteye-cv.js', import.meta.url), 'utf8');
const { UnitEyeWeb } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
function harness() {
  const saved = { setTimeout, clearTimeout, window: globalThis.window };
  const pending = new Map(), listeners = new Map(); let id = 0;
  globalThis.setTimeout = callback => { pending.set(++id, callback); return id; };
  globalThis.clearTimeout = timer => pending.delete(timer);
  globalThis.window = { addEventListener: (name, fn) => listeners.set(name, fn), removeEventListener: name => listeners.delete(name) };
  const tracker = Object.assign(Object.create(UnitEyeWeb.prototype), { mock: false, _running: false, _processFrame: async () => {} });
  function take() { const [key, callback] = pending.entries().next().value; pending.delete(key); return callback; }
  return { tracker, pending, listeners, take, restore() { Object.assign(globalThis, saved); } };
}
test('windowless sampling does not depend on animation frames or overlap inference', async () => {
  const h = harness();
  try {
    let release, calls = 0;
    h.tracker._processFrame = () => { calls++; return new Promise(resolve => { release = resolve; }); };
    h.tracker.start(); h.tracker.start(); assert.equal(h.pending.size, 1);
    const inference = h.take()(); assert.equal(calls, 1); assert.equal(h.pending.size, 0);
    release(); await inference; assert.equal(h.pending.size, 1);
    h.tracker.stop(); assert.equal(h.pending.size, 0);
  } finally { h.restore(); }
});
test('stopping during inference prevents rescheduling', async () => {
  const h = harness();
  try {
    let release; h.tracker._processFrame = () => new Promise(resolve => { release = resolve; });
    h.tracker.start(); const inference = h.take()(); h.tracker.stop(); release(); await inference;
    assert.equal(h.pending.size, 0);
  } finally { h.restore(); }
});
test('an old callback cannot run after stop and restart', async () => {
  const h = harness();
  try {
    let calls = 0; h.tracker._processFrame = async () => { calls++; };
    h.tracker.start(); const old = h.take(); h.tracker.stop(); h.tracker.start();
    await old(); assert.equal(calls, 0); assert.equal(h.pending.size, 1);
    await h.take()(); assert.equal(calls, 1); h.tracker.stop();
  } finally { h.restore(); }
});
test('mock sampling removes listeners and releases video tracks on stop', async () => {
  const h = harness();
  try {
    let samples = 0, stopped = 0;
    Object.assign(h.tracker, { mock: true, _mouse: { x: 0, y: 0 }, _emit: () => samples++, video: { srcObject: { getTracks: () => [{ stop: () => stopped++ }] } } });
    h.tracker.start(); assert.equal(h.listeners.size, 1); await h.take()(); assert.equal(samples, 1);
    h.tracker.stop(); assert.equal(h.listeners.size, 0); assert.equal(stopped, 1); assert.equal(h.pending.size, 0);
  } finally { h.restore(); }
});
test('failed inference invalidates the target immediately and continues sampling', async () => {
  const h=harness(),warn=console.warn;console.warn=()=>{};
  try {const emissions=[];h.tracker.onGaze=(...args)=>emissions.push(args);h.tracker._processFrame=async()=>{throw new Error('inference failed');};h.tracker.start();await h.take()();assert.deepEqual(emissions,[[null,null,false,false]]);assert.equal(h.pending.size,1);h.tracker.stop();}
  finally {console.warn=warn;h.restore();}
});
test('a failed stale inference cannot invalidate a newer run', async () => {
  const h=harness(),warn=console.warn;console.warn=()=>{};
  try {let reject;const emissions=[];h.tracker.onGaze=(...args)=>emissions.push(args);h.tracker._processFrame=()=>new Promise((_,no)=>reject=no);h.tracker.start();const old=h.take()();h.tracker.stop();h.tracker.start();reject(new Error('old frame'));await old;assert.equal(emissions.length,0);h.tracker.stop();}
  finally {console.warn=warn;h.restore();}
});
test('camera playback rejection releases acquired video tracks and rejects startup', async()=>{
  const ort='data:text/javascript,'+encodeURIComponent('export const env={wasm:{}};export const InferenceSession={create:async()=>({})}');
  const mp='data:text/javascript,'+encodeURIComponent('export const FilesetResolver={forVisionTasks:async()=>({})};export const FaceLandmarker={createFromOptions:async()=>({})}');
  const patched=source.replace(/const ORT_URL = '[^']+';/,`const ORT_URL = ${JSON.stringify(ort)};`).replace(/const MP_URL = '[^']+';/,`const MP_URL = ${JSON.stringify(mp)};`);
  const {UnitEyeWeb:PlaybackTracker}=await import('data:text/javascript;base64,'+Buffer.from(patched).toString('base64'));
  const originalDocument=globalThis.document,descriptor=Object.getOwnPropertyDescriptor(globalThis,'navigator');let stopped=0;
  globalThis.document={createElement:()=>({readyState:1,play:async()=>{throw new Error('playback denied');}})};
  Object.defineProperty(globalThis,'navigator',{configurable:true,value:{mediaDevices:{getUserMedia:async()=>({getTracks:()=>[{stop:()=>stopped++}]})}}});
  try {const t=Object.assign(Object.create(PlaybackTracker.prototype),{mock:false,onStatus:()=>{}});await assert.rejects(t.init(),/playback denied/);assert.equal(stopped,1);}
  finally {globalThis.document=originalDocument;if(descriptor)Object.defineProperty(globalThis,'navigator',descriptor);else delete globalThis.navigator;}
});

function eyeHarness() {
  const previous=globalThis.HTMLMediaElement;
  globalThis.HTMLMediaElement={HAVE_CURRENT_DATA:2};
  const eye=[],gaze=[],pending=[];let calls=0,face=true,ratios=[.25,.25];
  const t=Object.assign(Object.create(UnitEyeWeb.prototype),{
    _running:true,_runGeneration:1,_lastVideoTime:-1,blinkThreshold:.15,screenW:100,screenH:100,
    video:{readyState:2,currentTime:1,videoWidth:640,videoHeight:480},
    faceLandmarker:{detectForVideo:()=>({faceLandmarks:face?[Array.from({length:478},()=>({x:.5,y:.5,z:0}))]:[]})},
    _eyeRatios:()=>ratios,_eyeTensor:()=>new Float32Array(2),_headPose:()=>new Float32Array(4),
    C:{buildEyeMUFeatures:()=>[]},ort:{Tensor:class{}},
    session:{run:()=>{calls++;return new Promise((resolve,reject)=>pending.push({resolve,reject}));}},
    onEyeSample:s=>eye.push(s),onGaze:(...s)=>gaze.push(s),_emit:(...s)=>gaze.push(s)
  });
  return {t,eye,gaze,pending,get calls(){return calls;},setFace:v=>face=v,setRatios:v=>ratios=v,
    async frame(){t.video.currentTime++;await t._processFrame(t._runGeneration);},
    async resolve(){pending.shift().resolve({'dense_7':{data:[0,0,0,0]},'dense_8':{data:[.5,.5]}});await new Promise(r=>setImmediate(r));},
    restore(){globalThis.HTMLMediaElement=previous;}};
}
test('eyelid samples continue during slow gaze prediction, duplicate video frames are excluded and ONNX never overlaps',async()=>{
 const h=eyeHarness();try {
   await h.frame();await h.frame();assert.equal(h.eye.length,2);assert.equal(h.calls,1);assert.equal(h.gaze.length,0);
   await h.t._processFrame(1);assert.equal(h.eye.length,2);
   await h.resolve();assert.equal(h.gaze.length,1);await h.frame();assert.equal(h.calls,2);h.t._running=false;await h.resolve();assert.equal(h.gaze.length,1);
 } finally {h.restore();}
});
test('blink or face loss invalidates gaze immediately and pre-loss predictions cannot return after reacquisition',async()=>{
 for(const kind of ['blink','face']){const h=eyeHarness();try {
   await h.frame();if(kind==='blink')h.setRatios([.1,.1]);else h.setFace(false);await h.frame();if(kind==='blink')assert.equal(h.gaze[0][0],null);else assert.equal(h.gaze[0][4],false);
   h.setRatios([.25,.25]);h.setFace(true);await h.frame();await h.resolve();assert.equal(h.gaze.length,1);
   await h.frame();await h.resolve();assert.equal(h.gaze.length,2);
 }finally{h.restore();}}
});
test('failed gaze prediction does not fabricate a blink or stop eye observations',async()=>{
 const h=eyeHarness(),warn=console.warn;console.warn=()=>{};try{
   await h.frame();h.pending.shift().reject(new Error('synthetic inference failure'));await new Promise(r=>setImmediate(r));
   assert.equal(h.eye.length,1);assert.equal(h.gaze[0][0],null);await h.frame();assert.equal(h.eye.length,2);assert.equal(h.calls,2);h.t._running=false;await h.resolve();
 }finally{console.warn=warn;h.restore();}
});
