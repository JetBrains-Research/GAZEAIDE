package io.github.gazehighlighter;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GazeBlinkMonitorTest {
    @TempDir Path temp;
    final GazeBlinkMonitor.Config config=new GazeBlinkMonitor.Config("P01","camera-a",true,1,3,false);
    GazeBlinkMonitor monitor(long start) {return new GazeBlinkMonitor(start,config,GazeBlinkMonitor.reference(List.of(),config,start));}
    void stream(GazeBlinkMonitor m,long from,long to,int step,boolean blinks) {
        for(long t=from;t<=to;t+=step){double n=blinks&&t%5000>=1000&&t%5000<1160?.1:.25;m.sample(1000+t,n,n,true);}
    }
    GazeSession saved(GazeBlinkMonitor m,long end) {return new GazeSession("fixture",1000,1000+end,List.of(),null,List.of(),m.snapshot(1000+end));}
    @Test void sharedTimestampedEyeFixtureMatchesStandaloneExactly() throws Exception {
        try(var stream=getClass().getResourceAsStream("/blink-v1.json")) {
            var f=JsonParser.parseString(new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            var gson=new GsonBuilder().serializeNulls().disableHtmlEscaping().create();var o=f.getAsJsonObject("options");
            var m=new GazeBlinkMonitor(o.get("startMs").getAsLong(),gson.fromJson(o.get("config"),GazeBlinkMonitor.Config.class),gson.fromJson(o.get("reference"),GazeBlinkMonitor.Reference.class));
            for(var item:f.getAsJsonArray("runs")){var r=item.getAsJsonObject();for(long t=r.get("from").getAsLong();t<=r.get("to").getAsLong();t+=r.get("step").getAsLong())m.sample(t,r.get("left").getAsDouble(),r.get("right").getAsDouble(),r.get("face").getAsBoolean());}
            assertEquals(f.get("expected"),gson.toJsonTree(m.snapshot(f.get("atMs").getAsLong())));
        }
    }
    @Test void falseClosuresAreInterruptedAndPausesAreImmediatelyStale() {
        for(String kind:List.of("face","pause","gap","wink")) {
            var m=monitor(1000);stream(m,0,1040,40,true);
            switch(kind) {case "face"->m.sample(2080,0,0,false);case "pause"->m.interrupt(2080,"paused");case "gap"->m.sample(2400,.25,.25,true);case "wink"->m.sample(2080,.1,.25,true);}
            stream(m,1480,2000,40,false);var d=m.snapshot(3000);assertEquals("INTERRUPTED",d.events().get(0).kind());assertTrue(d.events().stream().noneMatch(e->e.kind().equals("BLINK")));
        }
        var m=monitor(1000);stream(m,0,60000,40,false);assertEquals(0.0,m.snapshot(61000).summary().blinkRatePerMinute());m.interrupt(61000,"paused");assertEquals("STALE",m.snapshot(61000).indicator().state());
    }
    @Test void sparseFramesAreUnknownAndGapsDoNotCountObservedTime() {
        var m=monitor(1000);stream(m,0,60000,200,true);var d=m.snapshot(61000);assertFalse(d.summary().usable());assertNull(d.summary().blinkRatePerMinute());assertNull(d.indicator().score());
        var b=monitor(1000);stream(b,0,59960,40,false);b.sample(61040,.25,.25,true);b.sample(181000,.25,.25,true);assertEquals(60040,b.snapshot(181000).windows().stream().mapToLong(GazeBlinkMonitor.Window::observedMs).sum());
    }
    @Test void referencePersistsAcrossSessionsAndIsPersonCameraRestAndTimeSpecific() throws Exception {
        var m=monitor(1000);stream(m,0,180000,40,true);var s=saved(m,180000);var store=new GazeSessionStore(temp);store.save(s);var sessions=store.load();
        var ref=GazeBlinkMonitor.reference(sessions,config,200000);assertTrue(ref.ready());assertEquals(3,ref.windows());assertEquals(36,ref.blinks());
        assertFalse(GazeBlinkMonitor.reference(sessions,new GazeBlinkMonitor.Config("P02","camera-a",true,null,null,false),200000).ready());
        assertFalse(GazeBlinkMonitor.reference(sessions,new GazeBlinkMonitor.Config("P01","other",true,null,null,false),200000).ready());
        assertFalse(GazeBlinkMonitor.reference(sessions,config,1000).ready());
        var tired=new GazeBlinkMonitor(1000,new GazeBlinkMonitor.Config("P01","camera-a",true,8,null,false),null);stream(tired,0,180000,40,true);assertFalse(GazeBlinkMonitor.reference(List.of(saved(tired,180000)),config,200000).ready());
        assertEquals(0,GazeBlinkMonitor.indicator(s.blink.summary(),ref,false).score());
    }
    @Test void blinkOnlyExportContextAndLegacyLoadWorkWithoutRemoteTelemetry() throws Exception {
        var m=monitor(1000);stream(m,0,60000,40,true);var s=saved(m,60000);
        var context=GazeAgentContext.build(s,null,null,null,61000);assertTrue(context.containsKey("blinkActivity"));assertFalse(new Gson().toJson(context.get("blinkActivity")).contains("P01"));assertEquals("q",GazeAgentContext.compose("q",context,false));assertTrue(GazeAgentContext.compose("q",context,true).contains("blinkActivity"));
        var written=GazeSessionExporter.exportAll(s,temp,"blink");assertEquals(4,written.size());assertTrue(Files.readString(written.get(1)).contains("blink"));assertTrue(Files.readString(written.get(2)).contains("Blink activity"));assertTrue(Files.readString(written.get(3)).contains("BLINK"));
        var store=new GazeSessionStore(temp.resolve("legacy"));store.save(new GazeSession("legacy",1000,2000,List.of()));assertNull(store.load().get(0).blink);
        assertEquals("INSUFFICIENT_OBSERVATIONS",GazeBlinkMonitor.context(s.blink,1000).get("state"));
    }
}
