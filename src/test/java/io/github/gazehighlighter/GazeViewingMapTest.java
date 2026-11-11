package io.github.gazehighlighter;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GazeViewingMapTest {
    @TempDir Path temp;
    @Test void portableMapUsesBundledSharedRuntimeAndDeduplicatedSnapshots() throws Exception {
        var r = new ViewedRegion("/demo.java", 1, 3, "Method", "void f() {\ncall();\n}", 7, "WEBCAM");
        var a = new GazeEvent(11000,1000,r.filePath(),2,0,80,"call();",null,false,GazeInputMode.WEBCAM,false,r);
        var b = new GazeEvent(12000,2000,r.filePath(),2,0,150,"call();",null,false,GazeInputMode.WEBCAM,false,r);
        var s = new GazeSession("demo",10000,13000,List.of(a,b));
        String html = Files.readString(GazeViewingMap.export(s,temp.resolve("map.html")));
        assertTrue(html.contains("export function viewingMapModule()"));
        assertTrue(html.contains("mountViewingMap(document.getElementById"));
        String payload = html.substring(html.lastIndexOf("mountViewingMap(document.getElementById('gazeaide-viewing-map'),")
                + "mountViewingMap(document.getElementById('gazeaide-viewing-map'),".length());
        var json = JsonParser.parseString(payload.substring(0,payload.indexOf(");\n</script>"))).getAsJsonObject();
        assertEquals(1,json.getAsJsonArray("mapSnapshots").size());
        assertEquals(2,json.getAsJsonArray("events").size());
        assertEquals(80,json.getAsJsonArray("events").get(0).getAsJsonObject().get("dwellMs").getAsInt());
        assertFalse(html.contains("<script src="));
    }
    @Test void hostileSourceCannotTerminateTheEmbeddedModule() throws Exception {
        var event = new GazeEvent(11000,1000,"/evil<script>.java",1,0,100,"</script><img src=x>",null,false,GazeInputMode.MOUSE);
        String html=GazeViewingMap.section(new GazeSession("demo",10000,12000,List.of(event)));
        assertEquals(1,html.split("</script>",-1).length-1);
        assertFalse(html.contains("<img src=x>"));
        assertTrue(html.contains("\\u003c"));
    }
}
