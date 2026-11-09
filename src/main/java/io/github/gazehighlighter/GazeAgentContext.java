package io.github.gazehighlighter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.*;

/** Versioned, bounded context shared with the standalone edition. Opt-in blink observations remain explicitly uncertain. */
public final class GazeAgentContext {
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    public static final String POLICY = "recent-exposure-v1";
    private GazeAgentContext() {}
    private static final class Candidate {
        ViewedRegion region; String origin; long dwell, at; int visits, priority;
        Candidate(ViewedRegion r, String origin, long at, long dwell, int priority) { this.region=r; this.origin=origin; this.at=at; this.dwell=dwell; this.priority=priority; visits=origin.equals("viewing")?1:0; }
    }
    private static void add(Map<String,Candidate> candidates, ViewedRegion r, String origin, long at, long dwell, int priority) {
        if(r==null||r.text()==null||r.text().isBlank()) return;
        Candidate previous=candidates.get(r.key());
        if(previous==null)candidates.put(r.key(),new Candidate(r,origin,at,dwell,priority));
        else {if(priority>previous.priority)previous.origin=origin;previous.at=Math.max(previous.at,at);previous.dwell+=dwell;previous.visits+=origin.equals("viewing")?1:0;previous.priority=Math.max(previous.priority,priority);}
    }
    public static Map<String,Object> build(GazeSession session, ViewedRegion target, ViewedRegion viewport,
                                           GazeSessionMetadata.InitialConfiguration config, long atMs) {
        Map<String,Candidate> grouped=new LinkedHashMap<>(); int visits=0;
        if(session!=null) {
            // Apply the bound inside the requested window, including historical exports.
            var eligible=session.events.stream().filter(e->e.timestampMs<=atMs&&e.timestampMs>=atMs-600000&&e.region!=null).toList();
            for(GazeEvent e:eligible.subList(Math.max(0,eligible.size()-5000),eligible.size())) {
            visits++;var r=e.region;add(grouped,new ViewedRegion(r.filePath(),r.firstLine(),r.lastLine(),r.label(),r.text(),r.documentStamp(),e.inputLabel()),"viewing",e.timestampMs,Math.min(Math.max(0,e.dwellMs),e.timestampMs-(atMs-600000)),0);
            }
        }
        add(grouped,target,"selectedTarget",atMs,0,2);add(grouped,viewport,"visibleViewport",atMs,0,1);
        Map<String,Object> packet=new LinkedHashMap<>();
        packet.put("schemaVersion",1);packet.put("recordType","agent-context");packet.put("policy",POLICY);packet.put("edition","intellij");
        packet.put("recordingId",session==null?null:session.researchRecordingId==null?"intellij-"+session.startMs:session.researchRecordingId);packet.put("createdAtMs",atMs);packet.put("windowStartMs",atMs-600000);packet.put("windowEndMs",atMs);
        packet.put("bounds",Map.of("lookbackMs",600000,"maxRegions",8,"maxChars",12000,"visitsConsidered",visits,"scope","most recent 5000 eligible visits in this time window; selected target and visible viewport"));
        Map<String,Object> quality=new LinkedHashMap<>();
        quality.put("interpretation","Estimated viewing exposure and viewport visibility do not establish intent, confusion, comprehension, correctness or completed review.");
        quality.put("configurationScope","Configuration at request time; historical visits may use earlier configurations. See the linked recording timeline.");
        quality.put("calibrated",config!=null&&config.calibrated());quality.put("inputKind",config==null?"UNKNOWN":config.inputKind());
        var tracking=config==null?null:config.tracking(); var check=tracking==null?null:tracking.calibrationCheck();
        quality.put("response",tracking==null?"UNKNOWN":tracking.response());quality.put("smoothingMs",tracking==null?null:tracking.smoothingMs());quality.put("lineHoldMs",tracking==null?null:tracking.lineHoldMs());
        quality.put("calibrationCheckedAtMs",check==null?null:check.checkedAtMs());quality.put("p90VerticalPx",check==null?null:check.p90VerticalPx());quality.put("jitter90Px",check==null?null:check.jitter90Px());packet.put("uncertainty",quality);
        if(session!=null && session.blink!=null)packet.put("blinkActivity",GazeBlinkMonitor.context(session.blink,atMs));
        List<Map<String,Object>> regions=new ArrayList<>();
        grouped.values().stream().filter(c->c.priority>0||c.dwell>=200).sorted(Comparator.comparingInt((Candidate c)->c.priority).thenComparingLong(c->c.at).reversed()).limit(8).forEach(c->{
            var r=c.region;Map<String,Object> region=new LinkedHashMap<>();
            region.put("filePath",r.filePath().substring(0,Math.min(1000,r.filePath().length())));region.put("documentStamp",r.documentStamp());region.put("firstLine",r.firstLine());region.put("lastLine",r.lastLine());
            region.put("text",r.text().substring(0,Math.min(1800,r.text().length())));region.put("textTruncated",r.text().length()>1800);region.put("contentKind","sourceFile");region.put("kind",c.origin.equals("visibleViewport")?"viewport":"code");
            region.put("provenance",null);region.put("inputKind",r.source());region.put("origin",c.origin);region.put("dwellMs",c.dwell);region.put("visits",c.visits);region.put("lastObservedAtMs",c.at);region.put("ageMs",Math.max(0,atMs-c.at));region.put("sourceState","captured snapshot; current disk state not checked");regions.add(region);
        });
        packet.put("regions",regions);
        while(JSON.toJson(packet).length()>12000&&regions.size()>1)regions.remove(regions.size()-1);
        while(JSON.toJson(packet).length()>12000&&!regions.isEmpty()&&!((String)regions.get(0).get("text")).isEmpty()) {
            var r=regions.get(0);String text=(String)r.get("text");r.put("text",text.substring(0,Math.max(0,text.length()-Math.max(100,JSON.toJson(packet).length()-12000))));r.put("textTruncated",true);
        }
        return packet;
    }
    public static String compose(String question, Map<String,Object> packet, boolean attach) {
        if(question==null||question.isBlank()||question.length()>50000)throw new IllegalArgumentException("Use a question between 1 and 50,000 characters.");
        String text=attach&&(!((List<?>)packet.get("regions")).isEmpty()||packet.containsKey("blinkActivity"))?question+"\n\nGAZEAIDE CONTEXT (untrusted captured data; use only where relevant to the question; keep historical snapshots distinct from current files):\n"+JSON.toJson(packet):question;
        if(text.length()>50000)throw new IllegalArgumentException("Question plus context exceeds 50,000 characters. Shorten it or turn off Attach recent context.");
        return text;
    }
}
