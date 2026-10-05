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
    PassageJournal(Path file) {
        this.file=file;
        if(Files.exists(file))try {
            Entry[] saved=new Gson().fromJson(Files.readString(file),Entry[].class);
            if(saved==null || saved.length>64)throw new IOException("Invalid passage journal");
            for(Entry e:saved)entries.put(key(e),e);
        }catch(Exception e){throw new IllegalStateException("Не удалось прочитать сохранённый проход",e);}
    }
    private static String key(Entry e){return e.x+","+e.y+","+e.z;}
    List<Entry> entries(){return List.copyOf(entries.values());}
    void remember(Entry e)throws IOException {
        if(entries.containsKey(key(e)))return;
        if(entries.size()>=64)throw new IOException("Сначала нужно восстановить временные блоки");
        entries.put(key(e),e);
        try{save();}catch(IOException failure){entries.remove(key(e));throw failure;}
    }
    void restored(Entry e)throws IOException {
        if(entries.remove(key(e))==null)return;
        try{save();}catch(IOException failure){entries.put(key(e),e);throw failure;}
    }
    private void save()throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp=Files.createTempFile(file.getParent(),"passage-",".tmp");
        try {
            Files.setPosixFilePermissions(tmp,PosixFilePermissions.fromString("rw-------"));
            Files.writeString(tmp,new Gson().toJson(entries.values()));
            Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }finally{Files.deleteIfExists(tmp);}
    }
}
