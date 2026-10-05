package com.dummymod.ai;

import com.dummymod.dummy.PlayerSession;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.input.Input;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.*;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;
import java.util.*;

/** Real creative flight and normal server-verified interactions, without scaffolding. */
final class CreativeBuilder {
    private final PlayerSession session;
    private final Building building;
    private final PassageJournal passage;
    private BuildPlan.Cell target;
    private List<FlightRoute.Point> path=List.of();
    private List<FlightRoute.Point> shortcutChecked;
    private int ticks,lastAction,lastRoute,failedRoutes;
    private boolean controlling;
    private boolean clearing;
    private boolean preferWalking=true,walking,walkingBlocked;
    private FlightSpeed speed=FlightSpeed.AUTO;
    private String lastTarget="нет доступной точки";
    private String lastInteraction="none",lastGate="none";
    private String lastStates="none";
    private BlockPos awaitingConfirmation;
    private boolean awaitingBreak;
    private int rejectedActions;
    private Vec3d rejectedPose;
    private BlockHitResult actionHit;
    private BlockPos actionTarget;
    private BlockState actionSupport;
    private BlockPos turnTarget;
    private float turnYaw,turnPitch;
    private int turnStarted;
    private FlightRoute.Search search;
    private FlightRoute.Point routeStart;
    private List<FlightRoute.Point> routeGoals=List.of();
    private int routeMode;
    private boolean allowPassage;
    private PassageJournal.Entry navigationAction;
    private boolean navigationRestore;
    private BlockState navigationExpected;
    private final Map<BuildPlan.Cell,Integer> postponed=new HashMap<>();
    private final Map<FlightRoute.Point,Integer> failedVantages=new HashMap<>();
    private final Map<String,Integer> failedAnchors=new HashMap<>();
    CreativeBuilder(PlayerSession session,Building building){
        this.session=session;this.building=building;
        passage=new PassageJournal(passageFile(session));
    }
    static java.nio.file.Path passageFile(PlayerSession session) {
        var client=net.minecraft.client.MinecraftClient.getInstance();
        String server=client.getCurrentServerEntry()==null?"local:"+(client.getServer()==null?"unknown":client.getServer().getSavePath(net.minecraft.util.WorldSavePath.ROOT)):client.getCurrentServerEntry().address;
        String key=server+"|"+session.displayName().toLowerCase(Locale.ROOT)+"|"+session.world.getRegistryKey().getValue();
        String id=UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("dummymod-ai-passages").resolve(id+".json");
    }
    boolean repairing(){return !passage.entries().isEmpty();}
    boolean planning(){return search!=null && !search.done();}
    boolean controls(){return controlling;}
    void speed(FlightSpeed value){speed=value==null?FlightSpeed.AUTO:value;}
    void preferWalking(boolean value){if(preferWalking!=value){preferWalking=value;retry();}}
    static boolean available(PlayerSession s){return s.player!=null && s.player.isCreative() && s.player.getAbilities().allowFlying;}
    void release() {
        if(controlling && session.player!=null){session.player.setVelocity(Vec3d.ZERO);session.player.input=new Input();session.player.setSprinting(false);}
        controlling=false;target=null;turnTarget=null;path=List.of();search=null;awaitingConfirmation=null;navigationAction=null;
    }
    void retry(){target=null;actionHit=null;turnTarget=null;path=List.of();search=null;postponed.clear();failedVantages.clear();failedRoutes=0;walkingBlocked=false;}
    String diagnostic(){return lastTarget+", position="+new Vec3d(session.player.getX(),session.player.getY(),session.player.getZ())+", route="+path.size()+", search="+(search==null?"none":routeMode+":"+search.visited())+", repairs="+passage.entries().size()+", travel="+(walking?"walk":"fly")+", gate="+lastGate+", interaction="+lastInteraction+", states="+lastStates;}
    String problem(){return lastTarget+". "+(lastInteraction.contains("свойства блока")?"Не найден способ поставить блок с заданными свойствами.":lastGate.contains("visible=false")?"Не найден доступ к грани блока.":lastGate.contains("подход")?"Не найден маршрут к точке установки.":"Действие не завершилось.");}
    private boolean pending(BuildPlan.Cell c){return clearing?building.obstructed(c):building.needs(c);}
    boolean awaiting(BlockPos pos) {
        if(!pos.equals(awaitingConfirmation))return false;
        var updates=((com.dummymod.mixin.ClientWorldPredictionAccessor)session.world).dummymod$pendingUpdates();
        return ((com.dummymod.mixin.PendingUpdateManagerAccessor)updates).dummymod$blocks().containsKey(pos.asLong());
    }
    void tick(Map<BuildPlan.Cell,BlockState> stage,boolean allowBreaking,boolean clearing) {
        if(!available(session)){release();return;}
        if(this.clearing!=clearing){retry();this.clearing=clearing;}
        ticks++;controlling=true;
        allowPassage=allowBreaking;
        session.player.input=new Input();
        session.player.setSprinting(false);
        if(session.player.getAbilities().flying)session.player.setVelocity(Vec3d.ZERO);
        else session.player.setVelocity(0,session.player.getVelocity().y,0);
        // Local placement is a prediction. Do not advance, change the held item,
        // or resend this action until its own world's server acknowledgement arrives.
        if(awaitingConfirmation!=null) {
            if(awaiting(awaitingConfirmation)){lastGate="ожидаю подтверждение сервера";return;}
            boolean accepted=navigationAction!=null?session.world.getBlockState(awaitingConfirmation).equals(navigationExpected):awaitingBreak?session.world.getBlockState(awaitingConfirmation).isAir():target!=null && building.matches(target,session.world.getBlockState(awaitingConfirmation));
            if(navigationAction!=null && accepted && navigationRestore)forget(navigationAction);
            if(accepted)rejectedActions=0;
            else if(++rejectedActions>=2) {
                // A predicted success can still be rejected by the server. Leave
                // this approach rather than repeatedly clicking from the same pose.
                rejectedPose=session.player.getEyePos();
                failedVantages.put(point(session.player.getBlockPos()),ticks+100);
                path=List.of();lastRoute=ticks-40;
            }
            awaitingConfirmation=null;
            navigationAction=null;
        }
        if(restorePassage(stage.isEmpty() && building.states.keySet().stream().noneMatch(building::needs)))return;
        if(target!=null && (!pending(target) || !stage.containsKey(target) || postponed.getOrDefault(target,0)>ticks)){target=null;path=List.of();search=null;}
        if(target==null) {
            Candidate next=nextTarget(stage);target=next==null?null:next.cell();actionHit=next==null?null:next.hit();
            if(target==null){if(!clearing)buildAnchor(stage);return;}
            actionTarget=building.position(target);actionSupport=session.world.getBlockState(actionHit.getBlockPos());walkingBlocked=false;
            lastRoute=ticks-40;failedRoutes=0;failedVantages.clear();
            rejectedActions=0;rejectedPose=null;
        }
        BlockPos pos=building.position(target);BlockState desired=building.states.get(target),current=session.world.getBlockState(pos);
        lastStates="expected="+desired+" actual="+current+" required="+building.properties.get(target);
        boolean adjusting=building.adjustsLight(target,current);
        boolean combine=desired.getBlock()==current.getBlock() && "double".equals(building.properties.get(target).get("type"));
        boolean breaking=clearing || desired.isAir() || (!adjusting && !combine && !current.isReplaceable() && !building.matches(target,current));
        lastTarget=building.position(target).toShortString()+", "+net.minecraft.registry.Registries.BLOCK.getId((breaking?current:desired).getBlock())+", "+(breaking?"ломание":adjusting?"изменение огня":"установка");
        if(breaking && !allowBreaking){release();return;}
        double slabTop=combine?current.getOutlineShape(session.world,pos).getBoundingBox().maxY:1;
        BlockHitResult hit=breaking || adjusting?breakHit(pos):combine?new BlockHitResult(new Vec3d(pos.getX()+0.5,pos.getY()+slabTop-0.001,pos.getZ()+0.5),Direction.UP,pos,false):placementAnchor(pos,desired);
        if(hit==null){postpone();return;}
        double reach=Math.min(session.player.getBlockInteractionRange(),4.5)-0.2;
        Vec3d eye=session.player.getEyePos();
        boolean bodyBlocksPlacement=!breaking && !adjusting && intersects(desired,pos,session.player.getBoundingBox());
        boolean inReach=eye.squaredDistanceTo(hit.getPos())<=reach*reach,canSee=visible(eye,hit);
        lastGate="body="+bodyBlocksPlacement+" reach="+inReach+" visible="+canSee+" support="+hit.getBlockPos().toShortString()+" side="+hit.getSide()+" hit="+hit.getPos();
        if(!canSee && inReach && openSight(hit))return;
        if(!bodyBlocksPlacement && inReach && canSee && (rejectedPose==null || eye.squaredDistanceTo(rejectedPose)>0.25)) {
            path=List.of();search=null;
            if(breaking){turnTarget=null;look(hit.getPos());}
            else if(adjusting) {
                boolean light=desired.get(net.minecraft.state.property.Properties.LIT);
                equip(light?net.minecraft.item.Items.FLINT_AND_STEEL:current.getBlock() instanceof net.minecraft.block.CampfireBlock?net.minecraft.item.Items.IRON_SHOVEL:net.minecraft.item.Items.AIR);look(hit.getPos());
            } else {
                equip(desired);
                if(!placementLook(hit,desired)) {
                    lastInteraction="свойства блока не совпадают до установки";failedAnchors.put(anchorKey(pos,hit),ticks+120);
                    actionHit=null;turnTarget=null;path=List.of();search=null;lastRoute=ticks-40;return;
                }
                if(!placementTurnReady(pos)){lastGate="ожидаю поворот головы на сервере";return;}
            }
            if(preferWalking && supported(session.player.getBoundingBox()))flying(false);
            if(ticks-lastAction<(breaking?5:1))return;
            lastAction=ticks;
            session.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.LookAndOnGround(session.player.getYaw(),session.player.getPitch(),session.player.isOnGround(),session.player.horizontalCollision));
            if(breaking){Tools.select(session,current);lastInteraction="break="+session.interactionManager.attackBlock(pos,hit.getSide());}
            else if(adjusting){lastInteraction="light="+session.interactionManager.interactBlock(session.player,Hand.MAIN_HAND,hit);}
            else {
                // Crouch prevents opening a chest/door used as the placement anchor.
                session.player.input.playerInput=new net.minecraft.util.PlayerInput(false,false,false,false,false,true,false);
                session.player.setSneaking(true);
                session.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket(session.player.input.playerInput));
                lastInteraction="place="+session.interactionManager.interactBlock(session.player,Hand.MAIN_HAND,hit)+" item="+net.minecraft.registry.Registries.ITEM.getId(session.player.getMainHandStack().getItem());
                lastInteraction+=" predicted="+session.world.getBlockState(pos)+" sameWorld="+(session.player.getEntityWorld()==session.world);
                session.player.setSneaking(false);
                session.player.input.playerInput=net.minecraft.util.PlayerInput.DEFAULT;
                session.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket(net.minecraft.util.PlayerInput.DEFAULT));
            }
            session.player.swingHand(Hand.MAIN_HAND);
            awaitingConfirmation=pos.toImmutable();
            awaitingBreak=breaking;
            if(ticks-lastRoute>100){postpone();}
            return;
        }
        flyTo(hit,pos,!breaking && !adjusting);
    }
    private record Candidate(BuildPlan.Cell cell,BlockHitResult hit) {}
    private Candidate nextTarget(Map<BuildPlan.Cell,BlockState> stage) {
        var order=Comparator.comparingInt(BuildPlan.Cell::y);if(clearing)order=order.reversed();
        order=order.thenComparingDouble(c->session.player.squaredDistanceTo(Vec3d.ofCenter(building.position(c))));
        Candidate fallback=null;Integer layer=null;int examined=0;
        for(var c:stage.keySet().stream().sorted(order).toList()) {
            // Keep foundation order, but finish reachable cells before moving
            // around a wall for a slightly nearer cell. Expensive face checks
            // usually stop after one ready cell, and inspect at most 32 options.
            if(layer!=null && (clearing?c.y()<layer-1:c.y()>layer))break;
            if(!pending(c) || postponed.getOrDefault(c,0)>ticks || !building.loaded(c))continue;
            var props=building.properties.get(c);
            if(!clearing && Set.of("head","upper").contains(props.getOrDefault("part",props.getOrDefault("half",""))))continue;
            BlockPos pos=building.position(c);BlockState desired=building.states.get(c),current=session.world.getBlockState(pos);
            boolean adjusting=building.adjustsLight(c,current),combine=desired.getBlock()==current.getBlock() && "double".equals(props.get("type"));
            boolean breaking=clearing || desired.isAir() || !adjusting && !combine && !current.isReplaceable() && !building.matches(c,current);
            BlockHitResult hit=breaking || adjusting?breakHit(pos):combine?new BlockHitResult(new Vec3d(pos.getX()+0.5,pos.getY()+current.getOutlineShape(session.world,pos).getBoundingBox().maxY-0.001,pos.getZ()+0.5),Direction.UP,pos,false):anchor(c,desired);
            if(hit==null)continue;
            if(layer==null)layer=c.y();
            Candidate candidate=new Candidate(c,hit);if(fallback==null)fallback=candidate;
            if((breaking || adjusting || !intersects(desired,pos,session.player.getBoundingBox())) && usable(hit))return candidate;
            if(++examined>=32)break;
        }
        return fallback;
    }
    private void equip(BlockState desired) {
        equip(desired.getBlock().asItem());
    }
    private void equip(net.minecraft.item.Item item) {
        var inv=session.player.getInventory();int slot=8;
        ItemStack wanted=item==net.minecraft.item.Items.AIR?ItemStack.EMPTY:new ItemStack(item,Math.min(64,item.getMaxCount()));
        if(inv.getStack(slot).getItem()!=wanted.getItem() || inv.getStack(slot).isEmpty()) {
            inv.setStack(slot,wanted);session.interactionManager.clickCreativeStack(wanted,36+slot);
        }
        inv.setSelectedSlot(slot);
    }
    private BlockHitResult anchor(BuildPlan.Cell cell,BlockState desired) {
        return anchor(building.position(cell),desired,building.properties.get(cell));
    }
    private BlockHitResult placementAnchor(BlockPos pos,BlockState desired) {
        return stableAnchor(pos,desired,building.properties.get(target));
    }
    private BlockHitResult stableAnchor(BlockPos pos,BlockState desired,Map<String,String> props) {
        // The route and interaction must use the same face. Choosing the nearest
        // anchor again while moving makes the goal jump to the opposite side.
        if(actionHit!=null && pos.equals(actionTarget) && session.world.getBlockState(actionHit.getBlockPos()).equals(actionSupport) && failedAnchors.getOrDefault(anchorKey(pos,actionHit),0)<=ticks)return actionHit;
        actionHit=anchor(pos,desired,props);actionTarget=pos;
        actionSupport=actionHit==null?null:session.world.getBlockState(actionHit.getBlockPos());
        return actionHit;
    }
    private BlockHitResult anchor(BlockPos target,BlockState desired,Map<String,String> props) {
        if(!desired.canPlaceAt(session.world,target))return null;
        BlockHitResult best=null;double distance=Double.MAX_VALUE;int bestRank=Integer.MAX_VALUE;
        // Grass and other replaceable plants are clicked in their own cell.
        // Clicking a different support can otherwise put the block elsewhere.
        BlockState occupying=session.world.getBlockState(target);
        if(!occupying.isAir() && occupying.isReplaceable() && !occupying.getOutlineShape(session.world,target).isEmpty()) {
            BlockHitResult direct=breakHit(target);
            if(failedAnchors.getOrDefault(anchorKey(target,direct),0)<=ticks){best=direct;distance=session.player.getEyePos().squaredDistanceTo(direct.getPos());bestRank=anchorRank(direct);}
        }
        for(Direction side:Direction.values()) {
            BlockPos support=target.offset(side.getOpposite());BlockState state=session.world.getBlockState(support);
            if(state.isAir() || state.isReplaceable() || state.getCollisionShape(session.world,support).isEmpty())continue;
            String half=props==null?null:props.get("half"),type=props==null?null:props.get("type"),axis=props==null?null:props.get("axis");
            // These decorations attach to a particular face. Routing to the
            // floor first cannot help place a wall button or a wall ladder.
            String face=props.get("face"),facing=props.get("facing");
            if("floor".equals(face) && side!=Direction.UP || "ceiling".equals(face) && side!=Direction.DOWN || "wall".equals(face) && side.getAxis()==Direction.Axis.Y)continue;
            boolean wall="wall".equals(face) || desired.getBlock() instanceof net.minecraft.block.LadderBlock || desired.getBlock() instanceof net.minecraft.block.WallSignBlock || desired.getBlock() instanceof net.minecraft.block.WallHangingSignBlock || desired.getBlock() instanceof net.minecraft.block.WallBannerBlock || desired.getBlock() instanceof net.minecraft.block.WallSkullBlock || desired.getBlock() instanceof net.minecraft.block.WallTorchBlock;
            if(wall && (side.getAxis()==Direction.Axis.Y || facing!=null && !side.asString().equals(facing)))continue;
            if(axis!=null && !side.getAxis().name().toLowerCase(Locale.ROOT).equals(axis))continue;
            if((desired.isOf(net.minecraft.block.Blocks.TORCH) || desired.isOf(net.minecraft.block.Blocks.SOUL_TORCH) || desired.isOf(net.minecraft.block.Blocks.REDSTONE_TORCH)) && side!=Direction.UP)continue;
            if(desired.getBlock() instanceof net.minecraft.block.CarpetBlock && side!=Direction.UP)continue;
            if(desired.getBlock() instanceof net.minecraft.block.DoorBlock && side!=Direction.UP)continue;
            if("true".equals(props.get("hanging")) && side!=Direction.DOWN)continue;
            if("false".equals(props.get("hanging")) && side!=Direction.UP)continue;
            if((desired.getBlock() instanceof net.minecraft.block.SlabBlock || desired.getBlock() instanceof net.minecraft.block.StairsBlock) && ((("top".equals(type) || "top".equals(half)) && side==Direction.UP) || (("bottom".equals(type) || "bottom".equals(half)) && side==Direction.DOWN)))continue;
            Box outline=state.getOutlineShape(session.world,support).getBoundingBox();
            Vec3d point=new Vec3d(support.getX()+(outline.minX+outline.maxX)/2,support.getY()+(outline.minY+outline.maxY)/2,support.getZ()+(outline.minZ+outline.maxZ)/2);
            point=switch(side){case UP->new Vec3d(point.x,support.getY()+outline.maxY,point.z);case DOWN->new Vec3d(point.x,support.getY()+outline.minY,point.z);case EAST->new Vec3d(support.getX()+outline.maxX,point.y,point.z);case WEST->new Vec3d(support.getX()+outline.minX,point.y,point.z);case SOUTH->new Vec3d(point.x,point.y,support.getZ()+outline.maxZ);case NORTH->new Vec3d(point.x,point.y,support.getZ()+outline.minZ);};
            if(side.getAxis()!=Direction.Axis.Y) {
                if("top".equals(type) || "top".equals(half))point=new Vec3d(point.x,support.getY()+0.8,point.z);
                else if("bottom".equals(type) || "bottom".equals(half))point=new Vec3d(point.x,support.getY()+0.2,point.z);
            }
            if(desired.getBlock() instanceof net.minecraft.block.DoorBlock && props.containsKey("hinge")) {
                point=doorPoint(point,support,desired,props);
                if(point==null)continue;
            }
            double d=session.player.getEyePos().squaredDistanceTo(point);
            BlockHitResult candidate=new BlockHitResult(point,side,support,false);
            if(failedAnchors.getOrDefault(anchorKey(target,candidate),0)>ticks)continue;
            int rank=anchorRank(candidate);
            if(best==null || rank<bestRank || rank==bestRank && d<distance){best=candidate;distance=d;bestRank=rank;}
        }
        return best;
    }
    private int anchorRank(BlockHitResult hit) {
        Vec3d eye=session.player.getEyePos();
        if(visible(eye,hit))return 0;
        if(allowPassage) {
            var opening=sightOpening(eye,hit);if(opening!=null)return 1+opening.size();
        }
        return eye.subtract(hit.getPos()).dotProduct(Vec3d.of(hit.getSide().getVector()))>0?10:11;
    }
    private Vec3d doorPoint(Vec3d point,BlockPos support,BlockState desired,Map<String,String> props) {
        float yaw=session.player.getYaw();
        try {
            session.player.setYaw(switch(props.getOrDefault("facing","north")){case "south"->0;case "west"->90;case "east"->-90;default->180;});
            for(double x:new double[]{0.25,0.75})for(double z:new double[]{0.25,0.75}) {
                Vec3d candidate=new Vec3d(support.getX()+x,point.y,support.getZ()+z);
                var hit=new BlockHitResult(candidate,Direction.UP,support,false);
                var context=new net.minecraft.item.ItemPlacementContext(session.player,Hand.MAIN_HAND,new ItemStack(desired.getBlock().asItem()),hit);
                BlockState predicted=desired.getBlock().getPlacementState(context);
                if(predicted!=null && predicted.get(net.minecraft.state.property.Properties.DOOR_HINGE).asString().equals(props.get("hinge")))return candidate;
            }
            return null;
        } finally {session.player.setYaw(yaw);}
    }
    private BlockHitResult breakHit(BlockPos p) {
        BlockHitResult best=null;double distance=Double.MAX_VALUE;boolean bestVisible=false;
        var shape=session.world.getBlockState(p).getOutlineShape(session.world,p);
        Box bounds=shape.isEmpty()?new Box(0,0,0,1,1,1):shape.getBoundingBox();
        for(Direction side:Direction.values()) {
            Vec3d point=new Vec3d(p.getX()+(bounds.minX+bounds.maxX)/2,p.getY()+(bounds.minY+bounds.maxY)/2,p.getZ()+(bounds.minZ+bounds.maxZ)/2);
            point=switch(side){case UP->new Vec3d(point.x,p.getY()+bounds.maxY,point.z);case DOWN->new Vec3d(point.x,p.getY()+bounds.minY,point.z);case EAST->new Vec3d(p.getX()+bounds.maxX,point.y,point.z);case WEST->new Vec3d(p.getX()+bounds.minX,point.y,point.z);case SOUTH->new Vec3d(point.x,point.y,p.getZ()+bounds.maxZ);case NORTH->new Vec3d(point.x,point.y,p.getZ()+bounds.minZ);};
            BlockHitResult hit=new BlockHitResult(point,side,p,false);double d=session.player.getEyePos().squaredDistanceTo(point);
            boolean sees=visible(session.player.getEyePos(),hit);
            if(best==null || sees && !bestVisible || sees==bestVisible && d<distance){best=hit;distance=d;bestVisible=sees;}
        }
        return best;
    }
    private boolean visible(Vec3d eye,BlockHitResult hit) {
        Vec3d end=hit.getPos().add(Vec3d.of(hit.getSide().getVector()).multiply(-0.015));
        // Tall grass does not prevent clicking a solid support behind it.
        boolean solid=!session.world.getBlockState(hit.getBlockPos()).getCollisionShape(session.world,hit.getBlockPos()).isEmpty();
        BlockHitResult ray=session.world.raycast(new RaycastContext(eye,end,solid?RaycastContext.ShapeType.COLLIDER:RaycastContext.ShapeType.OUTLINE,RaycastContext.FluidHandling.NONE,session.player));
        return ray.getType()==HitResult.Type.BLOCK && ray.getBlockPos().equals(hit.getBlockPos()) && ray.getSide()==hit.getSide();
    }
    private static String anchorKey(BlockPos target,BlockHitResult hit){return target.asLong()+":"+hit.getBlockPos().asLong()+":"+hit.getSide();}
    private boolean openSight(BlockHitResult hit) {
        if(!allowPassage || hit==null || !inReach(hit))return false;
        List<BlockPos> opening=sightOpening(session.player.getEyePos(),hit);
        if(opening==null || opening.isEmpty() || passage.entries().stream().filter(e->!original(e).isAir()).count()>=6)return false;
        BlockPos obstruction=opening.getFirst();BlockHitResult removal=breakHit(obstruction);
        if(!usable(removal))return false;
        var entry=new PassageJournal.Entry(obstruction.getX(),obstruction.getY(),obstruction.getZ(),net.minecraft.registry.Registries.BLOCK.getId(session.world.getBlockState(obstruction).getBlock()).toString());
        navigationInteract(entry,removal,false);return true;
    }
    /** A virtual view through at most six restorable cubes, without changing the world. */
    private List<BlockPos> sightOpening(Vec3d eye,BlockHitResult hit) {
        Vec3d end=hit.getPos().add(Vec3d.of(hit.getSide().getVector()).multiply(-0.015));
        Vec3d direction=end.subtract(eye).normalize(),start=eye;
        List<BlockPos> opening=new ArrayList<>();
        boolean solid=!session.world.getBlockState(hit.getBlockPos()).getCollisionShape(session.world,hit.getBlockPos()).isEmpty();
        for(int i=0;i<=6;i++) {
            BlockHitResult ray=session.world.raycast(new RaycastContext(start,end,solid?RaycastContext.ShapeType.COLLIDER:RaycastContext.ShapeType.OUTLINE,RaycastContext.FluidHandling.NONE,session.player));
            if(ray.getType()!=HitResult.Type.BLOCK)return null;
            if(ray.getBlockPos().equals(hit.getBlockPos()))return ray.getSide()==hit.getSide()?opening:null;
            BlockPos obstacle=ray.getBlockPos();
            if(!removable(obstacle) || opening.contains(obstacle) || opening.size()>=6)return null;
            opening.add(obstacle.toImmutable());
            var exit=new Box(obstacle).raycast(end,start);
            if(exit.isEmpty())return null;
            start=exit.get().add(direction.multiply(0.015));
        }
        return null;
    }
    private boolean free(FlightRoute.Point p) {
        if(p.y()<session.world.getBottomY() || p.y()+2>session.world.getTopYInclusive() || !session.world.isChunkLoaded(p.x()>>4,p.z()>>4))return false;
        return session.world.isSpaceEmpty(session.player,body(p));
    }
    private boolean supported(Box body){return !session.world.isSpaceEmpty(session.player,body.offset(0,-0.15,0));}
    private boolean ground(FlightRoute.Point p){return free(p) && supported(body(p));}
    private double feetHeight(FlightRoute.Point p) {
        BlockPos pos=new BlockPos(p.x(),p.y(),p.z());
        var shape=session.world.getBlockState(pos).getCollisionShape(session.world,pos);
        if(!shape.isEmpty()) {
            double height=shape.getMax(Direction.Axis.Y);
            if(height>0 && height<=session.player.getStepHeight())return p.y()+height;
        }
        return p.y();
    }
    private Box body(FlightRoute.Point p){double y=feetHeight(p)+0.08;return new Box(p.x()+0.18,y,p.z()+0.18,p.x()+0.82,y+session.player.getHeight(),p.z()+0.82);}
    private boolean intersects(BlockState state,BlockPos pos,Box body) {
        return state.getCollisionShape(session.world,pos).getBoundingBoxes().stream().anyMatch(box->box.offset(pos).intersects(body));
    }
    private static BlockPos position(PassageJournal.Entry e){return new BlockPos(e.x(),e.y(),e.z());}
    private static BlockState original(PassageJournal.Entry e){return net.minecraft.registry.Registries.BLOCK.get(net.minecraft.util.Identifier.of(e.block())).getDefaultState();}
    private void forget(PassageJournal.Entry e) {
        try{passage.restored(e);}catch(java.io.IOException failure){building.navigationFailure("Не удалось сохранить восстановленный проход.");}
    }
    /** Only ordinary, state-free full cubes can be restored exactly by normal placement. */
    private boolean removable(BlockPos pos) {
        BlockState state=session.world.getBlockState(pos);
        var relative=new BuildPlan.Cell(pos.getX()-building.plan.origin[0],pos.getY()-building.plan.origin[1],pos.getZ()-building.plan.origin[2]);
        if(building.states.containsKey(relative) && !building.matches(relative,state) || target!=null && pending(target) && building.position(target).equals(pos) || state.hasBlockEntity() || !state.getEntries().isEmpty() || state.getBlock().asItem()==net.minecraft.item.Items.AIR || state.getHardness(session.world,pos)<0 || !state.isFullCube(session.world,pos) || !state.getFluidState().isEmpty())return false;
        // Never remove a container, a fluid barrier, or support for a fragile decoration.
        for(Direction d:Direction.values()) {
            BlockPos adjacent=pos.offset(d);BlockState neighbor=session.world.getBlockState(adjacent);
            if(!neighbor.getFluidState().isEmpty() || neighbor.getBlock() instanceof net.minecraft.block.FallingBlock || d==Direction.UP && neighbor.getBlock() instanceof net.minecraft.block.PlantBlock && !neighbor.isReplaceable() || neighbor.getBlock() instanceof net.minecraft.block.CarpetBlock || neighbor.getBlock() instanceof net.minecraft.block.AbstractCandleBlock || neighbor.getBlock() instanceof net.minecraft.block.TorchBlock || neighbor.getBlock() instanceof net.minecraft.block.WallTorchBlock || neighbor.getBlock() instanceof net.minecraft.block.LanternBlock || neighbor.getBlock() instanceof net.minecraft.block.DoorBlock || neighbor.getBlock() instanceof net.minecraft.block.BedBlock || neighbor.hasBlockEntity())return false;
        }
        return true;
    }
    private List<BlockPos> blockers(FlightRoute.Point p) {
        List<BlockPos> result=new ArrayList<>();
        for(int y=0;y<2;y++) {
            BlockPos pos=new BlockPos(p.x(),p.y()+y,p.z());
            if(!session.world.getBlockState(pos).getCollisionShape(session.world,pos).isEmpty())result.add(pos);
        }
        return result;
    }
    private int passageCost(FlightRoute.Point p) {
        if(free(p))return 1;
        if(p.y()<session.world.getBottomY()+1 || p.y()+2>session.world.getTopYInclusive() || !session.world.isChunkLoaded(p.x()>>4,p.z()>>4))return 0;
        List<BlockPos> blocks=blockers(p);
        return !blocks.isEmpty() && blocks.stream().allMatch(this::removable)?1+6*blocks.size():0;
    }
    private boolean inReach(BlockHitResult hit){return session.player.getEyePos().squaredDistanceTo(hit.getPos())<Math.pow(Math.min(session.player.getBlockInteractionRange(),4.5)-0.2,2);}
    private boolean usable(BlockHitResult hit){return hit!=null && inReach(hit) && visible(session.player.getEyePos(),hit);}
    private void navigationInteract(PassageJournal.Entry e,BlockHitResult hit,boolean restore) {
        if(ticks-lastAction<(restore?1:5))return;
        lastAction=ticks;look(hit.getPos());
        session.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.LookAndOnGround(session.player.getYaw(),session.player.getPitch(),session.player.isOnGround(),session.player.horizontalCollision));
        if(restore){equip(original(e));lastInteraction="restore="+session.interactionManager.interactBlock(session.player,Hand.MAIN_HAND,hit);}
        else {
            try{passage.remember(e);}catch(java.io.IOException failure){building.navigationFailure("Не удалось сохранить временный проход: "+failure.getMessage());return;}
            Tools.select(session,session.world.getBlockState(position(e)));
            lastInteraction="passage="+session.interactionManager.attackBlock(position(e),hit.getSide());
        }
        session.player.swingHand(Hand.MAIN_HAND);
        awaitingConfirmation=position(e);awaitingBreak=!restore;navigationAction=e;navigationRestore=restore;
        navigationExpected=restore?original(e):Blocks.AIR.getDefaultState();
        lastGate=restore?"восстанавливаю временный проход":"открываю временный проход";
    }
    private boolean excavate(FlightRoute.Point next) {
        if(!allowPassage)return false;
        for(BlockPos pos:blockers(next)) {
            if(!removable(pos))return false;
            BlockHitResult hit=breakHit(pos);
            if(usable(hit)) {
                var e=new PassageJournal.Entry(pos.getX(),pos.getY(),pos.getZ(),net.minecraft.registry.Registries.BLOCK.getId(session.world.getBlockState(pos).getBlock()).toString());
                navigationInteract(e,hit,false);return true;
            }
        }
        return false;
    }
    private boolean restorePassage(boolean onlyRepairs) {
        if(passage.focus()!=null)return restoreFocused(passage.focus());
        var entries=new ArrayList<>(passage.entries());
        // A ceiling opening is closed from its farthest layer toward the player.
        entries.sort(Comparator.comparingDouble((PassageJournal.Entry e)->session.player.squaredDistanceTo(Vec3d.ofCenter(position(e)))).reversed());
        for(var e:entries) {
            BlockPos pos=position(e);
            if(!session.world.isChunkLoaded(pos.getX()>>4,pos.getZ()>>4))continue;
            if(session.world.getBlockState(pos).equals(original(e))){forget(e);continue;}
            if(onlyRepairs) {
                try{passage.focus(e);}catch(java.io.IOException failure){building.navigationFailure("Не удалось сохранить порядок восстановления прохода.");return true;}
                return restoreFocused(e);
            }
            if(original(e).isAir()) {
                continue;
            }
            if(!session.world.getBlockState(pos).isReplaceable()){building.navigationFailure("Временный проход изменился: "+pos.toShortString()+". Сохранённые блоки оставлены для восстановления.");return true;}
            // Keep the approach and its line of sight open until this target is
            // done. Restoring on the very next tick closes freshly opened access.
            if(!onlyRepairs && target!=null && pending(target))continue;
            if(session.player.getBoundingBox().expand(0.2).intersects(new Box(pos)) || path.stream().anyMatch(p->body(p).intersects(new Box(pos))))continue;
            BlockHitResult hit=anchor(pos,original(e),Map.of());
            if(usable(hit)){navigationInteract(e,hit,true);return true;}
        }
        return false;
    }
    private boolean restoreFocused(PassageJournal.Entry e) {
        BlockPos pos=position(e);BlockState desired=original(e),current=session.world.getBlockState(pos);
        lastTarget=pos.toShortString()+", "+e.block()+", восстановление прохода";lastStates="expected="+desired+" actual="+current;
        if(!session.world.isChunkLoaded(pos.getX()>>4,pos.getZ()>>4)){lastGate="ожидаю загрузку прохода";return true;}
        if(current.equals(desired)){forget(e);path=List.of();search=null;actionHit=null;failedRoutes=0;lastRoute=ticks-40;return true;}
        if(desired.isAir() && !allowPassage){building.navigationFailure("Для удаления временных опор включи ломание в меню ИИ.");return true;}
        if(desired.isAir()?!current.isOf(Blocks.STONE):!current.isReplaceable()){building.navigationFailure("Временный проход изменился: "+pos.toShortString()+". Проект сохранён.");return true;}
        BlockHitResult hit=desired.isAir()?breakHit(pos):restorationAnchor(pos,desired);
        if(hit==null){lastGate="не найдена опора для восстановления";return true;}
        boolean occupied=!desired.isAir() && (intersects(desired,pos,session.player.getBoundingBox()) || path.stream().anyMatch(p->intersects(desired,pos,body(p))));
        if(!occupied && usable(hit)) {
            navigationInteract(e,hit,!desired.isAir());if(desired.isAir())navigationRestore=true;return true;
        }
        if(!visible(session.player.getEyePos(),hit) && openSight(hit))return true;
        if(!visible(session.player.getEyePos(),hit) && restoreUnneededAccess(hit))return true;
        lastGate=(occupied?"выхожу из восстанавливаемой ячейки":"ищу доступ для восстановления")+", support="+hit.getBlockPos().toShortString()+", side="+hit.getSide();
        if(pos.getY()<session.player.getBlockY() || occupied)walkingBlocked=true;
        flyTo(hit,pos,!desired.isAir());return true;
    }
    private boolean restoreUnneededAccess(BlockHitResult targetHit) {
        if(!inReach(targetHit))return false;
        var opening=sightOpening(session.player.getEyePos(),targetHit);if(opening==null)return false;
        long existing=passage.entries().stream().filter(e->!original(e).isAir()).count();
        if(existing+opening.size()<=6)return false;
        Vec3d eye=session.player.getEyePos(),end=targetHit.getPos();
        for(var e:passage.entries()) {
            if(e.equals(passage.focus()) || original(e).isAir())continue;
            BlockPos pos=position(e);Box box=new Box(pos);
            if(!session.world.isChunkLoaded(pos.getX()>>4,pos.getZ()>>4) || !session.world.getBlockState(pos).isReplaceable() || box.contains(eye) || box.raycast(eye,end).isPresent() || box.intersects(session.player.getBoundingBox()) || path.stream().anyMatch(p->box.intersects(body(p))))continue;
            BlockHitResult hit=anchor(pos,original(e),Map.of());
            if(usable(hit)){navigationInteract(e,hit,true);return true;}
        }
        return false;
    }
    private BlockHitResult restorationAnchor(BlockPos pos,BlockState desired) {
        // An underground hole is closed against its floor. Side anchors can
        // require removing several unrelated soil columns to expose one face.
        BlockPos below=pos.down();BlockState support=session.world.getBlockState(below);
        var floor=new BlockHitResult(new Vec3d(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5),Direction.UP,below,false);
        if(pos.getY()<building.plan.origin[1] && support.isFullCube(session.world,below) && failedAnchors.getOrDefault(anchorKey(pos,floor),0)<=ticks)return floor;
        return stableAnchor(pos,desired,Map.of());
    }
    /** A disconnected decoration needs a placement anchor even in creative. */
    private void buildAnchor(Map<BuildPlan.Cell,BlockState> stage) {
        if(!allowPassage)return;
        BlockPos next=null;BlockHitResult hit=null;int length=Integer.MAX_VALUE;
        for(var cell:stage.keySet()) {
            BlockState desired=building.states.get(cell);
            if(!pending(cell) || !building.loaded(cell) || desired.isAir() || desired.getBlock() instanceof net.minecraft.block.LanternBlock || desired.getBlock() instanceof net.minecraft.block.TorchBlock || desired.getBlock() instanceof net.minecraft.block.BedBlock || desired.getBlock() instanceof net.minecraft.block.DoorBlock || desired.getBlock() instanceof net.minecraft.block.FallingBlock)continue;
            BlockPos root=building.position(cell);String axis=building.properties.get(cell).get("axis");
            for(Direction direction:Direction.values()) {
                if(axis!=null && !direction.getAxis().name().toLowerCase(Locale.ROOT).equals(axis))continue;
                for(int distance=1;distance<=24 && distance<length;distance++) {
                    BlockPos support=root.offset(direction,distance);
                    if(support.getY()<session.world.getBottomY() || support.getY()>session.world.getTopYInclusive() || !session.world.isChunkLoaded(support.getX()>>4,support.getZ()>>4))break;
                    BlockState existing=session.world.getBlockState(support);
                    if(!existing.getFluidState().isEmpty())break;
                    if(!existing.isReplaceable()) {
                        BlockPos candidate=support.offset(direction.getOpposite());
                        var relative=new BuildPlan.Cell(candidate.getX()-building.plan.origin[0],candidate.getY()-building.plan.origin[1],candidate.getZ()-building.plan.origin[2]);
                        if(distance>1 && !building.states.containsKey(relative) && !existing.getCollisionShape(session.world,support).isEmpty()) {
                            BlockHitResult anchor=anchor(candidate,Blocks.STONE.getDefaultState(),Map.of());
                            if(anchor!=null){next=candidate;hit=anchor;length=distance;}
                        }
                        break;
                    }
                    if(!existing.isAir())break;
                    var relative=new BuildPlan.Cell(support.getX()-building.plan.origin[0],support.getY()-building.plan.origin[1],support.getZ()-building.plan.origin[2]);
                    if(building.states.containsKey(relative))break;
                }
            }
        }
        if(next==null)return;
        lastTarget=next.toShortString()+", временная опора для декора";
        if(!usable(hit) || session.player.getBoundingBox().intersects(new Box(next))){flyTo(hit,next,true);return;}
        if(ticks-lastAction<1)return;
        var e=new PassageJournal.Entry(next.getX(),next.getY(),next.getZ(),"minecraft:air");
        try{passage.remember(e);}catch(java.io.IOException failure){building.navigationFailure("Не удалось сохранить временную опору.");return;}
        path=List.of();search=null;lastAction=ticks;equip(Blocks.STONE.getDefaultState());look(hit.getPos());
        session.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.LookAndOnGround(session.player.getYaw(),session.player.getPitch(),session.player.isOnGround(),session.player.horizontalCollision));
        lastInteraction="anchor="+session.interactionManager.interactBlock(session.player,Hand.MAIN_HAND,hit);session.player.swingHand(Hand.MAIN_HAND);
        awaitingConfirmation=next;awaitingBreak=false;navigationAction=e;navigationRestore=false;navigationExpected=Blocks.STONE.getDefaultState();
    }
    private void flying(boolean value) {
        if(session.player.getAbilities().flying!=value){session.player.getAbilities().flying=value;session.player.sendAbilitiesUpdate();}
    }
    private void flyTo(BlockHitResult hit,BlockPos pos,boolean placing) {
        if(walkingBlocked || !supported(session.player.getBoundingBox()))flying(true);
        if(search!=null) {
            search.advance(600,3_000_000L);
            if(!search.done()){lastGate="поиск подхода "+routeMode+", узлов="+search.visited();return;}
            path=search.result();search=null;
            if(path.isEmpty() && routeMode<2) {
                if(routeMode==0)beginSearch(1);
                else if(allowPassage)beginSearch(2);
                if(search!=null)return;
            }
            if(!path.isEmpty()) {
                if(routeMode==2) {
                    Set<BlockPos> opening=new HashSet<>();path.forEach(p->opening.addAll(blockers(p)));
                    passage.entries().stream().filter(e->!original(e).isAir()).forEach(e->opening.add(position(e)));
                    if(opening.size()>6){path=List.of();lastGate="проход требует слишком много блоков";}
                }
                walking=routeMode==0;
                if(!path.isEmpty() && free(routeStart)) {
                    Vec3d center=new Vec3d(routeStart.x()+0.5,walking?session.player.getY():feetHeight(routeStart)+0.08,routeStart.z()+0.5);
                    if(center.squaredDistanceTo(new Vec3d(session.player.getX(),session.player.getY(),session.player.getZ()))>0.000625) {
                        var centered=new ArrayList<FlightRoute.Point>();centered.add(routeStart);centered.addAll(path);path=centered;
                    }
                }
            }
            if(path.isEmpty() && ++failedRoutes>=3){failedAnchors.put(anchorKey(pos,hit),ticks+400);postpone();return;}
        }
        if(path.isEmpty()) {
            if(ticks-lastRoute<15)return;
            lastRoute=ticks;routeStart=point(session.player.getBlockPos());
            List<FlightRoute.Point> candidates=new ArrayList<>();
            Map<FlightRoute.Point,Integer> approachCost=new HashMap<>();
            Set<FlightRoute.Point> visibleGoals=new HashSet<>();
            int openingBudget=6-(int)passage.entries().stream().filter(e->!original(e).isAir()).count();
            BlockState placingState=target!=null && building.position(target).equals(pos)?building.states.get(target):Blocks.STONE.getDefaultState();
            for(int y=-2;y<=3;y++)for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++) {
                FlightRoute.Point p=new FlightRoute.Point(pos.getX()+x,pos.getY()+y,pos.getZ()+z);
                double eyeHeight=session.player.getEyePos().y-session.player.getY();
                Vec3d eye=new Vec3d(p.x()+0.5,feetHeight(p)+0.08+eyeHeight,p.z()+0.5);
                if(failedVantages.getOrDefault(p,0)>ticks || eye.squaredDistanceTo(hit.getPos())>=16 || placing && intersects(placingState,pos,body(p)) || intersects(session.world.getBlockState(hit.getBlockPos()),hit.getBlockPos(),body(p)))continue;
                boolean clear=free(p),sees=visible(eye,hit);Set<BlockPos> opening=new HashSet<>();
                if(!clear || !sees) {
                    if(!allowPassage || passageCost(p)<=0)continue;
                    var sight=sightOpening(eye,hit);if(sight==null)continue;
                    opening.addAll(sight);if(!clear)opening.addAll(blockers(p));
                    if(opening.size()>openingBudget)continue;
                }
                if(clear && sees)visibleGoals.add(p);
                candidates.add(p);approachCost.put(p,p.distance(routeStart)+(preferWalking && !ground(p)?2:0)+6*opening.size());
            }
            if(!visibleGoals.isEmpty())candidates.removeIf(p->!visibleGoals.contains(p));
            candidates.sort(Comparator.comparingInt(approachCost::get));
            routeGoals=candidates.stream().limit(16).toList();walking=false;
            boolean sameFloor=routeGoals.stream().anyMatch(p->Math.abs(p.y()-routeStart.y())<=1);
            beginSearch(preferWalking && !walkingBlocked && sameFloor && supported(session.player.getBoundingBox())?0:1);
            return;
        }
        if(path.isEmpty())return;
        shortcutPath();
        FlightRoute.Point next=path.getFirst();
        if(!free(next)) {
            if(excavate(next))return;
            path=List.of();search=null;lastRoute=ticks-15;return;
        }
        Vec3d dest=new Vec3d(next.x()+0.5,feetHeight(next)+0.08,next.z()+0.5);
        Vec3d delta=dest.subtract(new Vec3d(session.player.getX(),session.player.getY(),session.player.getZ()));
        if(walking)delta=new Vec3d(delta.x,0,delta.z);
        if(delta.length()<(path.size()==1?0.025:0.16)){
            // Reaching a theoretically visible cell is insufficient if the actual
            // eye is still blocked. Do not select the same failed pose forever.
            if(path.size()==1)failedVantages.put(next,ticks+100);
            path=path.subList(1,path.size());if(path.isEmpty())lastRoute=ticks-15;return;
        }
        int distanceLeft=Math.max(path.size(),(int)Math.ceil(delta.length()));
        boolean sprintWalk=walking && distanceLeft>2;
        double rate=walking?(sprintWalk?0.28:0.22):speed.step(distanceLeft);
        Vec3d step=delta.normalize().multiply(Math.min(rate,delta.length()));
        // The current box can slightly intersect a freshly acknowledged block.
        // Including it in every swept check prevents even moving out of the block,
        // leaving the bot immobile until somebody nudges it. Check the destination;
        // vanilla movement still resolves collisions along the actual motion.
        Box moved=session.player.getBoundingBox().offset(step);
        double rise=feetHeight(next)+0.08-session.player.getY();
        // Let vanilla step onto carpets, snow and slabs instead of treating their
        // thin collision as a wall before the movement has even been attempted.
        boolean canStep=walking && rise>0 && rise<=session.player.getStepHeight()+0.08 && session.world.isSpaceEmpty(session.player,moved.offset(0,rise,0));
        if(!session.world.isSpaceEmpty(session.player,moved) && !canStep){walkingBlocked|=walking;failedVantages.put(path.getLast(),ticks+60);path=List.of();lastRoute=ticks-15;lastGate+=" destination obstructed";return;}
        if(walking && !supported(session.player.getBoundingBox().offset(step))){walkingBlocked=true;failedVantages.put(path.getLast(),ticks+60);path=List.of();lastRoute=ticks-15;return;}
        flying(!walking);session.player.setSprinting(sprintWalk || !walking && rate>0.3);
        look(hit.getPos());session.player.setVelocity(walking?new Vec3d(step.x,session.player.getVelocity().y,step.z):step);
    }
    private void beginSearch(int mode) {
        routeMode=mode;
        var goals=mode==0?routeGoals.stream().filter(this::ground).toList():routeGoals;
        int direct=goals.stream().mapToInt(routeStart::distance).min().orElse(0);
        int budget=mode==0?Math.max(4,(direct*3+1)/2+2):Integer.MAX_VALUE;
        search=new FlightRoute.Search(routeStart,goals,p->mode==0?(ground(p)?1:0):mode==1?(free(p)?1:0):passageCost(p),mode==0?2000:30000,budget);
    }
    private void shortcutPath() {
        if(path==shortcutChecked || path.size()<2)return;
        if(session.world.isSpaceEmpty(session.player,session.player.getBoundingBox())) {
            Vec3d from=new Vec3d(session.player.getX(),session.player.getY(),session.player.getZ());
            for(int i=Math.min(8,path.size()-1);i>0;i--) {
                var p=path.get(i);double height=feetHeight(p)+0.08;
                if(!free(p) || walking && Math.abs(height-from.y)>0.12)continue;
                Vec3d to=new Vec3d(p.x()+0.5,walking?from.y:height,p.z()+0.5),delta=to.subtract(from);
                int count=(int)Math.ceil(delta.length()/0.5);Box previous=session.player.getBoundingBox();boolean clear=true;
                for(int n=1;n<=count;n++) {
                    Box next=session.player.getBoundingBox().offset(delta.multiply((double)n/count));
                    if(!session.world.isSpaceEmpty(session.player,previous.union(next)) || walking && !supported(next)){clear=false;break;}
                    previous=next;
                }
                if(clear){path=path.subList(i,path.size());break;}
            }
        }
        shortcutChecked=path;
    }
    private void look(Vec3d point) {
        Vec3d d=point.subtract(session.player.getEyePos());
        float yaw=(float)(Math.toDegrees(Math.atan2(d.z,d.x))-90),pitch=(float)-Math.toDegrees(Math.atan2(d.y,Math.hypot(d.x,d.z)));
        if(target!=null) {
            String facing=building.properties.get(target).get("facing");
            if(facing!=null) {
                float f=switch(facing){case "north"->180;case "south"->0;case "west"->90;case "east"->-90;default->yaw;};
                yaw=f;
            }
        }
        session.player.setYaw(yaw);session.player.setHeadYaw(yaw);session.player.setPitch(pitch);
    }
    private boolean placementLook(BlockHitResult hit,BlockState desired) {
        look(hit.getPos());
        BlockState predicted=predictedPlacement(hit,session.player.getMainHandStack(),building.position(target));
        // Furniture faces the player; stairs and beds face away. Six-way blocks
        // such as barrels also use pitch, so reversing yaw alone cannot face up.
        if(building.placementMatches(target,predicted))return true;
        float yaw=session.player.getYaw(),pitch=session.player.getPitch();
        float[] turns=new float[]{yaw+180,yaw,yaw+90,yaw-90};
        if(building.properties.get(target).containsKey("rotation")){turns=new float[16];for(int i=0;i<16;i++)turns[i]=i*22.5f;}
        for(float tilt:new float[]{pitch,0,90,-90})for(float turn:turns) {
            session.player.setYaw(turn);session.player.setHeadYaw(turn);session.player.setPitch(tilt);
            predicted=predictedPlacement(hit,session.player.getMainHandStack(),building.position(target));
            if(building.placementMatches(target,predicted))return true;
        }
        session.player.setYaw(yaw);session.player.setHeadYaw(yaw);session.player.setPitch(pitch);
        return false;
    }
    private BlockState predictedPlacement(BlockHitResult hit,ItemStack stack,BlockPos pos) {
        if(!(stack.getItem() instanceof net.minecraft.item.BlockItem item))return null;
        var context=item.getPlacementContext(new net.minecraft.item.ItemPlacementContext(session.player,Hand.MAIN_HAND,stack,hit));
        if(context==null || !context.getBlockPos().equals(pos) || !context.canPlace())return null;
        return ((com.dummymod.mixin.BlockItemPlacementAccessor)item).dummymod$placementState(context);
    }
    private boolean placementTurnReady(BlockPos pos) {
        var props=building.properties.get(target);
        if(!props.containsKey("facing") && !props.containsKey("rotation"))return true;
        float yaw=MathHelper.wrapDegrees(session.player.getYaw()),pitch=session.player.getPitch();
        // Server LivingEntity.getYaw(1) reads headYaw, which follows body yaw
        // during its tick. A look packet and use packet in the same tick can
        // therefore place six-way blocks using the previous head direction.
        if(!pos.equals(turnTarget) || Math.abs(MathHelper.wrapDegrees(yaw-turnYaw))>0.01 || Math.abs(pitch-turnPitch)>0.01) {
            turnTarget=pos.toImmutable();turnYaw=yaw;turnPitch=pitch;turnStarted=ticks;
            session.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.LookAndOnGround(yaw,pitch,session.player.isOnGround(),session.player.horizontalCollision));
        }
        return ticks-turnStarted>=2;
    }
    private void postpone(){if(target!=null)postponed.put(target,ticks+120);target=null;actionHit=null;turnTarget=null;path=List.of();search=null;failedRoutes=0;}
    private static FlightRoute.Point point(BlockPos p){return new FlightRoute.Point(p.getX(),p.getY(),p.getZ());}
}
