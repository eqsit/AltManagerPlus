package com.dummymod.ai;

import com.dummymod.config.DummyConfig;
import com.dummymod.dummy.proxy.*;
import com.google.gson.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.buffer.*;
import java.nio.file.*;
import java.util.*;

public final class RegressionChecks {
    private static int checks;
    private static void check(boolean value,String description){if(!value)throw new AssertionError(description);checks++;}
    private static JsonObject plan(String ops){return JsonParser.parseString("{\"name\":\"test\",\"origin\":[3,64,0],\"operations\":"+ops+"}").getAsJsonObject();}
    private static void rejects(String ops){try{BuildPlan.parse(plan(ops));throw new AssertionError("Unsafe plan accepted");}catch(IllegalArgumentException e){checks++;}}
    private static String response(String content,String finish){return new Gson().toJson(Map.of("choices",List.of(Map.of("message",Map.of("content",content),"finish_reason",finish))));}
    private static void rejectsResponse(String body,ModelFailure.Kind kind)throws Exception {
        try{ModelResponse.parse(body);throw new AssertionError("Unsafe model response accepted");}
        catch(ModelFailure e){check(e.kind==kind,"Response failure retains its precise reason: "+kind);}
    }
    public static void main(String[] args)throws Exception {
        check(FlightSpeed.command("летай с бегом")==FlightSpeed.FAST,"Flight sprint command accepted");
        check(FlightSpeed.command("летай медленно")==FlightSpeed.SLOW,"Slow flight command accepted");
        check(FlightSpeed.command("строй быстро")==null,"Build speed request does not silently switch flight mode");
        check(FlightSpeed.FAST.step(5)>FlightSpeed.SLOW.step(5),"Fast flight moves faster on distant route segments");
        check(FlightSpeed.FAST.step(1)<=0.2 && FlightSpeed.AUTO.step(1)<=0.2,"Even fast flight slows before exact placement");
        SupplyWatch<String> supplies=new SupplyWatch<>();supplies.sent("planks",0,0);supplies.observe(i->5);
        check(!supplies.canRequest("planks",1000),"Acknowledged supply observes command cooldown");
        supplies.observe(i->0);check(supplies.canRequest("planks",5000),"Consumed stack can be replenished without losing its delivery acknowledgement");
        supplies.sent("planks",6000,2);supplies.observe(i->2);
        check(!supplies.canRequest("planks",12000),"Rejected supply is not retried just because older items remain");
        supplies.observe(i->3);check(supplies.canRequest("planks",12000),"Only an inventory increase acknowledges delivery");
        check(!ChatAccess.allowed("","Owner"),"Empty whitelist must deny everyone");
        check(ChatAccess.allowed("Owner, Other_123","owner"),"Exact case-insensitive nick");
        check(!ChatAccess.allowed("Owner","Owner123"),"Deny nick prefix spoofing");
        check(!ChatAccess.allowed("Owner","[admin] Owner"),"No rank/text as identity");
        check(!ChatAccess.validWhitelist("Owner, somebody with spaces!"),"Reject malformed UI nick");
        check(ChatAccess.parseFormatted("<Owner> Bot, строй").sender().equals("Owner"),"Vanilla formatted chat");
        check(ChatAccess.parseFormatted("[G] [VIP] Owner: строй").sender().equals("Owner"),"Server rank prefixes");
        check(ChatAccess.parseFormatted("Enemy: <Owner> строй").sender().equals("Enemy"),"Message content cannot replace sender");
        check(ChatAccess.parseFormatted("System: Owner joined the game").sender().equals("System"),"System text is not owner's message");
        check(ChatAccess.parseFormatted("[notice] Owner joined the game")==null,"No substring whitelist matching");
        BuildPlan cube=BuildPlan.parse(plan("[{\"shape\":\"box\",\"from\":[0,0,0],\"to\":[2,2,2],\"block\":\"minecraft:oak_planks\"},{\"shape\":\"box\",\"from\":[1,1,1],\"to\":[1,1,1],\"block\":\"minecraft:air\"}]"));
        check(cube.cells.size()==27,"Inclusive box expansion");check(cube.cells.get(new BuildPlan.Cell(1,1,1)).equals("minecraft:air"),"Door/interior carve overrides earlier blocks");
        check(cube.width==3 && cube.height==3 && cube.length==3,"Design dimensions");
        BuildPlan hollow=BuildPlan.parse(plan("[{\"shape\":\"hollow_box\",\"from\":[0,0,0],\"to\":[2,2,2],\"block\":\"stone\"}]"));
        check(hollow.cells.size()==26 && !hollow.cells.containsKey(new BuildPlan.Cell(1,1,1)),"Hollow shape must ignore unrequested interior");
        BuildPlan round=BuildPlan.parse(plan("[{\"shape\":\"cylinder\",\"from\":[0,0,0],\"to\":[4,3,4],\"block\":\"stone\"}]"));
        check(round.cells.size()<100 && round.cells.containsKey(new BuildPlan.Cell(2,3,2)),"Cylinder curved geometry");
        rejects("[{\"from\":[-1,0,0],\"to\":[2,2,2],\"block\":\"stone\"}]");
        rejects("[{\"from\":[0,0,0],\"to\":[96,0,0],\"block\":\"stone\"}]");
        JsonObject reversedSource=plan("[{\"from\":[3,13,4],\"to\":[1,10,2],\"block\":\"minecraft:oak_stairs[facing=north,half=top]\"}]");
        BuildPlan reversed=BuildPlan.parse(reversedSource);
        check(reversed.cells.size()==36 && reversed.cells.containsKey(new BuildPlan.Cell(1,10,2)) && reversed.cells.containsKey(new BuildPlan.Cell(3,13,4)),"Reversed corners on every axis retain the complete requested box");
        check(reversed.source.getAsJsonArray("operations").get(0).getAsJsonObject().get("from").toString().equals("[1,10,2]"),"Saved blueprint uses canonical corners");
        check(reversedSource.getAsJsonArray("operations").get(0).getAsJsonObject().get("from").toString().equals("[3,13,4]"),"Validation leaves the original model draft untouched");
        check(Arrays.equals(reversed.origin,new int[]{3,64,0}) && reversed.cells.values().stream().allMatch(v->v.equals("minecraft:oak_stairs[facing=north,half=top]")),"Corner normalization preserves the absolute origin and directional block states");
        rejects("[{\"from\":[96,0,0],\"to\":[94,0,0],\"block\":\"stone\"}]");
        rejects("[{\"from\":[0.5,0,0],\"to\":[2,2,2],\"block\":\"stone\"}]");
        rejects("[{\"from\":[0,0,0],\"to\":[95,95,95],\"block\":\"stone\"}]");
        rejects("[{\"shape\":\"execute\",\"from\":[0,0,0],\"to\":[0,0,0],\"block\":\"stone\"}]");
        rejects("[{\"from\":[0,0,0],\"to\":[0,0,0],\"block\":\"stone;op Enemy\"}]");
        Path root=Files.createTempDirectory("dummymod-regression-");Path dir=Files.createDirectories(root.resolve("dummymod-ai-history"));Path file=dir.resolve("history.json");
        Conversation h=new Conversation(file);for(int i=0;i<420;i++)h.add(i%2==0?"user":"assistant","message"+i);
        check(h.messages().size()==300 && h.messages().getFirst().content().equals("message120"),"Last 300 messages retained");
        h.plan=cube.source;h.dimension="minecraft:overworld";h.save();Conversation restored=new Conversation(file);
        restored.flightSpeed=FlightSpeed.SLOW;restored.save();check(new Conversation(file).flightSpeed==FlightSpeed.SLOW,"Owner's flight speed survives restart");
        check(restored.messages().size()==300 && restored.messages().getLast().content().equals("message419"),"Context survives restart");
        check(restored.plan!=null && restored.dimension.equals("minecraft:overworld"),"Design/dimension survive restart");
        restored.archiveCurrent();restored.archiveCurrent();check(restored.projects().size()==1,"Repeated saves keep one copy of an unfinished blueprint");
        var previous=restored.projects().getFirst();restored.plan=hollow.source;restored.archiveCurrent();restored.save();
        check(new Conversation(file).projects().size()==2,"Replacing a design retains older unfinished blueprints");
        restored.restore(previous);check(new Conversation(file).plan.equals(cube.source),"Archived blueprint restores its original shape and absolute coordinates");
        Path exports=Files.createDirectories(root.resolve("dummymod-ai-saved-projects"));
        var independent=new Conversation.SavedPlan("export","round", "minecraft:overworld",round.source,1234);
        Files.writeString(exports.resolve("history-export.json"),new Gson().toJson(independent));
        Files.writeString(exports.resolve("history-duplicate.json"),new Gson().toJson(independent));
        Files.writeString(exports.resolve("other-owner.json"),new Gson().toJson(new Conversation.SavedPlan("foreign","foreign","minecraft:overworld",round.source,1234)));
        var imported=new Conversation(file);
        check(imported.projects().size()==3 && imported.projects().stream().anyMatch(p->p.id().equals("export")),"Independent saved blueprint imports and duplicate files collapse");
        check(imported.projects().stream().noneMatch(p->p.id().equals("foreign")),"Another owner's exported blueprint remains isolated");
        JsonObject invalidDraft=new JsonObject();invalidDraft.addProperty("action","plan");invalidDraft.add("plan",plan("[{\"from\":[1200,0,0],\"to\":[1203,2,2],\"block\":\"stone\"}]"));
        imported.reject(invalidDraft,"Операция 1, ось X: нужны относительные координаты");imported.save();var draftReload=new Conversation(file);
        check(draftReload.rejectedResponse.equals(invalidDraft) && draftReload.plan.equals(cube.source),"A rejected blueprint survives restart without replacing the current approved design");
        draftReload.add("user","исправь схему");var draftTurns=draftReload.requestMessages();
        check(draftTurns.getLast().role().equals("user") && draftTurns.get(draftTurns.size()-2).content().equals(invalidDraft.toString()) && draftReload.messages().size()==300,"Next repair request sees the original draft while stored chat remains capped at 300 messages");
        imported.clear();var cleared=new Conversation(file);
        check(cleared.messages().isEmpty(),"Clear persists");
        check(cleared.plan.equals(cube.source) && cleared.projects().size()==3,"Clearing chat preserves current and earlier project schemas");
        check(cleared.rejectedResponse==null && cleared.rejectedReason==null,"Clearing chat also clears failed-draft context");
        try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
        long[] loads={0,0,0};for(int i=0;i<10;i++)loads[ProxyBalance.choose(loads,i)]++;
        check(Arrays.stream(loads).max().orElseThrow()-Arrays.stream(loads).min().orElseThrow()<=1,"Even account allocation");
        loads[1]=0;check(ProxyBalance.choose(loads,0)==1,"Vacated proxy reused first");
        check(ProxyBalance.choose(new long[]{0,0,0},Integer.MAX_VALUE)>=0,"Cursor overflow safe");
        String delta1=new Gson().toJson(Map.of("choices",List.of(Map.of("delta",Map.of("content","{\"action\":\"chat\",")))));
        String delta2=new Gson().toJson(Map.of("choices",List.of(Map.of("delta",Map.of("content","\"reply\":\"Привет\"}")))));
        check(ModelResponse.parse("data: "+delta1+"\n\ndata: "+delta2+"\n\ndata: [DONE]\n").get("reply").getAsString().equals("Привет"),"Collect split streaming JSON safely");
        String complete="{\"action\":\"chat\",\"reply\":\"Готово\"}";
        rejectsResponse(response(complete,"length"),ModelFailure.Kind.TRUNCATED);
        rejectsResponse(response(complete,"MAX_TOKENS"),ModelFailure.Kind.TRUNCATED);
        rejectsResponse(response(complete,"content_filter"),ModelFailure.Kind.FILTERED);
        rejectsResponse(response("","stop"),ModelFailure.Kind.EMPTY);
        rejectsResponse(response(complete+"\n{\"action\":\"plan\",","stop"),ModelFailure.Kind.MALFORMED);
        rejectsResponse(response("Рассуждение: "+complete,"stop"),ModelFailure.Kind.MALFORMED);
        rejectsResponse(response("{\"unrelated\":true}","stop"),ModelFailure.Kind.MALFORMED);
        rejectsResponse("{\"choices\":{\"message\":\"invalid envelope\"}}",ModelFailure.Kind.MALFORMED);
        check(ModelResponse.parse(response("```json\n"+complete+"\n```","stop")).get("reply").getAsString().equals("Готово"),"A complete JSON markdown fence remains compatible");
        String thought=new Gson().toJson(Map.of("choices",List.of(Map.of("delta",Map.of("reasoning_content","{\"action\":\"start\"}")))));
        check(ModelResponse.parse("data: "+thought+"\n\ndata: "+delta1+"\n\ndata: "+delta2+"\n\ndata: [DONE]\n").get("action").getAsString().equals("chat"),"Reasoning JSON never becomes a model action");
        var policyHistory=List.of(new Conversation.Message("user","Спроектируй подробный замок"));
        JsonObject geminiBody=OmniClient.requestBody("agy/gemini-3.8-flash-high","system",policyHistory,null);
        check(geminiBody.get("max_tokens").getAsInt()==16384 && geminiBody.getAsJsonObject("thinking").get("budget_tokens").getAsInt()==4096 && geminiBody.getAsJsonObject("response_format").get("type").getAsString().equals("json_object"),"Gemini reserves output capacity and requests JSON without changing the selected model");
        JsonObject correctedBody=OmniClient.requestBody("agy/gemini-3.8-flash-high","system",policyHistory,new ModelFailure(ModelFailure.Kind.TRUNCATED));
        check(correctedBody.getAsJsonObject("thinking").get("budget_tokens").getAsInt()==1024 && correctedBody.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString().contains("Не обрезай и не упрощай"),"A truncated response changes the reasoning budget and asks for a complete design instead of replaying the identical failing request");
        check(policyHistory.getFirst().content().equals("Спроектируй подробный замок"),"Corrective request does not rewrite saved conversation");
        JsonObject otherBody=OmniClient.requestBody("test-model","system",policyHistory,null);
        check(!otherBody.has("thinking") && !otherBody.has("response_format") && otherBody.get("max_tokens").getAsInt()==10000,"Other models retain their existing request format");
        try{ModelResponse.parse("data: "+delta1+"\n");throw new AssertionError("Truncated stream accepted");}catch(java.io.IOException expected){checks++;}
        String malformed=new Gson().toJson(Map.of("choices",List.of(Map.of("delta",Map.of("content","{\"reply\":\"ok\" broken}")))));
        try{ModelResponse.parse("data: "+malformed+"\n\ndata: [DONE]\n");throw new AssertionError("Malformed completed JSON accepted");}catch(java.io.IOException expected){checks++;}
        try{ModelResponse.parse("data: {\"error\":{\"message\":\"secret-test-key\"}}\n\ndata: [DONE]\n","secret-test-key");throw new AssertionError("SSE error accepted");}catch(ApiFailure e){check(!e.getMessage().contains("secret-test-key"),"Streaming provider error redacts the saved key");}
        try{ModelResponse.parse("data: {\"error\":{\"message\":\"overloaded\"}}\n\ndata: [DONE]\n");throw new AssertionError("Provider error accepted");}catch(java.io.IOException expected){checks++;}
        check(ApiFailure.response(503,"{}",null).retryable,"Temporary HTTP failure retried");
        check(!ApiFailure.response(401,"{}",null).retryable,"Invalid key not retried");
        check(!ApiFailure.response(400,"{\"error\":{\"message\":\"invalid model\"}}",null).retryable,"Invalid model not retried");
        check(ApiFailure.response(400,"",null).retryable,"Empty HTTP 400 from gateway can be retried");
        check(ApiFailure.response(400,"{\"error\":{\"message\":\"provider overloaded\"}}",null).retryable,"Provider overload wrapped in HTTP 400 retried");
        check(!ApiFailure.response(400,"{\"error\":{\"message\":\"secret-test-key\"}}","secret-test-key").getMessage().contains("secret-test-key"),"API error redacts stored key");
        var wire=OmniClient.messages("system",List.of(new Conversation.Message("assistant","старый ответ"),new Conversation.Message("assistant","прогресс"),new Conversation.Message("user","продолжай")));
        check(wire.size()==4 && wire.get(1).getAsJsonObject().get("role").getAsString().equals("user") && wire.get(2).getAsJsonObject().get("content").getAsString().contains("прогресс"),"Truncated history and consecutive progress messages form alternating Gemini turns");
        List<Conversation.Message> originalTurns=List.of(new Conversation.Message("user","спроектируй башню"));
        JsonObject goodDraft=new JsonObject();goodDraft.addProperty("action","plan");goodDraft.add("plan",cube.source);
        var repairCalls=new java.util.concurrent.atomic.AtomicInteger();var rejectionCalls=new java.util.concurrent.atomic.AtomicInteger();
        var repaired=PlanRepair.ask("system",originalTurns,(system,turns)->{
            int call=repairCalls.incrementAndGet();
            if(call==1)return invalidDraft.deepCopy();
            check(turns.size()==3 && turns.get(1).content().equals(invalidDraft.toString()) && turns.getLast().content().contains("Операция 1"),"Model receives its whole rejected draft and the precise failing operation");
            return goodDraft.deepCopy();
        },(attempt,response,reason)->{rejectionCalls.incrementAndGet();check(attempt==1 && reason.contains("0..95"),"Rejected world coordinates produce an actionable repair diagnostic");});
        check(repaired.plan().cells.equals(cube.cells) && repairCalls.get()==2 && rejectionCalls.get()==1 && originalTurns.size()==1,"Only a corrected blueprint leaves the repair loop; caller history is immutable");
        JsonObject unknownMaterial=goodDraft.deepCopy();unknownMaterial.getAsJsonObject("plan").getAsJsonArray("operations").get(0).getAsJsonObject().addProperty("block","minecraft:crimson_carpet");
        repairCalls.set(0);var materialChecks=new java.util.concurrent.atomic.AtomicInteger();
        var materialRepair=PlanRepair.ask("system",originalTurns,(system,turns)->{
            if(repairCalls.incrementAndGet()==1)return unknownMaterial.deepCopy();
            check(turns.getLast().content().contains("Неизвестный блок"),"Registry validation feeds the missing material back to the model");return goodDraft.deepCopy();
        },(attempt,response,reason)->{},p->{materialChecks.incrementAndGet();if(p.cells.containsValue("minecraft:crimson_carpet"))throw new IllegalArgumentException("Неизвестный блок: minecraft:crimson_carpet");});
        check(materialChecks.get()==2 && repairCalls.get()==2 && materialRepair.plan().cells.equals(cube.cells),"A geometrically valid plan with a missing block is repaired before it can reach construction");
        repairCalls.set(0);rejectionCalls.set(0);
        try{PlanRepair.ask("system",originalTurns,(system,turns)->{repairCalls.incrementAndGet();return invalidDraft.deepCopy();},(attempt,response,reason)->rejectionCalls.incrementAndGet());throw new AssertionError("Invalid drafts retried forever");}catch(PlanRepair.InvalidPlan expected){check(repairCalls.get()==3 && rejectionCalls.get()==3,"Model repairs are bounded to two follow-up requests");}
        repairCalls.set(0);
        var lastRejected=new java.util.concurrent.atomic.AtomicReference<JsonObject>();
        try{PlanRepair.ask("system",originalTurns,(system,turns)->{if(repairCalls.incrementAndGet()==1)return invalidDraft.deepCopy();JsonObject unsafe=new JsonObject();unsafe.addProperty("action","start");return unsafe;},(attempt,response,reason)->lastRejected.set(response));throw new AssertionError("Repair started construction");}catch(PlanRepair.InvalidPlan expected){check(repairCalls.get()==3,"A corrective model response cannot start construction or issue another action");}
        check(lastRejected.get().equals(invalidDraft),"A non-plan corrective response cannot erase the recoverable blueprint draft");
        repairCalls.set(0);JsonObject explanation=new JsonObject();explanation.addProperty("action","chat");explanation.addProperty("reply","Давай согласуем отдельные части.");
        check(PlanRepair.ask("system",originalTurns,(system,turns)->repairCalls.incrementAndGet()==1?invalidDraft.deepCopy():explanation,(attempt,response,reason)->{}).plan()==null && repairCalls.get()==2,"An oversized design can be discussed instead of silently cropped or applied");
        repairCalls.set(0);
        try{PlanRepair.ask("system",originalTurns,(system,turns)->{repairCalls.incrementAndGet();return invalidDraft.deepCopy();},(attempt,response,reason)->Thread.currentThread().interrupt());throw new AssertionError("Cancelled repair sent another request");}catch(InterruptedException expected){check(repairCalls.get()==1,"Cancelling a rejected draft prevents another provider request");}finally{Thread.interrupted();}
        ProgressWatch watch=new ProgressWatch();watch.observe(20,false,false,20);
        boolean waitingSafe=true;for(int i=0;i<300;i++)waitingSafe&=!watch.observe(20,false,true,20);check(waitingSafe,"Resource wait does not pause construction");
        boolean delaySafe=true;for(int i=0;i<9;i++)delaySafe&=!watch.observe(20,false,false,20);check(delaySafe,"Short build delay tolerated");
        check(watch.observe(20,false,false,20) && watch.recover(),"Stationary stalled build recovered after ten seconds");
        watch.recover();watch.recover();watch.recover();check(!watch.recover(),"Recovery attempts bounded");
        watch.observe(19,false,false,20);check(watch.recover(),"Actual block progress restores recovery budget");
        var searchWatch=new ProgressWatch();searchWatch.observe(20,false,false,20,true);
        boolean planningSafe=true;for(int i=0;i<89;i++)planningSafe&=!searchWatch.observe(20,false,false,20,true);
        check(planningSafe && searchWatch.observe(20,false,false,20,true),"Repeated route planning cannot reset the no-progress timeout forever");
        check(!searchWatch.observe(19,false,false,20,true),"A confirmed block resets the planning timeout");
        var oscillatingWatch=new ProgressWatch();oscillatingWatch.observe(20,false,false,20);
        boolean oscillatingStall=false;for(int i=0;i<30;i++)oscillatingStall|=oscillatingWatch.observe(i%2==0?21:20,true,false,20);
        check(oscillatingStall,"Breaking and replacing one cell cannot disguise a stall as progress");
        oscillatingWatch.recover();oscillatingWatch.observe(21,false,false,20);oscillatingWatch.observe(20,false,false,20);
        check(oscillatingWatch.retries()==1,"Returning to an old remaining count does not replenish recovery attempts");
        var start=new FlightRoute.Point(0,0,0);var goal=new FlightRoute.Point(4,0,0);
        var route=FlightRoute.find(start,goal,p->p.y()>=0 && !(p.x()==2 && p.y()<3),2000);
        check(!route.isEmpty() && route.getLast().equals(goal) && route.stream().anyMatch(p->p.y()>=3),"Creative route flies over blocking wall without scaffolding");
        var before=start;for(var p:route){check(before.distance(p)==1,"Flight never cuts solid corners");before=p;}
        check(FlightRoute.find(start,goal,p->p.equals(start),100).isEmpty(),"Unreachable flight goal bounded");
        java.util.function.ToIntFunction<FlightRoute.Point> detour=p->p.y()==0 && p.x()>=0 && p.x()<=4 && p.z()>=0 && p.z()<=5 && (p.x()!=2 || p.z()==5)?1:0;
        var longWalk=new FlightRoute.Search(start,List.of(goal),detour,2000);
        while(!longWalk.done())longWalk.advance(2000,100_000_000);
        check(longWalk.result().size()==14,"Walking fixture requires a long detour around a solid wall");
        var shortWalk=new FlightRoute.Search(start,List.of(goal),detour,2000,8);
        while(!shortWalk.done())shortWalk.advance(2000,100_000_000);
        check(shortWalk.result().isEmpty() && shortWalk.visited()<100,"Creative walking budget rejects a long detour before spending time following it");
        var roof=new FlightRoute.Point(14,30,14);var room=new FlightRoute.Point(10,1,10);
        java.util.function.Predicate<FlightRoute.Point> palaceFree=p->{
            boolean inside=p.x()>=0 && p.x()<=26 && p.z()>=0 && p.z()<=26 && p.y()>=0 && p.y()<=29;
            boolean shell=inside && (p.x()==0 || p.x()==26 || p.z()==0 || p.z()==26 || p.y()==0 || p.y()==29);
            boolean doorway=p.x()==0 && p.z()==13 && p.y()>=1 && p.y()<=2;
            return p.y()>=1 && (!shell || doorway);
        };
        var incremental=new FlightRoute.Search(roof,List.of(room),p->palaceFree.test(p)?1:0,30000);
        incremental.advance(10,100_000_000);int explored=incremental.visited();
        check(!incremental.done() && explored>0,"Large roof-to-room search yields without blocking one client tick");
        while(!incremental.done())incremental.advance(100,100_000_000);
        check(!incremental.result().isEmpty() && incremental.result().stream().anyMatch(p->p.x()==0 && p.z()==13 && p.y()<=2),"Large palace routing retains its frontier and finds the existing entrance");
        var sealed=new FlightRoute.Search(roof,List.of(room),p->palaceFree.test(p) && !(p.x()==0 && p.z()==13 && p.y()<=2)?1:41,30000);
        while(!sealed.done())sealed.advance(100,100_000_000);
        check(!sealed.result().isEmpty(),"A sealed room has a weighted route through removable masonry");
        Path passageFile=Files.createTempDirectory("dummymod-passage-test-").resolve("passage.json");
        var journal=new PassageJournal(passageFile);var brick=new PassageJournal.Entry(172,105,209,"minecraft:polished_blackstone_bricks");journal.remember(brick);
        check(new PassageJournal(passageFile).entries().equals(List.of(brick)),"Original passage blocks survive a restart before restoration");
        journal.remember(brick);check(journal.entries().size()==1,"Repeated excavation never replaces the saved original block");
        var accessBrick=new PassageJournal.Entry(172,106,209,"minecraft:stone");journal.focus(brick);journal.remember(accessBrick);
        var focusedJournal=new PassageJournal(passageFile);
        check(brick.equals(focusedJournal.focus()) && focusedJournal.entries().contains(accessBrick),"Restoration dependency survives reopening access and restarting");
        var legacyEntries=new com.google.gson.Gson().fromJson(Files.readString(passageFile),PassageJournal.Entry[].class);
        check(legacyEntries.length==2,"Focused passage journal remains readable as the legacy entry array");
        journal.restored(accessBrick);
        journal.restored(brick);check(new PassageJournal(passageFile).entries().isEmpty(),"Confirmed restoration clears the durable repair record");
        check(new PassageJournal(passageFile).focus()==null,"Confirmed focused restoration clears the saved dependency");
        Files.delete(passageFile);Files.delete(passageFile.getParent());
        Path blockedJournal=Files.createTempFile("dummymod-blocked-journal-",".tmp");var failedJournal=new PassageJournal(blockedJournal.resolve("passage.json"));
        try{failedJournal.remember(brick);throw new AssertionError("Excavation journal accepted an unwritable destination");}catch(java.io.IOException expected){check(failedJournal.entries().isEmpty(),"Failed durable save leaves no in-memory permission to excavate");}finally{Files.delete(blockedJournal);}
        check(Supply.command("Dummy_01","minecraft:stone",64).equals("give Dummy_01 minecraft:stone 64"),"Supply targets exact own account");
        try{Supply.command("@a","minecraft:stone",64);throw new AssertionError("Supply selector accepted");}catch(IllegalArgumentException expected){checks++;}
        try{Supply.command("Dummy_01","minecraft:stone;op Enemy",64);throw new AssertionError("Supply command injection accepted");}catch(IllegalArgumentException expected){checks++;}
        try{Supply.command("Dummy_01","minecraft:stone",128);throw new AssertionError("Oversized supply accepted");}catch(IllegalArgumentException expected){checks++;}
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/chat/completions",exchange->{
            String request=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            int call=calls.incrementAndGet();String body=call==1?"{\"error\":{\"message\":\"temporarily unavailable\"}}":call==2?"":"data: "+delta1+"\n\ndata: "+delta2+"\n\ndata: [DONE]\n";
            if(!JsonParser.parseString(request).getAsJsonObject().get("stream").getAsBoolean())throw new AssertionError("Streaming not requested");
            byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(call==1?503:call==2?400:200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try{check(OmniClient.ask("http://127.0.0.1:"+server.getAddress().getPort()+"/chat/completions","test-key","test-model","system",List.of(new Conversation.Message("user","hello"))).get("reply").getAsString().equals("Привет") && calls.get()==3,"Real HTTP pipeline retries 503, empty 400 and collects successful SSE");}finally{server.stop(0);}
        var generationServer=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        List<JsonObject> generationBodies=Collections.synchronizedList(new ArrayList<>());
        generationServer.createContext("/chat/completions",exchange->{
            generationBodies.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject());
            int call=generationBodies.size();String body=call==1?response(goodDraft.toString(),"length"):call==2?response("{\"action\":\"plan\",","stop"):response(goodDraft.toString(),"stop");
            byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });generationServer.start();
        try{
            JsonObject answer=OmniClient.ask("http://127.0.0.1:"+generationServer.getAddress().getPort()+"/chat/completions","test-key","agy/gemini-test","system",policyHistory);
            check(generationBodies.size()==3 && BuildPlan.parse(answer.getAsJsonObject("plan")).cells.equals(cube.cells),"Real HTTP retries truncated and malformed model answers and accepts only the final complete blueprint");
            check(generationBodies.get(0).getAsJsonObject("thinking").get("budget_tokens").getAsInt()==4096 && generationBodies.get(1).getAsJsonObject("thinking").get("budget_tokens").getAsInt()==1024 && generationBodies.get(2).getAsJsonObject("thinking").get("budget_tokens").getAsInt()==1024,"Corrective budgets reach the wire and remain bounded across repeated model failures");
        }finally{generationServer.stop(0);}
        var proxy=new DummyConfig.SocksProxy("127.0.0.1",1080,"","",true);
        EmbeddedChannel channel=new EmbeddedChannel(new ProxyBridge.Socks5ClientHandler(proxy,"test.minecraft",25565));
        ByteBuf greeting=channel.readOutbound();check(greeting.readUnsignedByte()==5,"SOCKS greeting");greeting.release();
        channel.writeOutbound(Unpooled.wrappedBuffer(new byte[]{1,2,3}));check(channel.readOutbound()==null,"Minecraft login held until SOCKS tunnel is ready");
        channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{5}));check(channel.readOutbound()==null,"Partial auth reply waits");
        channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{0}));ByteBuf connect=channel.readOutbound();check(connect.readUnsignedByte()==5 && connect.readUnsignedByte()==1,"SOCKS CONNECT requested");connect.release();
        channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{5,0,0,1,0,0,0,0,0,0}));ByteBuf login=channel.readOutbound();check(login!=null && login.readableBytes()==3 && login.readByte()==1,"Login delivered only after CONNECT success");login.release();channel.finishAndReleaseAll();
        System.out.println("Passed "+checks+" AI/proxy regression checks");
    }
}
