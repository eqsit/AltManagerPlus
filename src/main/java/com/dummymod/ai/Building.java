package com.dummymod.ai;

import baritone.api.IBaritone;
import baritone.api.schematic.ISchematic;
import baritone.api.pathing.goals.GoalNear;
import com.dummymod.dummy.PlayerSession;
import com.dummymod.dummy.baritone.BaritoneBridge;
import net.minecraft.block.*;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;
import net.minecraft.entity.ItemEntity;
import java.util.*;
import java.util.function.Consumer;

/** Verified construction with separate survival and creative executors. */
public final class Building {
    public final BuildPlan plan;
    private final PlayerSession session;
    final Map<BuildPlan.Cell,BlockState> states=new LinkedHashMap<>();
    final Map<BuildPlan.Cell,Map<String,String>> properties=new HashMap<>();
    private Map<BuildPlan.Cell,BlockState> stage=Map.of();
    private boolean approved,paused,running,finished;
    private int ticks,recoveryUntil,settleChecks;
    private boolean creativeRequested,autoGive,usingCreative,clearing;
    private boolean terrainPrepared;
    private final Set<BuildPlan.Cell> preparation=new HashSet<>();
    private final CreativeBuilder flight;
    private final ProgressWatch watch=new ProgressWatch();
    private Vec3d lastPosition;
    private final SupplyWatch<Item> giveWatch=new SupplyWatch<>();
    private String lastNeed="";
    private long lastPickup,lastNotice;
    private final Consumer<String> say;
    private record Material(BlockState state,Map<String,String> properties) { }
    public Building(PlayerSession session,BuildPlan plan,Consumer<String> say) {
        this.session=session;this.plan=plan;this.say=say;
        flight=new CreativeBuilder(session,this);
        if(session.world==null || plan.origin[1]<session.world.getBottomY() || plan.origin[1]+plan.height-1>session.world.getTopYInclusive())throw new IllegalArgumentException("Проект за пределами высоты мира");
        Map<String,Material> materials=new HashMap<>();
        for(var e:plan.cells.entrySet()) {
            Material material=materials.computeIfAbsent(e.getValue(),Building::material);
            states.put(e.getKey(),material.state());properties.put(e.getKey(),material.properties());
        }
    }
    /** Read only the frozen block registry; safe for the background blueprint check. */
    static void validateBlocks(BuildPlan plan) {
        for(String spec:new HashSet<>(plan.cells.values()))material(spec);
    }
    private static Material material(String spec) {
        int bracket=spec.indexOf('[');
        Identifier id=BlockNames.resolve(bracket<0?spec:spec.substring(0,bracket));
        if(id==null || !Registries.BLOCK.containsId(id))throw new IllegalArgumentException("Неизвестный блок: "+spec);
        Block block=Registries.BLOCK.get(id);BlockState state=block.getDefaultState();Map<String,String> props=new HashMap<>();
        if(bracket>=0)for(String pair:spec.substring(bracket+1,spec.length()-1).split(",")) {
            String[] kv=pair.split("=",2);if(kv.length!=2)throw new IllegalArgumentException("Неверные свойства блока");
            Property<?> p=block.getStateManager().getProperty(kv[0]);
            if(p==null)throw new IllegalArgumentException("Нет свойства "+kv[0]+" у "+id);
            state=with(state,p,kv[1]);props.put(kv[0],kv[1]);
        }
        if(!state.isAir() && block.asItem()==Items.AIR)throw new IllegalArgumentException("Нельзя установить вручную: "+id+". Нужен проект из размещаемых блоков");
        return new Material(state,Map.copyOf(props));
    }
    private static <T extends Comparable<T>> BlockState with(BlockState state,Property<T> prop,String value) {
        return state.with(prop,prop.parse(value).orElseThrow(()->new IllegalArgumentException("Неверное значение "+prop.getName()+"="+value)));
    }
    BlockPos position(BuildPlan.Cell c){return new BlockPos(plan.origin[0]+c.x(),plan.origin[1]+c.y(),plan.origin[2]+c.z());}
    boolean loaded(BuildPlan.Cell c){BlockPos p=position(c);return session.world.isChunkLoaded(p.getX()>>4,p.getZ()>>4);}
    boolean matches(BuildPlan.Cell c,BlockState current) {
        return matchesExcept(c,current,null);
    }
    private boolean matchesExcept(BuildPlan.Cell c,BlockState current,String except) {
        BlockState desired=states.get(c);
        if(desired.isAir())return current.isAir();
        if(current.getBlock()!=desired.getBlock())return false;
        for(var p:properties.get(c).entrySet()) {
            if(p.getKey().equals(except))continue;
            Property<?> property=current.getBlock().getStateManager().getProperty(p.getKey());
            if(!propertyValue(current,property).equals(p.getValue()))return false;
        }
        return true;
    }
    boolean adjustsLight(BuildPlan.Cell c,BlockState current) {
        var block=current.getBlock();
        return (block instanceof CandleBlock || block instanceof CandleCakeBlock || block instanceof CampfireBlock) && properties.get(c).containsKey("lit") && matchesExcept(c,current,"lit") && !matches(c,current);
    }
    boolean placementMatches(BuildPlan.Cell c,BlockState predicted) {
        if(predicted==null)return false;
        if(matches(c,predicted) || adjustsLight(c,predicted))return true;
        // The first half of a double slab is an intentional intermediate state.
        return predicted.getBlock() instanceof SlabBlock && "double".equals(properties.get(c).get("type")) && matchesExcept(c,predicted,"type");
    }
    private static <T extends Comparable<T>> String propertyValue(BlockState state,Property<T> prop){return prop.name(state.get(prop));}
    boolean needs(BuildPlan.Cell c){return !loaded(c) || flight.awaiting(position(c)) || !matches(c,session.world.getBlockState(position(c)));}
    boolean obstructed(BuildPlan.Cell c) {
        if(!loaded(c))return false;
        BlockState current=session.world.getBlockState(position(c)),desired=states.get(c);
        if(matches(c,current) || current.isAir())return false;
        if(adjustsLight(c,current))return false;
        // A single slab is completed by placing its second half, without demolishing it.
        if(desired.getBlock()==current.getBlock() && "double".equals(properties.get(c).get("type")))return false;
        return desired.isAir() || !current.isReplaceable();
    }
    private boolean stagePending(BuildPlan.Cell c){return clearing?obstructed(c):needs(c);}
    private Map<Item,Integer> resources(Map<BuildPlan.Cell,BlockState> cells) {
        Map<Item,Integer> out=new LinkedHashMap<>();
        for(var e:cells.entrySet()) {
            if(!needs(e.getKey()) || e.getValue().isAir())continue;
            Map<String,String> props=properties.get(e.getKey());
            if("upper".equals(props.get("half")) || "head".equals(props.get("part")))continue;
            Item item=e.getValue().getBlock().asItem();
            out.merge(item,"double".equals(props.get("type"))?2:1,Integer::sum);
        }
        return out;
    }
    public Map<String,Integer> inventory() {
        Map<String,Integer> out=new TreeMap<>();
        if(session.player==null)return out;
        for(int i=0;i<36;i++){ItemStack s=session.player.getInventory().getStack(i);if(!s.isEmpty())out.merge(Registries.ITEM.getId(s.getItem()).toString(),s.getCount(),Integer::sum);}
        return out;
    }
    private Map<Item,Integer> missing(Map<BuildPlan.Cell,BlockState> cells) {
        Map<String,Integer> have=inventory();Map<Item,Integer> out=resources(cells);
        out.replaceAll((item,n)->Math.max(0,n-have.getOrDefault(Registries.ITEM.getId(item).toString(),0)));out.entrySet().removeIf(e->e.getValue()==0);return out;
    }
    private String format(Map<Item,Integer> counts) {
        List<String> parts=new ArrayList<>();counts.forEach((item,n)->parts.add(item.getName().getString()+" ×"+n));return String.join(", ",parts);
    }
    public String description() {
        Map<Item,Integer> total=resources(states);
        return "Проект «"+plan.name+"», "+plan.width+"×"+plan.height+"×"+plan.length+", точка "+plan.origin[0]+" "+plan.origin[1]+" "+plan.origin[2]+". Материалы: "+(total.isEmpty()?"не нужны":format(total))+". Можно заменить материалы или уменьшить проект. Когда согласуем — напиши «строй». "+(creative()?"В креативе летаю и беру блоки из творческого инвентаря.":"Ресурсы можно передавать частями, бросая рядом со мной.");
    }
    public String snapshot() {
        return status()+"; creative="+creative()+"; auto_give="+autoGive+"; plan="+plan.source+"; remaining_materials="+format(resources(states))+"; missing_next_batch="+(creative()?"не нужны":format(missing(stage)));
    }
    public String status(){return finished?"Готово":paused?"Стройка на паузе":running?"Строю «"+plan.name+"»":approved?"Жду ресурсы": "Проект на согласовании";}
    public void start(){approved=true;paused=false;finished=false;settleChecks=0;lastNeed="";watch.reset();recoveryUntil=0;terrainPrepared=false;stage=Map.of();flight.retry();}
    public void flightSpeed(FlightSpeed speed){flight.speed(speed);}
    public void preferWalking(boolean value){flight.preferWalking(value);}
    public void mode(boolean creativeRequested,boolean autoGive) {
        boolean change=this.creativeRequested!=creativeRequested;this.creativeRequested=creativeRequested;this.autoGive=autoGive;
        if(change){flight.release();running=false;watch.reset();BaritoneBridge.execute(session,"stop");}
    }
    public boolean creative(){return creativeRequested && CreativeBuilder.available(session);}
    public boolean controlsInput(){return approved && !paused && !finished && (running || flight.controls() || recoveryUntil>ticks);}
    public void pause(){paused=true;flight.release();IBaritone b=BaritoneBridge.resolve(session);if(b!=null){b.getBuilderProcess().onLostControl();b.getPathingBehavior().cancelEverything();b.getInputOverrideHandler().clearAllKeys();}running=false;recoveryUntil=0;}
    public void stop(){pause();approved=false;}
    public boolean approved(){return approved;}
    public boolean finished(){return finished;}
    public String diagnostic(){return creative()?flight.diagnostic():status();}
    boolean temporaryPassage(){return flight.repairing();}
    void navigationFailure(String reason){pause();say.accept(reason+" Проект сохранён.");}
    public void tick(boolean allowBreaking) {
        if(!approved || paused || finished || session.world==null || session.player==null)return;
        ticks++;
        if(autoGive)giveWatch.observe(this::inventoryCount);
        if(usingCreative!=creative()) {usingCreative=creative();flight.release();BaritoneBridge.execute(session,"stop");running=false;stage=Map.of();clearing=false;watch.reset();terrainPrepared=false;if(!usingCreative){preparation.forEach(c->{states.remove(c);properties.remove(c);});preparation.clear();}}
        if(creative() && allowBreaking && !terrainPrepared){prepareTerrain();terrainPrepared=true;}
        if(creative() && (!stage.isEmpty() || flight.repairing()))flight.tick(stage,allowBreaking,clearing);
        else if(flight.controls()){flight.release();running=false;}
        if(ticks%20!=0)return;
        List<BuildPlan.Cell> remaining=states.keySet().stream().filter(this::needs).toList();
        if(remaining.isEmpty() && !flight.repairing()) {
            if(++settleChecks<3)return;
            finished=true;running=false;flight.release();BaritoneBridge.execute(session,"stop");say.accept("Готово: «"+plan.name+"». Проверила все блоки проекта в мире.");return;
        }
        settleChecks=0;
        IBaritone b=BaritoneBridge.resolve(session);if(b==null){pause();say.accept("Baritone недоступен для этой дамми.");return;}
        // A server update or another player may add an obstruction after the
        // placement batch was selected. Re-enter clearing before it seals access
        // to a lower cell; waiting for this unfinished batch would never do so.
        if(creative() && !clearing && remaining.stream().anyMatch(this::obstructed)) {
            running=false;stage=Map.of();flight.retry();b.getBuilderProcess().onLostControl();b.getPathingBehavior().cancelEverything();
        }
        if(!stage.isEmpty() && stage.keySet().stream().noneMatch(this::stagePending)) {
            running=false;flight.retry();b.getBuilderProcess().onLostControl();b.getPathingBehavior().cancelEverything();stage=Map.of();
        }
        if(stage.isEmpty()) {
            Map<BuildPlan.Cell,BlockState> batch=new LinkedHashMap<>();Set<Item> kinds=new HashSet<>();
            Map<String,Integer> budget=inventory();
            // Buried foundation blocks have no exposed faces. Clear approved cells from
            // the surface down before asking the placement executor to reach the floor.
            List<BuildPlan.Cell> obstructions=creative()?remaining.stream().filter(this::obstructed).sorted(Comparator.comparingInt(BuildPlan.Cell::y).reversed().thenComparingInt(BuildPlan.Cell::x).thenComparingInt(BuildPlan.Cell::z)).toList():List.of();
            clearing=!obstructions.isEmpty();
            List<BuildPlan.Cell> work=clearing?obstructions:remaining;
            for(BuildPlan.Cell c:work) {
                Item item=states.get(c).getBlock().asItem();
                if(!creative() && !clearing && !kinds.contains(item) && kinds.size()>=8)break;
                Map<String,String> props=properties.get(c);
                int cost=states.get(c).isAir() || "upper".equals(props.get("half")) || "head".equals(props.get("part"))?0:"double".equals(props.get("type"))?2:1;
                String id=Registries.ITEM.getId(item).toString();int available=budget.getOrDefault(id,0);
                if(!creative() && !autoGive && cost>available && !batch.isEmpty())break;
                budget.put(id,available-cost);
                batch.put(c,states.get(c));kinds.add(item);if(batch.size()>=(creative()?512:watch.retries()>0?32:128))break;
            }
            stage=batch;
        }
        for(BuildPlan.Cell c:stage.keySet()) {
            if(!loaded(c)) {
                if(checkProgress(remaining,b,false))return;
                if(System.currentTimeMillis()-lastNotice>30000){say.accept("Иду к месту строительства, чтобы загрузить чанки.");lastNotice=System.currentTimeMillis();}
                if(!b.getPathingBehavior().isPathing())b.getCustomGoalProcess().setGoalAndPath(new GoalNear(position(c),4));
                return;
            }
            if(!allowBreaking && obstructed(c)) {pause();say.accept("На месте есть блоки, а ломание выключено в меню ИИ. Освободи площадку или включи его.");return;}
        }
        if(creative()) {
            if(b.getBuilderProcess().isActive())BaritoneBridge.execute(session,"stop");
            running=true;checkProgress(remaining,b,true);return;
        }
        Map<Item,Integer> need=missing(stage);
        if(!need.isEmpty()) {
            if(running){b.getBuilderProcess().onLostControl();b.getPathingBehavior().cancelEverything();running=false;}
            watch.observe(remaining.size(),false,true,20);
            tryGive(need);
            String text=format(need);
            if(!text.equals(lastNeed) && System.currentTimeMillis()-lastNotice>5000){say.accept("Для следующей части не хватает: "+text+". Можешь бросить рядом или обсудим замену.");lastNeed=text;lastNotice=System.currentTimeMillis();}
            if(System.currentTimeMillis()-lastPickup>5000 && !b.getPathingBehavior().isPathing()) {
                lastPickup=System.currentTimeMillis();
                session.world.getEntitiesByClass(ItemEntity.class,session.player.getBoundingBox().expand(10),e->need.containsKey(e.getStack().getItem())).stream().min(Comparator.comparingDouble(e->e.squaredDistanceTo(session.player))).ifPresent(e->b.getCustomGoalProcess().setGoalAndPath(new GoalNear(e.getBlockPos(),1)));
            }
            return;
        }
        if(checkProgress(remaining,b,false))return;
        if(recoveryUntil>ticks && b.getPathingBehavior().isPathing())return;
        recoveryUntil=0;
        if(running && b.getBuilderProcess().isActive() && !b.getBuilderProcess().isPaused())return;
        lastNeed="";b.getPathingBehavior().cancelEverything();
        Map<BuildPlan.Cell,BlockState> batch=stage;
        b.getBuilderProcess().build(plan.name,new ISchematic(){
            public int widthX(){return plan.width;} public int heightY(){return plan.height;} public int lengthZ(){return plan.length;}
            public boolean inSchematic(int x,int y,int z,BlockState current){return batch.containsKey(new BuildPlan.Cell(x,y,z));}
            public BlockState desiredState(int x,int y,int z,BlockState current,List<BlockState> placeable) {
                BuildPlan.Cell c=new BuildPlan.Cell(x,y,z);BlockState target=batch.get(c);
                if(target==null)return current==null?Blocks.AIR.getDefaultState():current;
                if(current!=null && matches(c,current))return current;
                for(BlockState candidate:placeable)if(matches(c,candidate))return candidate;
                return target;
            }
        },new BlockPos(plan.origin[0],plan.origin[1],plan.origin[2]));running=true;
    }
    private void prepareTerrain() {
        Set<Long> columns=new HashSet<>();int count=0;
        // A model may describe only the floor in a column. Expose a buried cell
        // through bounded natural terrain above it; never clear unrelated masonry.
        for(BuildPlan.Cell c:List.copyOf(states.keySet())) {
            if(preparation.contains(c) || !obstructed(c) || !natural(session.world.getBlockState(position(c))))continue;
            long column=((long)c.x()<<32)|(c.z()&0xffffffffL);
            if(!columns.add(column))continue;
            for(int y=c.y()+1;y<=Math.min(c.y()+16,95);y++) {
                BuildPlan.Cell above=new BuildPlan.Cell(c.x(),y,c.z());BlockPos pos=position(above);
                if(pos.getY()>session.world.getTopYInclusive() || !loaded(above))break;
                BlockState current=session.world.getBlockState(pos);
                if(!natural(current))break;
                if(states.containsKey(above)) {if(matches(above,current))break;continue;}
                if(states.size()>=BuildPlan.MAX_CELLS)break;
                states.put(above,Blocks.AIR.getDefaultState());properties.put(above,Map.of());preparation.add(above);count++;
            }
        }
        if(count>0)org.slf4j.LoggerFactory.getLogger("DummyMod-AI").info("Exposing buried project cells for {}, extra terrain blocks={}",session.displayName(),count);
    }
    private static boolean natural(BlockState state) {
        return state.isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_OVERWORLD) || state.isOf(Blocks.DIRT) || state.isOf(Blocks.GRASS_BLOCK) || state.isOf(Blocks.COARSE_DIRT) || state.isOf(Blocks.ROOTED_DIRT) || state.isOf(Blocks.PODZOL) || state.isOf(Blocks.MYCELIUM) || state.isOf(Blocks.MUD) || state.isOf(Blocks.CLAY) || state.isOf(Blocks.SAND) || state.isOf(Blocks.RED_SAND) || state.isOf(Blocks.GRAVEL) || state.isOf(Blocks.SNOW_BLOCK) || state.isOf(Blocks.SNOW);
    }
    private boolean checkProgress(List<BuildPlan.Cell> remaining,IBaritone b,boolean creative) {
        Vec3d now=new Vec3d(session.player.getX(),session.player.getY(),session.player.getZ());boolean moving=lastPosition!=null && now.squaredDistanceTo(lastPosition)>0.04;lastPosition=now;
        // Removing terrain is progress even when the replacement block is not placed yet.
        int work=remaining.size()+(creative?(int)remaining.stream().filter(this::obstructed).count():0)+(flight.repairing()?1:0);
        if(!watch.observe(work,moving,false,20,creative && flight.planning()))return false;
        if(!watch.recover()) {String point=creative?flight.problem():position(remaining.getFirst()).toShortString();org.slf4j.LoggerFactory.getLogger("DummyMod-AI").warn("Builder paused after retries, remaining={}, details={}",remaining.size(),creative?flight.diagnostic():point);pause();say.accept("Не удалось "+(clearing?"расчистить":"поставить блок на")+" этот участок: "+point+" Проект сохранён; напиши «продолжай».");return true;}
        org.slf4j.LoggerFactory.getLogger("DummyMod-AI").info("Recovering builder for {}, remaining={}, attempt={}, creative={}, clearing={}, target={}",session.displayName(),remaining.size(),watch.retries(),creative,clearing,creative?flight.diagnostic():position(remaining.getFirst()).toShortString());
        if(watch.retries()==1)say.accept("Застряла на этом участке. Меняю подход и продолжаю сама.");
        if(creative){flight.retry();return true;}
        b.getBuilderProcess().onLostControl();b.getPathingBehavior().cancelEverything();b.getInputOverrideHandler().clearAllKeys();running=false;
        // Shrink the problematic batch and approach it from another safe side.
        stage=Map.of();BlockPos p=position(remaining.getFirst());List<BlockPos> choices=new ArrayList<>();
        for(int radius=2;radius<=4;radius++)for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++) {
            if(Math.abs(x)!=radius && Math.abs(z)!=radius)continue;
            for(int y=-2;y<=2;y++) {BlockPos q=p.add(x,y,z);
                if(session.world.getBlockState(q).isAir() && session.world.getBlockState(q.up()).isAir() && !session.world.getBlockState(q.down()).getCollisionShape(session.world,q.down()).isEmpty())choices.add(q);
            }
        }
        if(!choices.isEmpty()) {choices.sort(Comparator.comparingDouble(q->q.getSquaredDistance(session.player.getBlockPos())));BlockPos q=choices.get(Math.min(choices.size()-1,watch.retries()*3));b.getCustomGoalProcess().setGoalAndPath(new GoalNear(q,1));}
        recoveryUntil=ticks+120;return true;
    }
    private void tryGive(Map<Item,Integer> need) {
        if(!autoGive || !Supply.canGive(session) || System.currentTimeMillis()-lastPickup<2000)return;
        for(var e:need.entrySet()) {
            long now=System.currentTimeMillis();if(!giveWatch.canRequest(e.getKey(),now))continue;
            giveWatch.sent(e.getKey(),now,inventoryCount(e.getKey()));lastPickup=now;
            Supply.give(session,Registries.ITEM.getId(e.getKey()).toString(),Math.min(e.getValue(),e.getKey().getMaxCount()));return;
        }
    }
    private int inventoryCount(Item item){int count=0;for(int i=0;i<36;i++){ItemStack stack=session.player.getInventory().getStack(i);if(stack.isOf(item))count+=stack.getCount();}return count;}
}
