package io.github.gazehighlighter;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GazeAgentResearchTest {
    @TempDir Path temp;
    private ViewedRegion region(long version,String source) {return new ViewedRegion("a.java",1,3,"answer","int answer() { return 42; }",version,source);}
    private GazeEvent event(long at, long version, boolean simulation) {return new GazeEvent(at,at-1000,"a.java",1,0,300,"int answer()",null,false,GazeInputMode.WEBCAM,simulation,region(version,"WEBCAM"));}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> regions(Map<String,Object> p) {return (List<Map<String,Object>>)p.get("regions");}
    @Test void sharedContextFixtureMatchesStandaloneExactly() throws Exception {
        try(var stream=getClass().getResourceAsStream("/agent-context-v1.json")) {
            var json=com.google.gson.JsonParser.parseString(new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            var gson=new com.google.gson.GsonBuilder().serializeNulls().disableHtmlEscaping().create();
            var actual=GazeAgentContext.build(gson.fromJson(json.get("session"),GazeSession.class),gson.fromJson(json.get("target"),ViewedRegion.class),gson.fromJson(json.get("viewport"),ViewedRegion.class),null,json.get("atMs").getAsLong());
            assertEquals(json.get("expected"),gson.toJsonTree(actual));
        }
    }
    @Test void noFutureLeakageAndSeparateInputAndVersions() {
        var session=new GazeSession("test",1000,4000,List.of(event(2000,1,false),event(2500,1,false),event(3001,1,false),event(-999999,1,false),event(2400,2,false),event(2450,1,true)));
        var p=GazeAgentContext.build(session,null,null,null,3000);
        assertEquals(3,regions(p).size());
        var webcam=regions(p).stream().filter(r->r.get("documentStamp").equals(1L)&&r.get("inputKind").equals("WEBCAM")).findFirst().orElseThrow();
        assertEquals(2,webcam.get("visits"));assertEquals(600L,webcam.get("dwellMs"));
        assertTrue(regions(p).stream().anyMatch(r->r.get("inputKind").equals("MOUSE_SIMULATION")));
    }
    @Test void targetAndViewportAreExplicitAndOptOutPreservesQuestion() {
        var p=GazeAgentContext.build(null,region(1,"WEBCAM"),region(1,"VIEWPORT"),null,3000);
        assertEquals("selectedTarget",regions(p).get(0).get("origin"));
        assertEquals("VIEWPORT",regions(p).get(1).get("inputKind"));assertEquals(0L,regions(p).get(1).get("dwellMs"));
        String q="  What should I inspect?\n";assertEquals(q,GazeAgentContext.compose(q,p,false));assertTrue(GazeAgentContext.compose(q,p,true).contains("untrusted captured data"));
        assertThrows(IllegalArgumentException.class,()->GazeAgentContext.compose("x".repeat(50000),p,true));
    }
    @Test void longRecordingsKeepEarlyContextAndCapOnlyEligibleVisits() {
        long start=1700000000000L;
        var events=new ArrayList<GazeEvent>();
        for(int i=0;i<6000;i++) events.add(event(start+i*100L,i<1000?1:2,false));
        var session=new GazeSession("long",start,start+600000,events);
        var early=GazeAgentContext.build(session,null,null,null,start+30000);
        assertEquals(301,((Map<?,?>)early.get("bounds")).get("visitsConsidered"));
        assertEquals(1,regions(early).size());
        assertEquals(1L,regions(early).get(0).get("documentStamp"));
        assertEquals(start+30000,regions(early).get(0).get("lastObservedAtMs"));
        var latest=GazeAgentContext.build(session,null,null,null,start+600000);
        assertEquals(5000,((Map<?,?>)latest.get("bounds")).get("visitsConsidered"));
        assertEquals(1,regions(latest).size());
        assertEquals(2L,regions(latest).get(0).get("documentStamp"));
    }
    @Test void oversizedContextIsBounded() {
        var events=new ArrayList<GazeEvent>();
        for(int i=0;i<60;i++) events.add(new GazeEvent(2000+i,1000+i,"a.java",1,0,300,"",null,false,GazeInputMode.WEBCAM,false,new ViewedRegion("x".repeat(1000)+i,1,3,"big","hello\n".repeat(1000),i,"WEBCAM")));
        var p=GazeAgentContext.build(new GazeSession("test",1000,3000,events),null,null,null,3000);
        assertTrue(new Gson().toJson(p).length()<=12000);assertTrue(regions(p).size()<=8);
    }
    @Test void nativeJournalPreservesUnobservedDeliveryAndLabelledManualAnswer() throws Exception {
        var session=new GazeSession("fixture",1000,4000,List.of());
        var exported=GazeSessionExporter.exportJson(session,temp.resolve("recording.json"));
        assertEquals("intellij-1000",com.google.gson.JsonParser.parseString(Files.readString(exported)).getAsJsonObject().get("researchRecordingId").getAsString());
        var journal=new GazeAgentJournal(temp);var context=GazeAgentContext.build(null,region(1,"WEBCAM"),null,null,3000);
        var e=GazeAgentJournal.example("Question",context,true,"intellij-1000",3000);journal.save(e);
        var read=journal.load().get(0);assertNull(read.get("response"));assertEquals("copied_to_native_chat; delivery_unobserved",read.get("status"));
        GazeAgentJournal.feedback(read,"helpful","Checked manually","Pasted native answer");journal.save(read);
        var persisted=journal.load().get(0);var response=(Map<?,?>)persisted.get("response");assertEquals(false,response.get("observed"));assertEquals("user_transcription",response.get("origin"));
        assertTrue(GazeAgentJournal.jsonl(journal.load()).contains("agent-example"));
        Files.writeString(temp.resolve("broken.json"),"broken");assertEquals(1,journal.load().size());
    }
    @Test void extraSteadyReducesAlternatingJitterAndConverges() {
        double[] energy=new double[2];int[] smooth={GazeResponse.STEADY.smoothingMs,GazeResponse.EXTRA_STEADY.smoothingMs};
        for(int j=0;j<2;j++){var f=new GazePointFilter();for(int i=0;i<100;i++){var p=f.update(0,i%2==0?-40:40,i*40,smooth[j]);if(i>30)energy[j]+=p.y*p.y;}}
        assertTrue(energy[1]<energy[0]);var f=new GazePointFilter();f.update(0,0,0,260);java.awt.Point p=null;
        for(int t=40;t<=2000;t+=40)p=f.update(100,300,t,260);assertEquals(300,p.y,1);
        assertEquals(260,GazeResponse.EXTRA_STEADY.lineHoldMs);assertEquals(GazeResponse.STEADY,GazeResponse.from("old"));
    }
}
