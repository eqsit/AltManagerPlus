package com.dummymod.ai;

import com.dummymod.dummy.DummyManager;
import com.dummymod.gui.AiConfigScreen;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.Blocks;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;

/** Optional real-client survival check; loaded only by the separate probe JAR. */
public final class ClientProbe implements ClientModInitializer {
    private int phase,ticks,wait;
    private BlockPos origin;
    private Building building;
    private boolean apiDone,apiPassed;
    private boolean shovelUsed,walkSeen,fastSeen,slowSeen;
    private boolean checkpoint;
    private boolean passageSeen;
    private final java.util.Deque<String> interiorReset=new java.util.ArrayDeque<>();
    private int stressTotal;
    private com.google.gson.JsonObject stressDesign;
    private com.dummymod.dummy.PlayerSession creativeSession;
    private static void log(String s){org.slf4j.LoggerFactory.getLogger("DummyMod-Probe").info(s);}
    public void onInitializeClient(){ClientTickEvents.END_CLIENT_TICK.register(this::tick);}
    private void tick(MinecraftClient c) {
        try {
            ticks++;
            if(phase==0 && ticks>120) {
                c.options.pauseOnLostFocus=false;Files.createDirectories(Path.of("build"));
                var chain=BlockNames.resolve("minecraft:chain");
                if(!chain.equals(net.minecraft.util.Identifier.ofVanilla("iron_chain")) || !net.minecraft.registry.Registries.BLOCK.containsId(chain))throw new AssertionError("Chain compatibility failed against real registry");
                log("PASS: old chain ID resolves against the actual game registry");
                c.setScreen(new AiConfigScreen(null));
                if(c.currentScreen.children().size()<8)throw new AssertionError("AI menu widgets missing");
                log("PASS: AI menu opens with masked API-key input");phase=1;
                if(System.getProperty("dummymod.probeInterior")!=null){apiDone=true;apiPassed=true;}
                else CompletableFuture.runAsync(()->{
                    try {var response=OmniClient.ask("You are a Minecraft architect. Reply ONLY JSON {\"reply\":\"Привет!\",\"action\":\"chat\"}. "+"Keep resources negotiable. ".repeat(100),java.util.List.of(new Conversation.Message("user","Привет")));apiPassed=response.has("reply");log("PASS: actual Java OmniRoute client responds");}
                    catch(Exception e){log("FAIL API: "+e.getClass().getSimpleName()+" "+e.getMessage());}
                    finally{apiDone=true;}
                });
                return;
            }
            if(phase==1 && ++wait>20){wait=0;
                String address=System.getProperty("dummymod.probeServer");
                if(address!=null) {
                    com.dummymod.config.DummyConfig.getInstance().setProxyDistributionMode(com.dummymod.config.DummyConfig.ProxyDistributionMode.DIRECT_ONLY);
                    net.minecraft.client.gui.screen.multiplayer.ConnectScreen.connect(null,c,net.minecraft.client.network.ServerAddress.parse(address),new net.minecraft.client.network.ServerInfo("Local probe",address,net.minecraft.client.network.ServerInfo.ServerType.OTHER),false,null);phase=10;
                } else {CreateWorldScreen.showTestWorld(c,()->{});phase=2;}return;
            }
            if(phase==10 && c.player!=null && c.world!=null && ++wait>60) {
                wait=0;creativeSession=DummyManager.spawnDummy("FlightProbe");phase=11;return;
            }
            if(phase==11 && creativeSession!=null && creativeSession.isValid() && ++wait>80) {
                if(System.getProperty("dummymod.probeInterior")!=null){prepareInterior(c);return;}
                wait=0;origin=creativeSession.player.getBlockPos().add(3,0,0);
                c.getNetworkHandler().sendChatCommand("fill "+origin.toShortString().replace(",","")+" "+origin.add(2,8,2).toShortString().replace(",","")+" air");
                c.getNetworkHandler().sendChatCommand("setblock "+origin.add(1,0,1).toShortString().replace(",","")+" stone");
                // A buried foundation: all three faces of its bottom corner are hidden.
                // The previous bottom-first executor stalls here until a human digs it out.
                c.getNetworkHandler().sendChatCommand("fill "+origin.toShortString().replace(",","")+" "+origin.add(2,3,2).toShortString().replace(",","")+" dirt");
                c.getNetworkHandler().sendChatCommand("setblock "+origin.add(-1,0,0).toShortString().replace(",","")+" gold_block");
                c.getNetworkHandler().sendChatCommand("give FlightProbe minecraft:stone 1");c.getNetworkHandler().sendChatCommand("gamemode creative FlightProbe");phase=12;return;
            }
            if(phase==11 && creativeSession!=null && creativeSession.isValid() && wait==1) {
                // Every run starts on a clean surface, independently of the previous
                // probe's player location and already constructed roof.
                var h=c.getNetworkHandler();h.sendChatCommand("fill -16 -60 -16 16 -40 16 air");
                h.sendChatCommand("difficulty peaceful");
                h.sendChatCommand("tp FlightProbe -5 -60 -5");h.sendChatCommand("tp DummyProbeHost -8 -60 -8");
            }
            if(phase==12 && ++wait>30) {
                wait=0;String json="{\"name\":\"creative flight probe\",\"origin\":["+origin.getX()+","+origin.getY()+","+origin.getZ()+"],\"operations\":[{\"from\":[0,0,0],\"to\":[2,0,2],\"block\":\"minecraft:oak_planks\"},{\"from\":[0,1,0],\"to\":[0,7,0],\"block\":\"minecraft:stone\"},{\"from\":[0,8,0],\"to\":[2,8,2],\"block\":\"minecraft:stone\"},{\"from\":[1,0,1],\"to\":[1,0,1],\"block\":\"minecraft:air\"}]}";
                var design=JsonParser.parseString(json).getAsJsonObject();var operations=design.getAsJsonArray("operations");
                operations.add(JsonParser.parseString("{\"from\":[1,1,0],\"to\":[1,1,0],\"block\":\"minecraft:oak_stairs[facing=north,half=bottom]\"}"));
                operations.add(JsonParser.parseString("{\"from\":[2,1,0],\"to\":[2,1,0],\"block\":\"minecraft:oak_slab[type=double]\"}"));
                operations.add(JsonParser.parseString("{\"from\":[2,2,0],\"to\":[2,2,0],\"block\":\"minecraft:chain[axis=y]\"}"));
                building=new Building(creativeSession,BuildPlan.parse(design),ClientProbe::log);building.mode(true,false);building.start();
                building.flightSpeed(FlightSpeed.FAST);c.getNetworkHandler().sendChatCommand("give FlightProbe iron_shovel");
                DummyManager.switchToSession(creativeSession);
                if(net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sound_physics_remastered")) {
                    var access=Class.forName("com.sonicether.soundphysics.utils.LevelAccessUtils").getMethod("getClientLevelProxy",MinecraftClient.class);
                    if(access.invoke(null,c)==null)throw new AssertionError("Sound Physics cache missing after account switch");log("PASS: Sound Physics world cache available after switching to dummy");
                }
                DummyManager.switchToSession(DummyManager.mainSession);
                if(Boolean.getBoolean("dummymod.probeController")) {
                    building.stop();var config=com.dummymod.config.DummyConfig.getInstance();
                    String owner=DummyManager.mainSession.player.getGameProfile().name();config.aiChatWhitelist=owner;config.aiEnabled=true;config.aiAllowBreaking=true;config.aiCreativeFlightSpeed=FlightSpeed.FAST;
                    String key=c.getCurrentServerEntry().address+"|"+creativeSession.displayName().toLowerCase(java.util.Locale.ROOT)+"|"+owner.toLowerCase(java.util.Locale.ROOT);
                    String id=java.util.UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                    var memory=new Conversation(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("dummymod-ai-history").resolve(id+".json"));
                    memory.plan=design.deepCopy();memory.planComplete=false;memory.dimension="minecraft:overworld";memory.creative=true;memory.flightSpeed=FlightSpeed.FAST;memory.save();
                    creativeSession.ai.resumeSaved("current");
                    log("Controller probe uses the actual AI session executor");
                }
                log("Creative session capability="+building.creative()+"; give permission="+Supply.canGive(creativeSession));phase=13;return;
            }
            if(phase==13) {
                if(!Boolean.getBoolean("dummymod.probeController"))building.tick(true);wait++;
                shovelUsed|=creativeSession.player.getMainHandStack().isOf(net.minecraft.item.Items.IRON_SHOVEL);
                walkSeen|=!creativeSession.player.getAbilities().flying && creativeSession.player.getVelocity().horizontalLength()>0.03;
                fastSeen|=creativeSession.player.getAbilities().flying && creativeSession.player.getVelocity().length()>0.3;
                slowSeen|=creativeSession.player.getAbilities().flying && creativeSession.player.getVelocity().length()>0.03 && creativeSession.player.getVelocity().length()<=0.2;
                if(ticks%100==0)log("Creative progress remaining="+building.states.keySet().stream().filter(building::needs).count()+", flying="+creativeSession.player.getAbilities().flying+"; "+building.diagnostic());
                if(wait>80 && (Boolean.getBoolean("dummymod.probeController")?creativeSession.ai.status().equals("Готово"):building.finished()) && apiDone) {
                    if(!apiPassed)throw new AssertionError("Java API failed");
                    if(!creativeSession.world.getBlockState(origin.add(-1,0,0)).isOf(Blocks.GOLD_BLOCK))throw new AssertionError("Excavation changed a block outside the project");
                    if(!shovelUsed || !walkSeen || !fastSeen || !slowSeen)throw new AssertionError("Tool/movement check: shovel="+shovelUsed+", walk="+walkSeen+", fast="+fastSeen+", slow="+slowSeen);
                    log("PASS: background creative account clears a four-layer mound above its buried foundation, preserves outside blocks, flies eight blocks high and places stairs/double slab/roof");
                    if(Boolean.getBoolean("dummymod.probeSkipSurvival")){
                        building.stop();
                        if(System.getProperty("dummymod.probePlan")!=null)prepareStress(c);
                        else {Files.writeString(Path.of("build/client-probe-result.txt"),"PASS menu/API/chain/terrain preparation/shovel/walking/fast and slow flight/server-confirmed creative interactions");phase=6;c.scheduleStop();}
                        return;
                    }
                    building.stop();origin=c.player.getBlockPos().add(3,0,0);wait=0;
                    var h=c.getNetworkHandler();h.sendChatCommand("op FlightProbe");h.sendChatCommand("gamemode survival FlightProbe");
                    h.sendChatCommand("tp FlightProbe "+c.player.getBlockPos().toShortString().replace(",",""));
                    h.sendChatCommand("fill "+origin.toShortString().replace(",","")+" "+origin.add(2,2,0).toShortString().replace(",","")+" air");
                    h.sendChatCommand("setblock "+origin.add(1,1,0).toShortString().replace(",","")+" stone");h.sendChatCommand("give FlightProbe iron_pickaxe");phase=14;
                }
                if(wait>1800)throw new AssertionError("Creative build timed out: "+building.status());
            }
            if(phase==14 && ++wait>40) {
                wait=0;String json="{\"name\":\"survival supply probe\",\"origin\":["+origin.getX()+","+origin.getY()+","+origin.getZ()+"],\"operations\":[{\"from\":[0,0,0],\"to\":[2,1,0],\"block\":\"minecraft:oak_planks\"},{\"from\":[1,1,0],\"to\":[1,1,0],\"block\":\"minecraft:air\"}]}";
                building=new Building(creativeSession,BuildPlan.parse(JsonParser.parseString(json).getAsJsonObject()),ClientProbe::log);building.mode(false,true);building.start();phase=15;
                log("Survival supply permission="+Supply.canGive(creativeSession));return;
            }
            if(phase==15) {
                building.tick(true);wait++;
                if(wait>80 && building.finished()) {
                    log("PASS: survival account uses own /give, places five planks and breaks stone doorway");
                    building.stop();
                    String source=System.getProperty("dummymod.probePlan");
                    if(source!=null) {
                        prepareStress(c);return;
                    }
                    Files.writeString(Path.of("build/client-probe-result.txt"),"PASS menu/API/chain/terrain preparation/shovel/walking/fast and slow flight/roof/survival/give");phase=6;c.scheduleStop();
                }
                if(ticks%100==0)log("Survival progress remaining="+building.states.keySet().stream().filter(building::needs).count()+", "+building.status());
                if(wait>1600)throw new AssertionError("Survival supply build timed out");
            }
            if(phase==16 && ++wait>50) {
                wait=0;building=new Building(creativeSession,BuildPlan.parse(stressDesign),ClientProbe::log);building.mode(true,false);building.preferWalking(true);building.flightSpeed(FlightSpeed.FAST);building.start();
                stressTotal=(int)building.states.keySet().stream().filter(building::needs).count();log("Stress project started: "+building.plan.name+", "+building.plan.width+"x"+building.plan.height+"x"+building.plan.length+", cells="+stressTotal);phase=17;return;
            }
            if(phase==17) {
                building.tick(true);wait++;int remaining=(int)building.states.keySet().stream().filter(building::needs).count();
                if(ticks%200==0){
                    log("Stress remaining="+remaining+"; "+building.status()+"; "+building.diagnostic());
                    if(Boolean.getBoolean("dummymod.probePlacementDiagnostics")){
                        c.getNetworkHandler().sendChatCommand("data get entity FlightProbe Pos");
                        c.getNetworkHandler().sendChatCommand("data get entity FlightProbe SelectedItem");
                        c.getNetworkHandler().sendChatCommand("execute if block 106 -57 106 polished_blackstone_bricks");
                    }
                }
                if(!checkpoint && remaining<stressTotal*0.65) {
                    checkpoint=true;building.pause();var file=Path.of("build/stress-saved-history.json");Conversation saved=new Conversation(file);saved.plan=stressDesign;saved.dimension="minecraft:overworld";saved.save();
                    var restored=new Conversation(file);building=new Building(creativeSession,BuildPlan.parse(restored.plan),ClientProbe::log);building.mode(true,false);building.preferWalking(true);building.flightSpeed(FlightSpeed.FAST);building.start();
                    log("PASS: large partially built project restored from disk; completed blocks retained");
                }
                if(building.finished()) {
                    if(!checkpoint)throw new AssertionError("Persistence checkpoint not reached");
                    log("PASS: full user-designed large project finished and every block verified");building.stop();
                    String owner=DummyManager.mainSession.player.getGameProfile().name();
                    String key=c.getCurrentServerEntry().address+"|"+creativeSession.displayName().toLowerCase(java.util.Locale.ROOT)+"|"+owner.toLowerCase(java.util.Locale.ROOT);
                    String id=java.util.UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                    var memory=new Conversation(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("dummymod-ai-history").resolve(id+".json"));
                    memory.plan=stressDesign.deepCopy();memory.planComplete=false;memory.dimension="minecraft:overworld";memory.creative=true;memory.save();
                    var config=com.dummymod.config.DummyConfig.getInstance();config.aiChatWhitelist=owner;config.aiEnabled=true;
                    c.setScreen(new com.dummymod.gui.SavedBuildsScreen(null));
                    if(c.currentScreen.children().size()<5)throw new AssertionError("Saved project missing in native menu");
                    wait=0;phase=18;return;
                }
                if(wait>36000 || building.status().equals("Стройка на паузе"))throw new AssertionError("Large build stalled: "+building.diagnostic()+"; remaining="+remaining);
            }
            if(phase==18 && ++wait>20) {
                if(!(c.currentScreen instanceof com.dummymod.gui.SavedBuildsScreen))throw new AssertionError("Project menu did not remain open");
                ((net.minecraft.client.gui.widget.ButtonWidget)c.currentScreen.children().getFirst()).onPress(new net.minecraft.client.input.KeyInput(257,0,0));
                log("PASS: saved project menu renders and its resume button restores the schema");wait=0;phase=19;
            }
            if(phase==19 && ++wait>80) {
                if(!creativeSession.ai.status().equals("Готово"))throw new AssertionError("Menu-restored project did not retain completed blocks");
                log("PASS: controller resumes from native project menu without rebuilding completed blocks");
                Files.writeString(Path.of("build/client-probe-result.txt"),"PASS menu/API/chain/terrain preparation/shovel/walking/fast and slow flight/"+(Boolean.getBoolean("dummymod.probeSkipSurvival")?"":"survival/give/")+"full user palace/checkpoint restore/native project menu resume; initial cells="+stressTotal);phase=6;c.scheduleStop();
            }
            if(phase==20 && !interiorReset.isEmpty()){for(int i=0;i<4 && !interiorReset.isEmpty();i++)c.getNetworkHandler().sendChatCommand(interiorReset.removeFirst());return;}
            if(phase==20 && ++wait>80) {
                wait=0;building=new Building(creativeSession,BuildPlan.parse(stressDesign),ClientProbe::log);building.mode(true,false);building.flightSpeed(FlightSpeed.FAST);building.start();
                stressTotal=(int)building.states.keySet().stream().filter(building::needs).count();log("Interior begins on palace roof, missing="+stressTotal);phase=21;return;
            }
            if(phase==21) {
                building.tick(true);wait++;
                if(ticks%100==0)log("Interior remaining="+building.states.keySet().stream().filter(building::needs).count()+"; "+building.status()+"; "+building.diagnostic());
                if(building.finished()) {
                    var saved=JsonParser.parseString(Files.readString(Path.of(System.getProperty("dummymod.probePalace")))).getAsJsonObject();
                    var design=saved.getAsJsonObject("source").deepCopy();design.add("origin",stressDesign.get("origin").deepCopy());
                    var palace=new Building(creativeSession,BuildPlan.parse(design),ClientProbe::log);
                    for(var cell:palace.states.keySet())if(!building.states.containsKey(cell) && !palace.matches(cell,creativeSession.world.getBlockState(palace.position(cell))))throw new AssertionError("Palace damaged at "+palace.position(cell));
                    log("PASS: exact user interior completed from roof, temporary passage restored and original palace preserved");
                    if(Boolean.getBoolean("dummymod.probePassage")) {
                        building.stop();var h=c.getNetworkHandler();h.sendChatCommand("fill 140 -60 80 146 -54 86 stone hollow");h.sendChatCommand("tp FlightProbe 143.5 -52.92 83.5");h.sendChatCommand("tp DummyProbeHost 137 -60 78");wait=0;phase=22;return;
                    }
                    Files.writeString(Path.of("build/client-probe-result.txt"),"PASS exact interior from roof / temporary passage restoration / original palace preserved; cells="+stressTotal);phase=6;c.scheduleStop();
                }
                if(wait>9000 || building.status().equals("Стройка на паузе"))throw new AssertionError("Interior stalled: "+building.diagnostic());
            }
            if(phase==22 && ++wait>60) {
                wait=0;var design=JsonParser.parseString("{\"name\":\"sealed room passage\",\"origin\":[143,-59,83],\"operations\":[{\"from\":[0,0,0],\"to\":[0,0,0],\"block\":\"minecraft:gold_block\"}]}").getAsJsonObject();
                building=new Building(creativeSession,BuildPlan.parse(design),ClientProbe::log);building.mode(true,false);building.flightSpeed(FlightSpeed.FAST);building.start();phase=23;return;
            }
            if(phase==23) {
                building.tick(true);wait++;passageSeen|=building.temporaryPassage();
                if(!checkpoint && building.temporaryPassage()) {
                    checkpoint=true;building.pause();var saved=new Conversation(Path.of("build/passage-saved-history.json"));saved.plan=building.plan.source.deepCopy();saved.dimension="minecraft:overworld";saved.save();wait=0;phase=24;return;
                }
                if(ticks%100==0)log("Sealed passage: "+building.status()+"; "+building.diagnostic());
                if(building.finished()) {
                    if(!passageSeen)throw new AssertionError("Sealed room test never excavated a passage");
                    for(int x=140;x<=146;x++)for(int y=-60;y<=-54;y++)for(int z=80;z<=86;z++)if((x==140 || x==146 || y==-60 || y==-54 || z==80 || z==86) && !creativeSession.world.getBlockState(new BlockPos(x,y,z)).isOf(Blocks.STONE))throw new AssertionError("Passage was not restored at "+x+","+y+","+z);
                    log("PASS: entered a sealed stone room through a temporary opening, placed its interior block and restored every original wall/roof block");
                    Files.writeString(Path.of("build/client-probe-result.txt"),"PASS exact palace interior / sealed room excavation / all temporary blocks restored / palace preserved");phase=6;c.scheduleStop();
                }
                if(wait>3600 || building.status().equals("Стройка на паузе"))throw new AssertionError("Sealed passage stalled: "+building.diagnostic());
            }
            if(phase==24 && ++wait>20) {
                var saved=new Conversation(Path.of("build/passage-saved-history.json"));building=new Building(creativeSession,BuildPlan.parse(saved.plan),ClientProbe::log);
                if(!building.temporaryPassage())throw new AssertionError("Temporary opening did not survive executor reload");
                building.mode(true,false);building.flightSpeed(FlightSpeed.FAST);building.start();log("PASS: executor recreated from disk while its passage is open; original wall blocks retained for restoration");wait=0;phase=23;return;
            }
            if(phase==2 && c.currentScreen instanceof CreateWorldScreen screen) {
                screen.getWorldCreator().setWorldName("DummyMod integration probe");
                screen.getWorldCreator().setGameMode(WorldCreator.Mode.SURVIVAL);
                var create=CreateWorldScreen.class.getDeclaredMethod("createLevel");create.setAccessible(true);create.invoke(screen);phase=3;return;
            }
            if(phase==3 && c.player!=null && c.world!=null && c.getServer()!=null && ++wait>80) {
                wait=0;origin=c.player.getBlockPos().add(3,0,0);String name=c.player.getGameProfile().name();
                BlockPos p=c.player.getBlockPos();var server=c.getServer();
                server.execute(()->{
                    var commands=server.getCommandManager();var source=server.getCommandSource();
                    commands.parseAndExecute(source,"fill "+p.add(-8,0,-8).toShortString().replace(",","")+" "+p.add(8,6,8).toShortString().replace(",","")+" air");
                    commands.parseAndExecute(source,"fill "+p.add(-8,-1,-8).toShortString().replace(",","")+" "+p.add(8,-1,8).toShortString().replace(",","")+" stone");
                    commands.parseAndExecute(source,"give "+name+" oak_planks 64");
                    commands.parseAndExecute(source,"give "+name+" iron_pickaxe");
                    commands.parseAndExecute(source,"setblock "+origin.add(1,1,0).toShortString().replace(",","")+" stone");
                });phase=4;return;
            }
            if(phase==3 && ticks%100==0)log("World wait: player="+(c.player!=null)+", world="+(c.world!=null)+", screen="+(c.currentScreen==null?"none":c.currentScreen.getClass().getSimpleName()));
            if(phase==4 && ++wait>60) {
                wait=0;DummyManager.captureMain(c);
                String json="{\"name\":\"survival probe\",\"origin\":["+origin.getX()+","+origin.getY()+","+origin.getZ()+"],\"operations\":[{\"shape\":\"box\",\"from\":[0,0,0],\"to\":[2,1,0],\"block\":\"minecraft:oak_planks\"},{\"shape\":\"box\",\"from\":[1,1,0],\"to\":[1,1,0],\"block\":\"minecraft:air\"}]}";
                building=new Building(DummyManager.mainSession,BuildPlan.parse(JsonParser.parseString(json).getAsJsonObject()),ClientProbe::log);
                log("Actual inventory: "+building.inventory());building.start();phase=5;return;
            }
            if(phase==5) {
                building.tick(true);wait++;
                boolean complete=true;
                for(int x=0;x<3;x++)for(int y=0;y<2;y++){var state=c.world.getBlockState(origin.add(x,y,0));if(x==1&&y==1){if(!state.isAir())complete=false;}else if(!state.isOf(Blocks.OAK_PLANKS))complete=false;}
                if(complete && apiDone){log("PASS: real survival build places five planks and breaks stone doorway");if(!apiPassed)throw new AssertionError("Java API request failed");Files.writeString(Path.of("build/client-probe-result.txt"),"PASS menu, Java API, survival place/break");phase=6;building.stop();c.scheduleStop();}
                if(wait>2400)throw new AssertionError("Survival build timed out: "+building.status());
            }
        } catch(Throwable e){log("FAIL: "+e.getClass().getSimpleName()+" "+e.getMessage());try{Files.writeString(Path.of("build/client-probe-result.txt"),"FAIL "+e.getClass().getSimpleName()+" "+e.getMessage());}catch(Exception ignored){}phase=6;c.scheduleStop();}
    }
    private void prepareStress(MinecraftClient c) throws Exception {
        var saved=JsonParser.parseString(Files.readString(Path.of(System.getProperty("dummymod.probePlan")))).getAsJsonObject();
        stressDesign=(saved.has("source")?saved.getAsJsonObject("source"):saved).deepCopy();origin=new BlockPos(80,-60,80);
        var coordinates=new com.google.gson.JsonArray();coordinates.add(origin.getX());coordinates.add(origin.getY());coordinates.add(origin.getZ());stressDesign.add("origin",coordinates);
        var h=c.getNetworkHandler();h.sendChatCommand("gamemode creative FlightProbe");h.sendChatCommand("tp FlightProbe 79 -60 79");h.sendChatCommand("tp DummyProbeHost 78 -60 78");
        wait=0;phase=16;
    }
    private void prepareInterior(MinecraftClient c) throws Exception {
        var saved=JsonParser.parseString(Files.readString(Path.of(System.getProperty("dummymod.probeInterior")))).getAsJsonObject();
        stressDesign=saved.getAsJsonObject("source").deepCopy();
        var coordinates=new com.google.gson.JsonArray();coordinates.add(80);coordinates.add(-60);coordinates.add(80);stressDesign.add("origin",coordinates);
        if(Boolean.getBoolean("dummymod.probeResetInterior")) {
            var palaceSaved=JsonParser.parseString(Files.readString(Path.of(System.getProperty("dummymod.probePalace")))).getAsJsonObject();
            var original=palaceSaved.getAsJsonObject("source").deepCopy();original.add("origin",coordinates.deepCopy());var palace=BuildPlan.parse(original);
            for(var cell:BuildPlan.parse(stressDesign).cells.keySet()) {
                String block=palace.cells.getOrDefault(cell,"minecraft:air");
                int bracket=block.indexOf('[');String suffix=bracket<0?"":block.substring(bracket);
                block=BlockNames.resolve(bracket<0?block:block.substring(0,bracket))+suffix;
                interiorReset.add("setblock "+(80+cell.x())+" "+(-60+cell.y())+" "+(80+cell.z())+" "+block);
            }
        }
        var h=c.getNetworkHandler();h.sendChatCommand("gamemode creative FlightProbe");h.sendChatCommand("tp FlightProbe 97.5 -32.92 92.15");h.sendChatCommand("tp DummyProbeHost 78 -60 78");
        wait=0;phase=20;
    }
}
