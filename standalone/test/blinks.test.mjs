import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {BlinkMonitor,blinkConfig,blinkReference,blinkIndicator,blinkContext,blinkCsv,validBlinkData} from '../lib/blinks.mjs';
import {buildAgentContext,composeAgentRequest,researchRows} from '../lib/agent-context.mjs';
import {reportHtml,reduced} from '../lib/research.mjs';
const config=blinkConfig({participant:'P01',group:'camera-a',reference:true,fatigue:1});
function monitor(startMs=1000,c=config,reference=blinkReference([],c,startMs)) {return new BlinkMonitor({startMs,config:c,reference});}
function stream(m,from,to,closed=t=>t%5000>=1000&&t%5000<1160,step=40) {for(let t=from;t<=to;t+=step){const n=closed(t)? .1:.25;m.sample(m.startMs+t,n,n,true);}}
const saved=(m,end)=>({id:'s-'+m.startMs,startMs:m.startMs,endMs:m.startMs+end,events:[],blink:m.snapshot(m.startMs+end)});
test('bilateral events preserve sample bounds and distinguish prolonged/short closures',()=>{
 const m=monitor();stream(m,0,60000);const d=m.snapshot(61000);
 assert.equal(d.events.length,12);assert.equal(d.windows[0].blinks,12);assert.equal(d.windows[0].observedMs,60000);
 assert.equal(d.windows[0].blinkRatePerMinute,12);assert.equal(d.windows[0].usable,true);
 assert.deepEqual(d.events[0],{startMs:2000,endMs:2160,kind:'BLINK',durationMs:160,lowerBoundMs:120,upperBoundMs:200,maxSamplingGapMs:40});
 const long=monitor();stream(long,0,3000,t=>t>=1000&&t<2000);assert.equal(long.events[0].kind,'PROLONGED_CLOSURE');assert.equal(long.snapshot(4000).windows[0].blinks,0);
 const short=monitor();stream(short,0,2000,t=>t===1000);assert.equal(short.events[0].kind,'SHORT_CLOSURE');
});
test('winks, face loss, pauses and sample gaps never complete a blink',()=>{
 for(const interrupt of ['face','gap','pause','wink']){
   const m=monitor();stream(m,0,1040);
   if(interrupt==='face')m.sample(2080,0,0,false);
   if(interrupt==='gap')m.sample(2400,.25,.25,true);
   if(interrupt==='pause')m.interrupt(2080,'paused');
   if(interrupt==='wink')m.sample(2080,.1,.25,true);
   stream(m,1480,2000,()=>false);
   assert.equal(m.events.filter(e=>e.kind==='BLINK').length,0,interrupt);
   assert.equal(m.events[0].kind,'INTERRUPTED',interrupt);
 }
 const m=monitor();for(let t=0;t<=1000;t+=40)m.sample(1000+t,.1,.25,true);assert.equal(m.events.length,0);assert.equal(m.snapshot(2000).summary.observedMs,0);
});
test('sparse sampling is unknown, duplicate/backwards timestamps have no effect and missing is distinct from zero',()=>{
 const sparse=monitor();stream(sparse,0,60000,()=>false,200);const d=sparse.snapshot(61000);assert.equal(d.summary.usable,false);assert.equal(d.summary.blinkRatePerMinute,null);assert.equal(d.indicator.score,null);
 const m=monitor();stream(m,0,60000,()=>false);const before=m.snapshot(61000);m.sample(61000,.1,.1);m.sample(60000,.1,.1);assert.deepEqual(m.snapshot(61000),before);assert.equal(before.summary.blinkRatePerMinute,0);
 assert.equal(m.snapshot(63000).indicator.state,'STALE');m.interrupt(61000,'paused');assert.equal(m.snapshot(61000).indicator.state,'STALE');
});
test('minute boundaries split observed time without double counting; long gaps are excluded',()=>{
 const m=monitor();stream(m,0,59960,()=>false);m.sample(61040,.25,.25);let d=m.snapshot(61040);
 assert.equal(d.windows[0].observedMs,60000);assert.equal(d.windows[1].observedMs,40);
 m.sample(181000,.25,.25);d=m.snapshot(181000);assert.equal(d.windows.reduce((a,w)=>a+w.observedMs,0),60040);
 assert.equal(d.windows.at(-1).gaps,1);
});
test('reference learns across saved rested records only, isolated by person/setup, deduplicated and frozen',()=>{
 const m=monitor();stream(m,0,180000);const record=saved(m,180000);const before=200000;
 const r=blinkReference([record,record],config,before);assert.equal(r.windows,3);assert.equal(r.blinks,36);assert.equal(r.ready,true);
 assert.equal(blinkReference([record],{...config,participant:'P02'},before).ready,false);
 assert.equal(blinkReference([record],{...config,group:'other'},before).ready,false);
 for(const b of [{...record,inProgress:true},{...record,endMs:before+1},{...record,blink:{...record.blink,config:{...config,reference:false}}},{...record,blink:{...record.blink,config:{...config,fatigue:8}}}])assert.equal(blinkReference([b],config,before).windows,0);
 const n=monitor(before,config,r);const snapshot=n.snapshot(before);record.blink.windows=[];assert.equal(snapshot.reference.windows,3);
 const w=m.snapshot(181000).summary;assert.equal(blinkIndicator(w,r).score,0);
 assert.equal(blinkIndicator({...w,medianBlinkMs:400,closedMs:6000,closedFraction:.1},r).state,'ELEVATED_CLOSURE');
 assert.equal(blinkIndicator({...w,blinks:100,blinkRatePerMinute:100},r).score,0,'rate alone has no assumed direction');
});
test('context uses earlier observations only, excludes personal identifiers and preserves opt-out/bounds',()=>{
 const m=monitor();stream(m,0,180000);const s=saved(m,180000);const early=blinkContext(s.blink,61000);
 assert.equal(early.window.endMs,61000);assert.equal(early.selfReport.fatigue,1);assert.ok(!JSON.stringify(early).includes('P01'));assert.equal(blinkContext(s.blink,1000).state,'INSUFFICIENT_OBSERVATIONS');
 const p=buildAgentContext({session:s,atMs:181000,maxChars:2000});assert.ok(JSON.stringify(p).length<=2000);assert.ok(p.blinkActivity);assert.equal(composeAgentRequest('q',p,false).text,'q');assert.ok(composeAgentRequest('q',p,true).contextAttached);assert.ok(researchRows(s).length);
});
test('blink-only records validate, export and retain quality without entering reduced remote upload',()=>{
 const m=monitor();stream(m,0,60000);const s=saved(m,60000);s.schemaVersion=3;s.durationMs=60000;s.configurationChanges=[];
 assert.equal(validBlinkData(s.blink),true);assert.equal(validBlinkData({...s.blink,indicator:null}),false);assert.equal(validBlinkData({...s.blink,events:Array(50001).fill({})}),false);
 assert.ok(blinkCsv(s.blink).includes('BLINK'));assert.ok(reportHtml(s).includes('Blink activity'));assert.ok(!JSON.stringify(reduced(s,{studyId:'study',participantId:'P1',taskId:'task'})).includes('closedFraction'));
});
test('JS and Java share a timestamped eye observation fixture',async()=>{
 const f=JSON.parse(await readFile(new URL('./blink-v1.json',import.meta.url)));const m=new BlinkMonitor(f.options);
 for(const run of f.runs)for(let t=run.from;t<=run.to;t+=run.step)m.sample(t,run.left,run.right,run.face);
 const d=m.snapshot(f.atMs);const normalized=JSON.parse(JSON.stringify(d));normalized.events=normalized.events.map(e=>({durationMs:null,lowerBoundMs:null,upperBoundMs:null,maxSamplingGapMs:null,reason:null,...e}));
 assert.deepEqual(normalized,f.expected);
});
test('authenticated reference endpoint persists blink-only data, isolates participants and returns no source snapshots',async()=>{
 const {start}=await import('../server.mjs');const fs=await import('node:fs/promises');const os=await import('node:os');const path=await import('node:path');
 const temp=await fs.mkdtemp(path.join(os.tmpdir(),'gazeaide-blink-test-'));let app;
 try {
   app=await start({project:temp,data:path.join(temp,'data'),port:0});const {url,token}=app.connection;
   const post=(route,b)=>fetch(url+'/api/'+route,{method:'POST',headers:{'x-gazeaide-key':token,'content-type':'application/json'},body:JSON.stringify(b)});
   const m=monitor();stream(m,0,180000);const s=saved(m,180000);Object.assign(s,{schemaVersion:3,configurationChanges:[],privateCode:'not-for-reference-response'});
   assert.equal((await post('session',s)).status,200);const r=await (await post('blink-reference',config)).json();assert.equal(r.ready,true);assert.equal(r.windows,3);assert.ok(!JSON.stringify(r).includes('not-for-reference-response'));
   assert.equal((await (await post('blink-reference',{...config,participant:'other'})).json()).ready,false);
   assert.equal((await post('session',{...s,blink:{...s.blink,windows:Array(1441).fill({})}})).status,400);
   assert.equal((await fetch(url+'/api/blink-reference',{method:'POST',body:'{}'})).status,403);
 } finally {await app?.close();await fs.rm(temp,{recursive:true,force:true});}
});
test('hysteresis rejects threshold noise, reports truncation, and safely quotes imported CSV text',()=>{
 const m=monitor();stream(m,0,960,()=>false);for(let t=1000;t<=1080;t+=40)m.sample(1000+t,.16,.16,true);stream(m,1120,2000,()=>false);assert.equal(m.events.length,0);
 m.sample(1000+1440*60000,.25,.25,true);assert.equal(m.snapshot(1000+1440*60000).truncated,true);
 assert.ok(blinkCsv({events:[{startMs:1,endMs:2,kind:'INTERRUPTED',reason:'=1+1'}]}).includes("'=1+1"));
});
