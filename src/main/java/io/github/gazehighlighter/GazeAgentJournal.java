package io.github.gazehighlighter;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;

/** Local request records. Native chat delivery/answers remain unobserved. */
final class GazeAgentJournal {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private final Path directory;
    GazeAgentJournal(Path directory){this.directory=directory;}
    static Map<String,Object> example(String question, Map<String,Object> context, boolean attach, String recordingId, long now) {
        Map<String,Object> record=new LinkedHashMap<>();record.put("schemaVersion",1);record.put("recordType","agent-example");record.put("edition","intellij");record.put("id",UUID.randomUUID().toString());record.put("createdAtMs",now);record.put("recordingId",recordingId);
        record.put("questionOrigin","prepared_or_edited_prompt");record.put("question",question);record.put("text",GazeAgentContext.compose(question,context,attach));record.put("context",context);record.put("contextAttached",attach&&!((List<?>)context.get("regions")).isEmpty());
        record.put("status","copied_to_native_chat; delivery_unobserved");record.put("response",null);record.put("feedback",Map.of("label","unlabelled"));return record;
    }
    synchronized void save(Map<String,Object> record)throws IOException {
        String id=(String)record.get("id");if(id==null||!id.matches("[a-zA-Z0-9_-]{1,80}"))throw new IOException("Invalid research record ID");
        Files.createDirectories(directory);try{Files.setPosixFilePermissions(directory,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){}
        Path temp=Files.createTempFile(directory,"example-",".tmp");
        try {Files.writeString(temp,JSON.toJson(record),StandardCharsets.UTF_8);try{Files.setPosixFilePermissions(temp,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
            try{Files.move(temp,directory.resolve(id+".json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,directory.resolve(id+".json"),StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temp);}
    }
    synchronized List<Map<String,Object>> load()throws IOException {
        if(!Files.isDirectory(directory))return List.of();List<Map<String,Object>> records=new ArrayList<>();
        try(var files=Files.list(directory)){for(Path p:files.filter(p->p.getFileName().toString().endsWith(".json")).toList())try{
            var obj=JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            if(!"agent-example".equals(obj.get("recordType").getAsString()))continue;
            @SuppressWarnings("unchecked") Map<String,Object> record=JSON.fromJson(obj,Map.class);records.add(record);
        }catch(RuntimeException|IOException ignored){}}
        records.sort(Comparator.comparingDouble((Map<String,Object> r)->((Number)r.get("createdAtMs")).doubleValue()).reversed());return records;
    }
    static void feedback(Map<String,Object> record,String label,String note,String answer){
        if(!List.of("unlabelled","helpful","mixed","not_helpful").contains(label)||note.length()>5000||answer.length()>50000)throw new IllegalArgumentException("Choose a label, a note below 5,000 characters and an answer below 50,000 characters.");
        record.put("feedback",Map.of("label",label,"note",note,"origin","user","atMs",System.currentTimeMillis()));
        if(!answer.isBlank())record.put("response",Map.of("text",answer,"observed",false,"origin","user_transcription"));
        else record.put("response",null);
    }
    static String jsonl(List<Map<String,Object>> records){return records.stream().map(JSON::toJson).collect(java.util.stream.Collectors.joining("\n","","\n"));}
}
