package io.github.gazehighlighter;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;

/** A versioned append journal. force(false) bounds loss to writes still queued in memory. */
final class GazeSessionJournal {
    private static final Gson JSON=GazeSessionStore.JSON;
    private final Path directory;
    GazeSessionJournal(Path directory){this.directory=directory;}
    private Path file(long start){return directory.resolve("active-"+start+".ndjson");}
    void begin(GazeSession s)throws IOException {
        Files.createDirectories(directory);
        Files.writeString(file(s.startMs),"",StandardOpenOption.CREATE_NEW);
        append(s.startMs,"start",s);
    }
    void append(long start,String kind,Object value)throws IOException {
        if(kind.equals("blink")) {
            Path target=directory.resolve("active-"+start+".blink.json"),temp=Files.createTempFile(directory,"blink-",".tmp");
            try{Files.writeString(temp,JSON.toJson(value));try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temp);}return;
        }
        JsonObject record=new JsonObject();record.addProperty("kind",kind);record.add("value",JSON.toJsonTree(value));
        byte[] bytes=(record+"\n").getBytes(StandardCharsets.UTF_8);
        try(var channel=FileChannel.open(file(start),StandardOpenOption.WRITE,StandardOpenOption.APPEND)) {
            var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(false);
        }
    }
    void complete(long start)throws IOException {Files.deleteIfExists(file(start));Files.deleteIfExists(directory.resolve("active-"+start+".blink.json"));}
    List<GazeSession> recover()throws IOException {
        List<GazeSession> result=new ArrayList<>();if(!Files.isDirectory(directory))return result;
        try(var files=Files.list(directory)) {
            for(var p:files.filter(p->p.getFileName().toString().matches("active-[0-9]+\\.ndjson")).toList()) {
                try(var reader=Files.newBufferedReader(p)) {
                    GazeSession header=null;List<GazeEvent> events=new ArrayList<>();List<GazeSession.ConfigurationChange> changes=new ArrayList<>();
                    GazeBlinkMonitor.Data blink=null;var active=new HashMap<String,GazeEvent>();boolean torn=false;String line;
                    while((line=reader.readLine())!=null) {
                        JsonObject record;try{record=JsonParser.parseString(line).getAsJsonObject();}catch(RuntimeException e){torn=true;continue;}
                        if(torn)throw new IOException("Damaged recording journal");
                        var value=record.get("value");switch(record.get("kind").getAsString()) {
                            case "start" -> header=JSON.fromJson(value,GazeSession.class);
                            case "visit" -> {var e=JSON.fromJson(value,GazeEvent.class);events.add(e);active.remove(e.filePath+":"+e.line);}
                            case "active" -> {var e=JSON.fromJson(value,GazeEvent.class);active.put(e.filePath+":"+e.line,e);}
                            case "configuration" -> changes.add(JSON.fromJson(value,GazeSession.ConfigurationChange.class));
                            case "blink" -> blink=JSON.fromJson(value,GazeBlinkMonitor.Data.class);
                            default -> throw new IOException("Unknown recording record");
                        }
                    }
                    if(header!=null) {
                        try{blink=JSON.fromJson(Files.readString(directory.resolve("active-"+header.startMs+".blink.json")),GazeBlinkMonitor.Data.class);}catch(IOException|RuntimeException ignored){}
                        events.addAll(active.values());events.sort(Comparator.comparingLong(e->e.elapsedMs));
                        long end=events.stream().mapToLong(e->e.timestampMs).max().orElse(header.startMs);
                        if(blink!=null)end=Math.max(end,blink.capturedAtMs());
                        result.add(new GazeSession(header.projectName,header.startMs,end,events,header.metadata,changes,blink,true));
                    }
                }catch(RuntimeException|IOException ignored){/* A bad journal never hides another recording. */}
            }
        }return result;
    }
}
