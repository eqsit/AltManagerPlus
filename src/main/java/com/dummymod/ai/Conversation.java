package com.dummymod.ai;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Persist exactly the most recent 300 user/assistant messages, plus the current design. */
public final class Conversation {
    public record Message(String role, String content) { }
    public record SavedPlan(String id,String name,String dimension,JsonObject source,long savedAt){}
    private final Deque<Message> messages = new ArrayDeque<>();
    private final Path file;
    public JsonObject plan;
    public JsonObject rejectedResponse;
    public String rejectedReason;
    public boolean planComplete;
    private final List<SavedPlan> projects=new ArrayList<>();
    public String dimension;
    public boolean creative=true,autoGive;
    public FlightSpeed flightSpeed;
    public Conversation(Path file) {
        this.file=file;
        if (Files.exists(file))try {
            JsonObject root=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("messages")) {
                JsonObject m=e.getAsJsonObject(); String role=m.get("role").getAsString();
                if (Set.of("user","assistant").contains(role)) add(role,m.get("content").getAsString());
            }
            if (root.has("plan") && root.get("plan").isJsonObject()) plan=root.getAsJsonObject("plan");
            if(root.has("rejectedResponse") && root.get("rejectedResponse").isJsonObject())rejectedResponse=root.getAsJsonObject("rejectedResponse");
            if(root.has("rejectedReason"))rejectedReason=root.get("rejectedReason").getAsString();
            if (root.has("dimension")) dimension=root.get("dimension").getAsString();
            if(root.has("creative"))creative=root.get("creative").getAsBoolean();
            if(root.has("autoGive"))autoGive=root.get("autoGive").getAsBoolean();
            if(root.has("flightSpeed"))try{flightSpeed=FlightSpeed.valueOf(root.get("flightSpeed").getAsString());}catch(IllegalArgumentException ignored){}
            if(root.has("planComplete"))planComplete=root.get("planComplete").getAsBoolean();
            if(root.has("projects"))for(JsonElement e:root.getAsJsonArray("projects"))addProject(new Gson().fromJson(e,SavedPlan.class));
        } catch (Exception ignored) { messages.clear(); }
        Path exports=file.getParent().resolveSibling("dummymod-ai-saved-projects");
        if(Files.isDirectory(exports))try(var files=Files.newDirectoryStream(exports,file.getFileName().toString().replace(".json","")+"-*.json")) {
            for(Path p:files)try{addProject(new Gson().fromJson(Files.readString(p),SavedPlan.class));}catch(Exception ignored){}
        }catch(Exception ignored){}
    }
    public void add(String role,String content) {
        messages.addLast(new Message(role,content));
        while(messages.size()>300) messages.removeFirst();
    }
    public List<Message> messages() { return List.copyOf(messages); }
    public List<Message> requestMessages() {
        List<Message> result=new ArrayList<>(messages);
        if(rejectedResponse!=null && !result.isEmpty() && result.getLast().role().equals("user"))
            result.add(result.size()-1,new Message("assistant",rejectedResponse.toString()));
        return List.copyOf(result);
    }
    public void reject(JsonObject response,String reason){rejectedResponse=response.deepCopy();rejectedReason=reason;}
    public void clearRejected(){rejectedResponse=null;rejectedReason=null;}
    public List<SavedPlan> projects(){return List.copyOf(projects);}
    private void addProject(SavedPlan saved) {
        if(saved!=null && saved.id()!=null && saved.source()!=null && saved.dimension()!=null && projects.stream().noneMatch(p->p.id().equals(saved.id()) || p.source().equals(saved.source())))projects.add(saved);
    }
    public void archiveCurrent() {
        if(plan!=null && !planComplete)addProject(new SavedPlan(UUID.randomUUID().toString(),plan.has("name")?plan.get("name").getAsString():"Постройка",dimension==null?"minecraft:overworld":dimension,plan.deepCopy(),System.currentTimeMillis()));
    }
    public void restore(SavedPlan saved) {
        archiveCurrent();plan=saved.source().deepCopy();dimension=saved.dimension();planComplete=false;clearRejected();save();
    }
    public void clear() { messages.clear();clearRejected();save(); }
    public void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp=Files.createTempFile(file.getParent(),"history-",".tmp");
            try {
                try { Files.setPosixFilePermissions(tmp,PosixFilePermissions.fromString("rw-------")); } catch(UnsupportedOperationException ignored) { }
                JsonObject root=new JsonObject();root.add("messages",new Gson().toJsonTree(messages));
                if(plan!=null) root.add("plan",plan);
                if(rejectedResponse!=null)root.add("rejectedResponse",rejectedResponse);
                if(rejectedReason!=null)root.addProperty("rejectedReason",rejectedReason);
                root.addProperty("planComplete",planComplete);root.add("projects",new Gson().toJsonTree(projects));
                if(dimension!=null) root.addProperty("dimension",dimension);
                root.addProperty("creative",creative);root.addProperty("autoGive",autoGive);
                if(flightSpeed!=null)root.addProperty("flightSpeed",flightSpeed.name());
                Files.writeString(tmp,new Gson().toJson(root));
                Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(tmp); }
        } catch (Exception e) { org.slf4j.LoggerFactory.getLogger("DummyMod-AI").warn("Could not save AI conversation ({})",e.getClass().getSimpleName()); }
    }
}
