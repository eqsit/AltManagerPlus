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
        rejects("[{\"from\":[3,0,0],\"to\":[1,0,0],\"block\":\"stone\"}]");
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
        imported.clear();var cleared=new Conversation(file);
        check(cleared.messages().isEmpty(),"Clear persists");
        check(cleared.plan.equals(cube.source) && cleared.projects().size()==3,"Clearing chat preserves current and earlier project schemas");
        try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
        long[] loads={0,0,0};for(int i=0;i<10;i++)loads[ProxyBalance.choose(loads,i)]++;
        check(Arrays.stream(loads).max().orElseThrow()-Arrays.stream(loads).min().orElseThrow()<=1,"Even account allocation");
        loads[1]=0;check(ProxyBalance.choose(loads,0)==1,"Vacated proxy reused first");
        check(ProxyBalance.choose(new long[]{0,0,0},Integer.MAX_VALUE)>=0,"Cursor overflow safe");
        String delta1=new Gson().toJson(Map.of("choices",List.of(Map.of("delta",Map.of("content","{\"action\":\"chat\",")))));
        String delta2=new Gson().toJson(Map.of("choices",List.of(Map.of("delta",Map.of("content","\"reply\":\"Привет\"}")))));
        check(ModelResponse.parse("data: "+delta1+"\n\ndata: "+delta2+"\n\ndata: [DONE]\n").get("reply").getAsString().equals("Привет"),"Collect split streaming JSON safely");
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
        ProgressWatch watch=new ProgressWatch();watch.observe(20,false,false,20);
        boolean waitingSafe=true;for(int i=0;i<300;i++)waitingSafe&=!watch.observe(20,false,true,20);check(waitingSafe,"Resource wait does not pause construction");
        boolean delaySafe=true;for(int i=0;i<9;i++)delaySafe&=!watch.observe(20,false,false,20);check(delaySafe,"Short build delay tolerated");
        check(watch.observe(20,false,false,20) && watch.recover(),"Stationary stalled build recovered after ten seconds");
        watch.recover();watch.recover();watch.recover();check(!watch.recover(),"Recovery attempts bounded");
        watch.observe(19,false,false,20);check(watch.recover(),"Actual block progress restores recovery budget");
        var start=new FlightRoute.Point(0,0,0);var goal=new FlightRoute.Point(4,0,0);
        var route=FlightRoute.find(start,goal,p->p.y()>=0 && !(p.x()==2 && p.y()<3),2000);
        check(!route.isEmpty() && route.getLast().equals(goal) && route.stream().anyMatch(p->p.y()>=3),"Creative route flies over blocking wall without scaffolding");
        var before=start;for(var p:route){check(before.distance(p)==1,"Flight never cuts solid corners");before=p;}
        check(FlightRoute.find(start,goal,p->p.equals(start),100).isEmpty(),"Unreachable flight goal bounded");
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
        journal.restored(brick);check(new PassageJournal(passageFile).entries().isEmpty(),"Confirmed restoration clears the durable repair record");
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
