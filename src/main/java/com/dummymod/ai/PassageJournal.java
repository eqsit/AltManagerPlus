package com.dummymod.ai;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.IOException;
import java.util.*;

/** Original masonry is written before excavation, including across reconnects. */
final class PassageJournal {
    record Entry(int x,int y,int z,String block) {}
    private final Path file;
    private final Map<String,Entry> entries=new LinkedHashMap<>();
    private Entry focus;
    PassageJournal(Path file) {
        this.file=file;
        if(Files.exists(file))try {
            JsonArray data=JsonParser.parseString(Files.readString(file)).getAsJsonArray();
            Entry[] saved=new Gson().fromJson(data,Entry[].class);
            if(saved==null || saved.length>64)throw new IOException("Invalid passage journal");
            for(Entry e:saved)entries.put(key(e),e);
            for(JsonElement row:data)if(row.getAsJsonObject().has("restoreFirst") && row.getAsJsonObject().get("restoreFirst").getAsBoolean()) {
                if(focus!=null)throw new IOException("Multiple restoration targets");
                focus=new Gson().fromJson(row,Entry.class);
            }
        }catch(Exception e){throw new IllegalStateException("Не удалось прочитать сохранённый проход",e);}
    }
    private static String key(Entry e){return e.x+","+e.y+","+e.z;}
    List<Entry> entries(){return List.copyOf(entries.values());}
    Entry focus(){return focus;}
    void focus(Entry e)throws IOException {
        if(e.equals(focus))return;
        if(!entries.containsKey(key(e)))throw new IOException("Unknown restoration target");
        Entry previous=focus;focus=e;
        try{save();}catch(IOException failure){focus=previous;throw failure;}
    }
    void remember(Entry e)throws IOException {
        if(entries.containsKey(key(e)))return;
        if(entries.size()>=64)throw new IOException("Сначала нужно восстановить временные блоки");
        entries.put(key(e),e);
        try{save();}catch(IOException failure){entries.remove(key(e));throw failure;}
    }
    void restored(Entry e)throws IOException {
        if(entries.remove(key(e))==null)return;
        Entry previous=focus;if(e.equals(focus))focus=null;
        try{save();}catch(IOException failure){entries.put(key(e),e);focus=previous;throw failure;}
    }
    private void save()throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp=Files.createTempFile(file.getParent(),"passage-",".tmp");
        try {
            Files.setPosixFilePermissions(tmp,PosixFilePermissions.fromString("rw-------"));
            var data=new JsonArray();var gson=new Gson();
            for(Entry e:entries.values()) {
                JsonObject row=gson.toJsonTree(e).getAsJsonObject();if(e.equals(focus))row.addProperty("restoreFirst",true);data.add(row);
            }
            Files.writeString(tmp,gson.toJson(data));
            Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }finally{Files.deleteIfExists(tmp);}
    }
}
