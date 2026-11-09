package io.github.gazehighlighter;

import java.util.*;

/** Local, opt-in bilateral eye-closure observations. Matched to standalone/lib/blinks.mjs. */
public final class GazeBlinkMonitor {
    public static final String POLICY = "webcam-blink-v1";
    public record Config(String participant, String group, boolean reference, Integer fatigue, Integer effort, boolean suggestions) {
        public Config {
            participant=label(participant,"local-user"); group=label(group,"default-camera");
            fatigue=rating(fatigue); effort=rating(effort);
        }
    }
    public record Event(long startMs,long endMs,String kind,Long durationMs,Long lowerBoundMs,Long upperBoundMs,Long maxSamplingGapMs,String reason) {}
    public record Window(long startMs,long endMs,boolean complete,long observedMs,long closedMs,int frames,int invalidSamples,int gaps,int blinks,int prolongedClosures,
                         Double blinkRatePerMinute,Double medianBlinkMs,Double closedFraction,double coverage,double samplingHz,boolean usable) {}
    public record Reference(String policy,String participant,String group,int windows,int sessions,int blinks,long observedMs,boolean ready,
                            double medianBlinkMs,double durationMadMs,double closedFraction,double fractionMad,long learnedBeforeMs,String scope) {}
    public record Indicator(String state,Integer score,String note) {}
    public record Data(int schemaVersion,String policy,String inputKind,Config config,long startMs,long capturedAtMs,Long lastSampleAtMs,
                       List<Event> events,List<Window> windows,Window summary,Reference reference,Indicator indicator,boolean truncated,String interpretation) {}
    private static String label(String s,String fallback) { s=s==null?"":s.trim();return s.isEmpty()?fallback:s.substring(0,Math.min(120,s.length())); }
    private static Integer rating(Integer n) { return n!=null&&n>=0&&n<=10?n:null; }
    private static double median(List<Double> values) {if(values.isEmpty())return 0;var a=values.stream().sorted().toList();return a.get(a.size()/2);}
    private static double mad(List<Double> values) {double m=median(values);return median(values.stream().map(v->Math.abs(v-m)).toList());}
    static boolean usable(Window w) {
        return w!=null&&w.endMs>w.startMs&&w.endMs-w.startMs<=60000&&w.observedMs>=45000&&w.observedMs<=w.endMs-w.startMs&&(double)w.observedMs/(w.endMs-w.startMs)>=.75
            &&w.closedMs>=0&&w.closedMs<=w.observedMs&&Double.isFinite(w.samplingHz)&&w.samplingHz>=10&&w.blinks>=0&&w.closedFraction!=null&&Double.isFinite(w.closedFraction)
            &&Math.abs(w.closedFraction-(double)w.closedMs/w.observedMs)<1e-8&&(w.blinks==0||(w.medianBlinkMs!=null&&Double.isFinite(w.medianBlinkMs)&&w.medianBlinkMs>=40&&w.medianBlinkMs<=700));
    }
    public static Reference reference(List<GazeSession> sessions,Config config,long beforeMs) {
        record Entry(Window window,String session) {}
        List<Entry> all=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(var s:sessions) {
            var b=s.blink;
            if(b==null||b.truncated||!POLICY.equals(b.policy)||!"WEBCAM".equals(b.inputKind)||b.config==null||!config.participant.equals(b.config.participant)||!config.group.equals(b.config.group)
                    ||!b.config.reference||(b.config.fatigue!=null&&b.config.fatigue>2)||s.endMs>=beforeMs||b.windows==null)continue;
            for(var w:b.windows)if(w!=null&&w.complete&&w.endMs-w.startMs==60000&&w.usable&&usable(w)&&w.endMs<=s.endMs&&w.endMs<beforeMs&&seen.add(s.researchRecordingId+":"+w.startMs))all.add(new Entry(w,s.researchRecordingId));
        }
        all.sort(Comparator.comparingLong(e->e.window.endMs));
        var chosen=all.subList(Math.max(0,all.size()-240),all.size());
        List<Double> durations=new ArrayList<>(),fractions=new ArrayList<>();Set<String> ids=new HashSet<>();int blinks=0;long observed=0;
        for(var e:chosen){var w=e.window;ids.add(e.session);blinks+=w.blinks;observed+=w.observedMs;if(w.blinks>0&&w.medianBlinkMs!=null)durations.add(w.medianBlinkMs);fractions.add(w.closedFraction);}
        return new Reference(POLICY,config.participant,config.group,chosen.size(),ids.size(),blinks,observed,chosen.size()>=3&&blinks>=10&&observed>=135000,
                median(durations),mad(durations),median(fractions),mad(fractions),beforeMs,"Up to 240 usable complete windows from earlier explicitly rested reference recordings; frozen for this recording.");
    }
    public static Indicator indicator(Window w,Reference r,boolean stale) {
        String note="Exploratory closure deviation, not a fatigue probability, cognitive-load estimate or diagnosis. Blink rate is reported but has no fixed fatigue direction.";
        String state;Integer score=null;
        if(stale)state="STALE";
        else if(w==null||!w.usable||!usable(w))state="LOW_QUALITY_OR_SHORT";
        else if(r==null||!r.ready)state="NEED_PERSONAL_REFERENCE";
        else {
            double total=Math.max(0,(w.closedFraction-r.closedFraction)/Math.max(.01,1.4826*r.fractionMad));int n=1;
            if(w.blinks>0&&w.medianBlinkMs!=null){total+=Math.max(0,(w.medianBlinkMs-r.medianBlinkMs)/Math.max(40,1.4826*r.durationMadMs));n++;}
            score=(int)Math.round(Math.min(100,25*total/n));state=score>=50?"ELEVATED_CLOSURE":"WITHIN_REFERENCE";
        }
        return new Indicator(state,score,note);
    }
    private static final class Bin {
        int index,frames,invalid,gaps,blinks,prolonged;long observed,closed;List<Double> durations=new ArrayList<>();
        Bin(int i){index=i;}
    }
    private final long start;private final Config config;private final Reference reference;
    private final Map<Integer,Bin> bins=new TreeMap<>();private final List<Window> finished=new ArrayList<>();private final List<Event> events=new ArrayList<>();
    private Long lastSample,lastValid,lastAt,openSince,closureStart,closureLast,openBefore;private String lastState;private int closureSamples;private long closureGap;private boolean truncated;
    public GazeBlinkMonitor(long startMs,Config config,Reference reference) {this.start=startMs;this.config=config;this.reference=reference;}
    private Bin bin(int i){return bins.computeIfAbsent(i,Bin::new);}
    private void event(Event e){if(events.size()<50000)events.add(e);else truncated=true;}
    public void interrupt(long at,String reason) {
        if(closureStart!=null)event(new Event(closureStart,at,"INTERRUPTED",null,null,null,null,reason));
        lastAt=null;lastState=null;openSince=null;closureStart=null;closureLast=null;openBefore=null;closureSamples=0;
        if(!"recording_stopped".equals(reason))lastValid=null;
    }
    public void sample(long at,double left,double right,boolean face) {
        if(at<start||(lastSample!=null&&at<=lastSample))return;
        int index=(int)((at-start)/60000);
        if(index>=1440){truncated=true;interrupt(at,"recording_limit");return;}
        Bin b=bin(index);b.frames++;lastSample=at;
        boolean valid=face&&Double.isFinite(left)&&Double.isFinite(right)&&left>0&&right>0&&left<1&&right<1;
        if(!valid){b.invalid++;interrupt(at,"face_or_landmarks_unavailable");}
        else {
            String state=left<.15&&right<.15?"closed":left>=.18&&right>=.18?"open":(left<.15&&right>=.18)||(right<.15&&left>=.18)?"uncertain":lastState==null?"uncertain":lastState;
            long dt=lastAt==null?0:at-lastAt;
            if(dt>120){b.gaps++;interrupt(at,"sampling_gap");}
            if(state.equals("uncertain")){b.invalid++;interrupt(at,"asymmetric_or_uncertain_eyes");}else lastValid=at;
            if(lastAt!=null&&!state.equals("uncertain")&&!lastState.equals("uncertain")) {
                long from=lastAt;
                while(from<at){int i=(int)((from-start)/60000);long to=Math.min(at,start+(i+1L)*60000);Bin a=bin(i);a.observed+=to-from;if(state.equals("closed")&&lastState.equals("closed"))a.closed+=to-from;from=to;}
                if(state.equals("closed")) {
                    if(closureStart==null&&lastState.equals("open")&&openSince!=null&&lastAt-openSince>=60){closureStart=at;closureLast=at;openBefore=lastAt;closureSamples=0;closureGap=dt;}
                    if(closureStart!=null){closureLast=at;closureSamples++;closureGap=Math.max(closureGap,dt);}openSince=null;
                } else {
                    if(closureStart!=null){long duration=at-closureStart;String kind=duration>=40&&closureSamples>=2?(duration<=700?"BLINK":"PROLONGED_CLOSURE"):"SHORT_CLOSURE";
                        event(new Event(closureStart,at,kind,duration,closureLast-closureStart,at-openBefore,Math.max(closureGap,dt),null));
                        if(kind.equals("BLINK")){b.blinks++;b.durations.add((double)duration);}else if(kind.equals("PROLONGED_CLOSURE"))b.prolonged++;
                        closureStart=null;closureLast=null;openBefore=null;
                    }if(openSince==null)openSince=at;
                }
            }else if(state.equals("open"))openSince=at;
            lastAt=at;lastState=state;
        }
        for(int i:new ArrayList<>(bins.keySet()))if(i<index){finished.add(window(bins.remove(i),start+(i+1L)*60000,true));}
    }
    private Window window(Bin b,long end,boolean complete) {
        long from=start+b.index*60000L,duration=Math.max(1,end-from);double coverage=(double)b.observed/duration,hz=(b.frames-b.invalid)*1000.0/duration;
        return new Window(from,end,complete,b.observed,b.closed,b.frames,b.invalid,b.gaps,b.blinks,b.prolonged,b.observed>0?b.blinks*60000.0/b.observed:null,
                b.blinks>0?median(b.durations):null,b.observed>0?(double)b.closed/b.observed:null,coverage,hz,b.observed>=45000&&coverage>=.75&&hz>=10);
    }
    public Data snapshot(long at) {
        long end=Math.max(start,at);List<Window> windows=new ArrayList<>(finished);
        for(var b:bins.values())windows.add(window(b,Math.min(end,start+(b.index+1L)*60000),end>=start+(b.index+1L)*60000));
        Window summary=windows.isEmpty()?null:windows.get(windows.size()-1),candidate=summary!=null&&summary.usable?summary:null;
        if(candidate==null)for(var w:windows)if(w.complete)candidate=w;
        return new Data(1,POLICY,"WEBCAM",config,start,end,lastValid,List.copyOf(events),List.copyOf(windows),candidate==null?summary:candidate,reference,
                indicator(candidate,reference,lastValid==null||end-lastValid>1000||candidate!=null&&end-candidate.endMs>60000),truncated,
                "Bilateral EAR threshold crossings; durations are sample-bounded estimates. Closure fraction is not standardized PERCLOS. No video or raw eye/face samples retained.");
    }
    public static Map<String,Object> context(Data data,long at) {
        if(data==null||!POLICY.equals(data.policy)||!"WEBCAM".equals(data.inputKind))return null;
        Reference ref=data.reference!=null&&data.reference.learnedBeforeMs<=at?data.reference:null;
        Window selected=null;
        for(var w:data.windows)if(w.endMs<=at&&at-w.endMs<=120000&&w.usable&&usable(w)&&(selected==null||selected.endMs<w.endMs))selected=w;
        Map<String,Object> result=new LinkedHashMap<>();result.put("policy",POLICY);result.put("inputKind","WEBCAM");
        if(selected==null){result.put("state","INSUFFICIENT_OBSERVATIONS");result.put("interpretation","No usable blink window at this request time. Do not infer mental state.");return result;}
        result.put("window",selected);result.put("ageMs",at-selected.endMs);
        result.put("indicator",indicator(selected,ref,at-selected.endMs>60000||data.capturedAtMs<=at&&(data.lastSampleAtMs==null||at-data.lastSampleAtMs>1000)));
        result.put("reference",Map.of("windows",ref==null?0:ref.windows,"sessions",ref==null?0:ref.sessions,"ready",ref!=null&&ref.ready));
        result.put("truncated",data.truncated);
        Map<String,Object> ratings=new LinkedHashMap<>();ratings.put("fatigue",data.config.fatigue);ratings.put("effort",data.config.effort);ratings.put("scale","Optional self-ratings 0–10 at recording start; not inferred.");result.put("selfReport",ratings);return result;
    }
    public static String status(Data d) {
        if(d==null)return "Blink collection inactive. Start a recording with a real webcam and collection enabled.";
        var w=d.summary;return String.format(Locale.ROOT,"%s%s%n%s blinks/min · %.1f s observed · %.0f%% coverage · %.1f samples/s%nMedian blink: %s ms · threshold-closed: %s%% · %d prolonged closures%nPersonal reference: %d windows from %d recordings. Not a cognitive-load estimate or diagnosis.%s",
                d.indicator.state.replace('_',' '),d.indicator.score==null?"":" · closure deviation "+d.indicator.score+"/100",w==null||w.blinkRatePerMinute==null?"—":String.format(Locale.ROOT,"%.1f",w.blinkRatePerMinute),w==null?0:w.observedMs/1000.0,w==null?0:100*w.coverage,w==null?0:w.samplingHz,w==null||w.medianBlinkMs==null?"—":String.format(Locale.ROOT,"%.0f",w.medianBlinkMs),w==null||w.closedFraction==null?"—":String.format(Locale.ROOT,"%.2f",100*w.closedFraction),w==null?0:w.prolongedClosures,
                d.reference==null?0:d.reference.windows,d.reference==null?0:d.reference.sessions,(d.truncated?" Recording limit reached; retained data is truncated.":"")+(d.config.suggestions&&d.indicator.state.equals("ELEVATED_CLOSURE")?" Optional suggestion: consider a break if you feel tired.":""));
    }
}
