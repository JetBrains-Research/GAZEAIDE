import { csvCell } from "./core.mjs";
// Threshold-crossing observations, not clinical blinks or a cognitive-load model.
export const BLINK_POLICY = "webcam-blink-v1";
export const BLINK_LIMITS = Object.freeze({ windowMs: 60000, maxGapMs: 120, minObservedMs: 45000, minHz: 10, maxEvents: 50000, maxWindows: 1440 });
const median = a => a.length ? [...a].sort((a,b)=>a-b)[Math.floor(a.length/2)] : 0;
const mad = a => { const m=median(a);return median(a.map(v=>Math.abs(v-m))); };
const clip = s => String(s ?? "").trim().slice(0,120);
export function blinkConfig(c={}) {
  return { participant:clip(c.participant)||"local-user", group:clip(c.group)||"default-camera", reference:!!c.reference,
    fatigue:Number.isInteger(c.fatigue)&&c.fatigue>=0&&c.fatigue<=10?c.fatigue:null,
    effort:Number.isInteger(c.effort)&&c.effort>=0&&c.effort<=10?c.effort:null, suggestions:!!c.suggestions };
}
const same = (a,b) => a?.participant===b?.participant && a?.group===b?.group;
export const usableBlinkWindow = w => !!w && Number.isFinite(w.startMs) && Number.isFinite(w.endMs) && w.endMs>w.startMs && w.endMs-w.startMs<=60000 &&
  Number.isFinite(w.observedMs) && w.observedMs>=45000 && w.observedMs<=w.endMs-w.startMs && w.observedMs/(w.endMs-w.startMs)>=.75 &&
  Number.isFinite(w.closedMs) && w.closedMs>=0 && w.closedMs<=w.observedMs && Number.isFinite(w.samplingHz) && w.samplingHz>=10 &&
  Number.isInteger(w.blinks) && w.blinks>=0 && Number.isFinite(w.closedFraction) && Math.abs(w.closedFraction-w.closedMs/w.observedMs)<1e-8 &&
  (w.blinks===0 || (Number.isFinite(w.medianBlinkMs) && w.medianBlinkMs>=40 && w.medianBlinkMs<=700));
const displayWindow = w => w && ["observedMs","closedMs","frames","invalidSamples","gaps","blinks","prolongedClosures","coverage","samplingHz"].every(k=>Number.isFinite(w[k])&&w[k]>=0) &&
  ["blinkRatePerMinute","medianBlinkMs","closedFraction"].every(k=>w[k]===null || Number.isFinite(w[k])&&w[k]>=0);
export function validBlinkData(b) {
  return !!(b?.schemaVersion===1 && b.policy===BLINK_POLICY && b.inputKind==="WEBCAM" && b.config && typeof b.config.participant==="string" && b.config.participant.length<=120 && typeof b.config.group==="string" && b.config.group.length<=120 &&
    b.indicator && ["COLLECTING","STALE","LOW_QUALITY_OR_SHORT","NEED_PERSONAL_REFERENCE","WITHIN_REFERENCE","ELEVATED_CLOSURE"].includes(b.indicator.state) &&
    (b.indicator.score===null || Number.isInteger(b.indicator.score)&&b.indicator.score>=0&&b.indicator.score<=100) &&
    (b.summary===null || displayWindow(b.summary)) &&
    Array.isArray(b.events) && b.events.length<=50000 && Array.isArray(b.windows) && b.windows.length<=1440 &&
    Number.isFinite(b.startMs) && Number.isFinite(b.capturedAtMs) && b.capturedAtMs>=b.startMs &&
    b.windows.every(w=>displayWindow(w) && Number.isFinite(w.startMs) && Number.isFinite(w.endMs) && w.startMs>=b.startMs && w.endMs>=w.startMs && w.endMs<=b.capturedAtMs) &&
    b.events.every(e=>e && Number.isFinite(e.startMs) && Number.isFinite(e.endMs) && e.startMs>=b.startMs && e.endMs>=e.startMs && e.endMs<=b.capturedAtMs && ["BLINK","SHORT_CLOSURE","PROLONGED_CLOSURE","INTERRUPTED"].includes(e.kind)));
}
export function blinkReference(sessions,config,beforeMs) {
  const windows=[], ids=new Set(), seen=new Set();
  for(const s of sessions || []) {
    const b=s.blink;
    if(!b || b.truncated || b.policy!==BLINK_POLICY || b.inputKind!=="WEBCAM" || !same(b.config,config) || !b.config.reference ||
      (b.config.fatigue!=null && b.config.fatigue>2) || s.inProgress || !(s.endMs<beforeMs)) continue;
    const id=s.id||s.researchRecordingId||String(s.startMs);
    for(const w of b.windows || []) if(w && w.complete && w.endMs-w.startMs===60000 && w.usable && usableBlinkWindow(w) && w.endMs<=s.endMs && w.endMs<beforeMs && !seen.has(id+":"+w.startMs)) { windows.push({...w,sessionId:id}); seen.add(id+":"+w.startMs); }
  }
  windows.sort((a,b)=>a.endMs-b.endMs);
  const chosen=windows.slice(-240), durations=chosen.filter(w=>w.blinks>0).map(w=>w.medianBlinkMs), fractions=chosen.map(w=>w.closedFraction);
  chosen.forEach(w=>ids.add(w.sessionId));
  const blinks=chosen.reduce((n,w)=>n+w.blinks,0), observedMs=chosen.reduce((n,w)=>n+w.observedMs,0);
  return { policy:BLINK_POLICY, participant:config.participant, group:config.group, windows:chosen.length, sessions:ids.size, blinks, observedMs,
    ready:chosen.length>=3&&blinks>=10&&observedMs>=135000,
    medianBlinkMs:median(durations), durationMadMs:mad(durations), closedFraction:median(fractions), fractionMad:mad(fractions),
    learnedBeforeMs:beforeMs, scope:"Up to 240 usable complete windows from earlier explicitly rested reference recordings; frozen for this recording." };
}
export function blinkIndicator(window,reference,{stale=false}={}) {
  const note="Exploratory closure deviation, not a fatigue probability, cognitive-load estimate or diagnosis. Blink rate is reported but has no fixed fatigue direction.";
  let state="COLLECTING", score=null;
  if(stale) state="STALE";
  else if(!window?.usable || !usableBlinkWindow(window)) state="LOW_QUALITY_OR_SHORT";
  else if(!reference?.ready) state="NEED_PERSONAL_REFERENCE";
  else {
    const terms=[Math.max(0,(window.closedFraction-reference.closedFraction)/Math.max(.01,1.4826*reference.fractionMad))];
    if(window.blinks>0) terms.push(Math.max(0,(window.medianBlinkMs-reference.medianBlinkMs)/Math.max(40,1.4826*reference.durationMadMs)));
    score=Math.round(Math.min(100,25*terms.reduce((a,b)=>a+b,0)/terms.length));
    state=score>=50?"ELEVATED_CLOSURE":"WITHIN_REFERENCE";
  }
  return {state,score,note};
}
export class BlinkMonitor {
  constructor({startMs,config,reference}) {
    if(!Number.isFinite(startMs)) throw new Error("Invalid blink recording start.");
    this.startMs=startMs;this.config=blinkConfig(config);this.reference=reference;
    this.events=[];this.finished=[];this.bins=new Map();this.last=null;this.openSince=null;this.closure=null;
    this.lastSampleAtMs=null;this.lastValidAtMs=null;this.truncated=false;
  }
  bin(index) {
    if(!this.bins.has(index)) this.bins.set(index,{index,observedMs:0,closedMs:0,frames:0,invalidSamples:0,gaps:0,blinks:0,prolongedClosures:0,durations:[]});
    return this.bins.get(index);
  }
  event(e) { if(this.events.length<BLINK_LIMITS.maxEvents)this.events.push(e);else this.truncated=true; }
  interrupt(atMs,reason="paused_or_unfocused") {
    if(this.closure) this.event({startMs:this.closure.start,endMs:atMs,kind:"INTERRUPTED",durationMs:null,lowerBoundMs:null,upperBoundMs:null,reason});
    this.last=null;this.openSince=null;this.closure=null;
    if(reason!=="recording_stopped")this.lastValidAtMs=null;
  }
  sample(atMs,left,right,face=true) {
    if(!Number.isFinite(atMs)||atMs<this.startMs||(this.lastSampleAtMs!=null&&atMs<=this.lastSampleAtMs))return;
    const index=Math.floor((atMs-this.startMs)/60000);
    if(index>=BLINK_LIMITS.maxWindows){this.truncated=true;this.interrupt(atMs,"recording_limit");return;}
    const bin=this.bin(index);bin.frames++;
    const valid=face&&Number.isFinite(left)&&Number.isFinite(right)&&left>0&&right>0&&left<1&&right<1;
    this.lastSampleAtMs=atMs;
    if(!valid){bin.invalidSamples++;this.interrupt(atMs,"face_or_landmarks_unavailable");}
    else {
      // Bilateral thresholds with hysteresis; one closed eye is not a blink.
      const state=left<.15&&right<.15?"closed":left>=.18&&right>=.18?"open":(left<.15&&right>=.18)||(right<.15&&left>=.18)?"uncertain":this.last?.state||"uncertain";
      const dt=this.last?atMs-this.last.at:0;
      if(dt>BLINK_LIMITS.maxGapMs){bin.gaps++;this.interrupt(atMs,"sampling_gap");}
      if(state==="uncertain"){bin.invalidSamples++;this.interrupt(atMs,"asymmetric_or_uncertain_eyes");}
      else this.lastValidAtMs=atMs;
      if(this.last && state!=="uncertain" && this.last.state!=="uncertain") {
        let from=this.last.at;
        while(from<atMs){const i=Math.floor((from-this.startMs)/60000),to=Math.min(atMs,this.startMs+(i+1)*60000),b=this.bin(i);b.observedMs+=to-from;if(state==="closed"&&this.last.state==="closed")b.closedMs+=to-from;from=to;}
        if(state==="closed") {
          if(!this.closure&&this.last.state==="open"&&this.openSince!=null&&this.last.at-this.openSince>=60)
            this.closure={start:atMs,last:atMs,openBefore:this.last.at,samples:0,maxGap:dt};
          if(this.closure){this.closure.last=atMs;this.closure.samples++;this.closure.maxGap=Math.max(this.closure.maxGap,dt);}
          this.openSince=null;
        } else {
          if(this.closure) {
            const c=this.closure,duration=atMs-c.start,kind=duration>=40&&c.samples>=2?(duration<=700?"BLINK":"PROLONGED_CLOSURE"):"SHORT_CLOSURE";
            this.event({startMs:c.start,endMs:atMs,kind,durationMs:duration,lowerBoundMs:c.last-c.start,upperBoundMs:atMs-c.openBefore,maxSamplingGapMs:Math.max(c.maxGap,dt)});
            if(kind==="BLINK"){bin.blinks++;bin.durations.push(duration);}else if(kind==="PROLONGED_CLOSURE")bin.prolongedClosures++;
            this.closure=null;
          }
          if(this.openSince==null)this.openSince=atMs;
        }
      } else if(state==="open") this.openSince=atMs;
      this.last={at:atMs,state};
    }
    for(const [i,b] of this.bins) if(i<index) {this.finished.push(this.window(b,this.startMs+(i+1)*60000,true));this.bins.delete(i);}
  }
  window(b,endMs,complete) {
    const startMs=this.startMs+b.index*60000, duration=Math.max(1,endMs-startMs),coverage=b.observedMs/duration,hz=(b.frames-b.invalidSamples)*1000/duration;
    return {startMs,endMs,complete,observedMs:b.observedMs,closedMs:b.closedMs,frames:b.frames,invalidSamples:b.invalidSamples,gaps:b.gaps,blinks:b.blinks,prolongedClosures:b.prolongedClosures,
      blinkRatePerMinute:b.observedMs?b.blinks*60000/b.observedMs:null,medianBlinkMs:b.blinks?median(b.durations):null,closedFraction:b.observedMs?b.closedMs/b.observedMs:null,
      coverage,samplingHz:hz,usable:b.observedMs>=45000&&coverage>=.75&&hz>=10};
  }
  snapshot(atMs) {
    const end=Math.max(this.startMs,atMs),windows=[...this.finished,...[...this.bins.values()].map(b=>this.window(b,Math.min(end,this.startMs+(b.index+1)*60000),end>=this.startMs+(b.index+1)*60000))];
    const summary=windows.at(-1), candidate=summary?.usable?summary:[...windows].reverse().find(w=>w.complete);
    const indicator=blinkIndicator(candidate,this.reference,{stale:this.lastValidAtMs==null||end-this.lastValidAtMs>1000||!!candidate&&end-candidate.endMs>60000});
    return {schemaVersion:1,policy:BLINK_POLICY,inputKind:"WEBCAM",config:this.config,startMs:this.startMs,capturedAtMs:end,lastSampleAtMs:this.lastValidAtMs,
      events:[...this.events],windows,summary:candidate||summary||null,reference:this.reference,indicator,truncated:this.truncated,
      interpretation:"Bilateral EAR threshold crossings; durations are sample-bounded estimates. Closure fraction is not standardized PERCLOS. No video or raw eye/face samples retained."};
  }
}
export function blinkContext(data,atMs) {
  if(!data||data.policy!==BLINK_POLICY||data.inputKind!=="WEBCAM")return null;
  const reference=data.reference?.learnedBeforeMs<=atMs?data.reference:null;
  const w=[...(data.windows||[])].reverse().find(w=>w.endMs<=atMs&&atMs-w.endMs<=120000&&w.usable&&usableBlinkWindow(w));
  if(!w)return {policy:BLINK_POLICY,inputKind:"WEBCAM",state:"INSUFFICIENT_OBSERVATIONS",interpretation:"No usable blink window at this request time. Do not infer mental state."};
  return {policy:BLINK_POLICY,inputKind:"WEBCAM",window:w,ageMs:atMs-w.endMs,
    indicator:blinkIndicator(w,reference,{stale:atMs-w.endMs>60000 || data.capturedAtMs<=atMs && (data.lastSampleAtMs==null||atMs-data.lastSampleAtMs>1000)}),
    reference:{windows:reference?.windows||0,sessions:reference?.sessions||0,ready:!!reference?.ready},
    truncated:!!data.truncated,
    selfReport:{fatigue:data.config?.fatigue??null,effort:data.config?.effort??null,scale:"Optional self-ratings 0–10 at recording start; not inferred."}};
}
export function blinkCsv(data) {
  const columns=["startMs","endMs","kind","durationMs","lowerBoundMs","upperBoundMs","maxSamplingGapMs","reason"];
  const cell=csvCell;
  return columns.join(",")+"\n"+(data?.events||[]).map(e=>columns.map(k=>cell(e[k])).join(",")).join("\n")+"\n";
}
export function blinkStatus(data) {
  if(!data)return "Blink collection inactive. Start a recording with a real webcam and collection enabled.";
  const w=data.summary,rate=w?.blinkRatePerMinute;
  return `${data.indicator.state.replaceAll("_"," ")}${data.indicator.score!=null?` · closure deviation ${data.indicator.score}/100`:""}\n${rate!=null?rate.toFixed(1):"—"} blinks/min · ${((w?.observedMs||0)/1000).toFixed(1)} s observed · ${((w?.coverage||0)*100).toFixed(0)}% coverage · ${(w?.samplingHz||0).toFixed(1)} samples/s\nMedian blink: ${w?.medianBlinkMs??"—"} ms · threshold-closed: ${w?.closedFraction!=null?(100*w.closedFraction).toFixed(2):"—"}% · ${w?.prolongedClosures??0} prolonged closures\nPersonal reference: ${data.reference?.windows||0} windows from ${data.reference?.sessions||0} recordings. Not a cognitive-load estimate or diagnosis.${data.truncated?" Recording limit reached; retained data is truncated.":""}${data.config.suggestions&&data.indicator.state==="ELEVATED_CLOSURE"?" Optional suggestion: consider a break if you feel tired.":""}`;
}
