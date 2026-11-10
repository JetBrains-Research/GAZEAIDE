package io.github.gazehighlighter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.JsonParser;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GazeRecordingRecoveryTest {
    @TempDir Path temp;
    GazeEvent event(long at,int line,long dwell){return new GazeEvent(at,at-1000,"demo",line,0,dwell,"line",null,false,GazeInputMode.MOUSE);}
    @Test void journalRecoversVisitsAndActiveCheckpointWithoutStopAndIgnoresTornTail() throws Exception {
        var j=new GazeSessionJournal(temp);j.begin(new GazeSession("demo",1000,1000,List.of()));
        j.append(1000,"visit",event(1100,1,100));j.append(1000,"active",event(1200,2,100));
        Files.writeString(temp.resolve("active-1000.ndjson"),"{torn",StandardOpenOption.APPEND);
        var recovered=new GazeSessionJournal(temp).recover().get(0);
        assertEquals(2,recovered.events.size());assertEquals(1200,recovered.endMs);assertTrue(recovered.inProgress);
    }
    @Test void completedVisitReplacesItsEarlierActiveCheckpointAndFinalSaveRemovesJournal() throws Exception {
        var j=new GazeSessionJournal(temp);var s=new GazeSession("demo",1000,1500,List.of(event(1500,1,500)));
        j.begin(new GazeSession("demo",1000,1000,List.of()));j.append(1000,"active",event(1200,1,200));j.append(1000,"visit",event(1500,1,500));
        assertEquals(1,j.recover().get(0).events.size());assertEquals(500,j.recover().get(0).events.get(0).dwellMs);
        new GazeSessionStore(temp).save(s);j.complete(1000);assertTrue(j.recover().isEmpty());
        assertTrue(Files.readString(temp.resolve(GazeSessionStore.id(s)+".json")).contains("\"schemaVersion\":4"));
    }
    @Test void damagedQueueEntryIsIsolatedFromValidEntriesAndClearPending() throws Exception {
        var outbox=new GazeRemoteOutbox(temp);var c=new GazeRemoteConnection(GazeRemoteConnection.Connector.HTTPS,"https://research.example/upload","study","P1","task",true,true,true);
        var s=new GazeSession("demo",1000,1500,List.of(event(1500,1,500)));
        outbox.enqueue(s,c);Files.writeString(temp.resolve("aaaaaaaaaaaaaaaaaaaaaaaa.json"),"{broken");
        assertEquals(1,outbox.entries().size());assertTrue(Files.isDirectory(temp.resolve("unreadable")));
        outbox.clearPending();assertTrue(outbox.entries().isEmpty());
    }
    @Test void languageAwareBlocksPreserveRustLifetimesAndJavascriptPrivateFields() {
        String rust="fn f<'a>(x: &'a str) {\n work();\n}",js="class A {\n #value = 1;\n method() { return this.#value; }\n}";
        assertTrue(CodeRegion.mask(rust,"demo.rs").contains("work()"));assertTrue(CodeRegion.mask(js,"demo.js").contains("method()"));
        assertEquals(2,CodeRegion.find(js,2,"demo.js").firstLine());
    }
    @Test void lowCadenceWindowsCannotBeUsedAsPersonalBlinkReferences() {
        var c=new GazeBlinkMonitor.Config("P1","camera",true,null,null,false);
        for(int hz:List.of(10,20,25,30)) {
            var m=new GazeBlinkMonitor(0,c,null);
            for(int n=0;n<=hz*60;n++){long t=Math.round(n*1000.0/hz);boolean closed=t%5000>=1020&&t%5000<1100;m.sample(t,closed?.1:.25,closed?.1:.25,true);}
            assertEquals(hz>=25,m.snapshot(60000).windows().get(0).usable());
        }
    }
    @Test void simulationExportNeverLabelsTheSourceAsWebcam() throws Exception {
        var e=new GazeEvent(1100,100,"demo",1,0,100,"line",null,false,GazeInputMode.WEBCAM,true,null);
        var s=new GazeSession("demo",1000,1100,List.of(e));
        assertTrue(Files.readString(GazeSessionExporter.exportCsv(s,temp.resolve("simulation.csv"))).contains("MOUSE_SIMULATION"));
    }
    @Test void sessionStorageRoundTripsCanonicalAndLegacyInputProvenance() throws Exception {
        var simulation = new GazeEvent(1100,100,"demo",1,0,100,"line",null,false,GazeInputMode.WEBCAM,true,null);
        String encoded = GazeSessionStore.JSON.toJson(simulation);
        assertTrue(encoded.contains("\"source\":\"MOUSE_SIMULATION\""));
        assertEquals("MOUSE_SIMULATION",GazeSessionStore.JSON.fromJson(encoded,GazeEvent.class).inputLabel());
        var mouse = event(1200,2,100);
        assertEquals("MOUSE_PROXY",GazeSessionStore.JSON.fromJson(GazeSessionStore.JSON.toJson(mouse),GazeEvent.class).inputLabel());
        String legacy = "{\"source\":\"WEBCAM\",\"simulated\":true}";
        assertEquals("MOUSE_SIMULATION",GazeSessionStore.JSON.fromJson(legacy,GazeEvent.class).inputLabel());
    }
    @Test void recoveredExportsRetainIncompleteStatus() throws Exception {
        var s = new GazeSession("demo",1000,1200,List.of(event(1200,1,100)),null,List.of(),null,true);
        String json = Files.readString(GazeSessionExporter.exportJson(s,temp.resolve("recovered.json")));
        assertTrue(JsonParser.parseString(json).getAsJsonObject().get("inProgress").getAsBoolean());
        String csv = Files.readString(GazeSessionExporter.exportCsv(s,temp.resolve("recovered.csv")));
        assertTrue(csv.contains("session_in_progress"));assertTrue(csv.stripTrailing().endsWith(",true"));
        assertTrue(Files.readString(GazeSessionExporter.exportHeatmapHtml(s,temp.resolve("recovered.html"))).contains("Recovered checkpoint"));
    }
}
