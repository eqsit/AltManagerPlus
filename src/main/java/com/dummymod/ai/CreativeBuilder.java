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
    private int ticks,lastAction,lastRoute,failedRoutes;
    private boolean controlling;
    private boolean clearing;
    private boolean preferWalking=true,walking;
    private FlightSpeed speed=FlightSpeed.AUTO;
    private String lastTarget="нет доступной точки";
    private String lastInteraction="none",lastGate="none";
    private BlockPos awaitingConfirmation;
    private boolean awaitingBreak;
    private int rejectedActions;
    private Vec3d rejectedPose;
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
    CreativeBuilder(PlayerSession session,Building building){
        this.session=session;this.building=building;
        var client=net.minecraft.client.MinecraftClient.getInstance();
        String server=client.getCurrentServerEntry()==null?"local:"+(client.getServer()==null?"unknown":client.getServer().getSavePath(net.minecraft.util.WorldSavePath.ROOT)):client.getCurrentServerEntry().address;
        String key=server+"|"+session.displayName().toLowerCase(Locale.ROOT)+"|"+session.world.getRegistryKey().getValue();
        String id=UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        passage=new PassageJournal(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("dummymod-ai-passages").resolve(id+".json"));
    }
    boolean repairing(){return !passage.entries().isEmpty();}
    boolean planning(){return search!=null && !search.done();}
    boolean controls(){return controlling;}
    void speed(FlightSpeed value){speed=value==null?FlightSpeed.AUTO:value;}
    void preferWalking(boolean value){if(preferWalking!=value){preferWalking=value;retry();}}
    static boolean available(PlayerSession s){return s.player!=null && s.player.isCreative() && s.player.getAbilities().allowFlying;}
    void release() {
        if(controlling && session.player!=null){session.player.setVelocity(Vec3d.ZERO);session.player.input=new Input();session.player.setSprinting(false);}
        controlling=false;target=null;path=List.of();search=null;awaitingConfirmation=null;navigationAction=null;
    }
    void retry(){target=null;path=List.of();search=null;postponed.clear();failedVantages.clear();failedRoutes=0;}
    String diagnostic(){return lastTarget+", position="+new Vec3d(session.player.getX(),session.player.getY(),session.player.getZ())+", route="+path.size()+", search="+(search==null?"none":routeMode+":"+search.visited())+", repairs="+passage.entries().size()+", travel="+(walking?"walk":"fly")+", gate="+lastGate+", interaction="+lastInteraction;}
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
            var order=Comparator.comparingInt(BuildPlan.Cell::y);
            if(clearing)order=order.reversed();
            List<BuildPlan.Cell> eligible=new ArrayList<>();Integer layer=null;
            for(var c:stage.keySet().stream().sorted(order).toList()) {
                if(layer!=null && (clearing?c.y()<layer-1:c.y()>layer+1))break;
                if(!pending(c) || postponed.getOrDefault(c,0)>ticks || !building.loaded(c))continue;
                var props=building.properties.get(c);
                if(!clearing && Set.of("head","upper").contains(props.getOrDefault("part",props.getOrDefault("half",""))))continue;
                if(!clearing && !building.states.get(c).isAir() && session.world.getBlockState(building.position(c)).isReplaceable() && anchor(c,building.states.get(c))==null)continue;
                if(layer==null)layer=c.y();eligible.add(c);
            }
            target=eligible.stream().min(Comparator.comparingDouble(c->session.player.squaredDistanceTo(Vec3d.ofCenter(building.position(c))))).orElse(null);
            if(target==null){if(!clearing)buildAnchor(stage);return;}
            lastRoute=ticks-40;failedRoutes=0;failedVantages.clear();
            rejectedActions=0;rejectedPose=null;
        }
        BlockPos pos=building.position(target);BlockState desired=building.states.get(target),current=session.world.getBlockState(pos);
        boolean combine=desired.getBlock()==current.getBlock() && "double".equals(building.properties.get(target).get("type"));
        boolean breaking=clearing || desired.isAir() || (!combine && !current.isReplaceable() && !building.matches(target,current));
        lastTarget=building.position(target).toShortString()+", "+net.minecraft.registry.Registries.BLOCK.getId(current.getBlock())+", "+(breaking?"ломание":"установка");
        if(breaking && !allowBreaking){release();return;}
        double slabTop=combine?current.getOutlineShape(session.world,pos).getBoundingBox().maxY:1;
        BlockHitResult hit=breaking?breakHit(pos):combine?new BlockHitResult(new Vec3d(pos.getX()+0.5,pos.getY()+slabTop-0.001,pos.getZ()+0.5),Direction.UP,pos,false):anchor(target,desired);
        if(hit==null){postpone();return;}
        double reach=Math.min(session.player.getBlockInteractionRange(),4.5)-0.2;
        Vec3d eye=session.player.getEyePos();
        boolean bodyBlocksPlacement=!breaking && session.player.getBoundingBox().intersects(new Box(pos));
        boolean inReach=eye.squaredDistanceTo(hit.getPos())<=reach*reach,canSee=visible(eye,hit);
        lastGate="body="+bodyBlocksPlacement+" reach="+inReach+" visible="+canSee+" support="+hit.getBlockPos().toShortString()+" side="+hit.getSide()+" hit="+hit.getPos();
        if(!bodyBlocksPlacement && inReach && canSee && (rejectedPose==null || eye.squaredDistanceTo(rejectedPose)>0.25)) {
            path=List.of();search=null;
            if(breaking)look(hit.getPos());else {equip(desired);placementLook(hit,desired);}
            if(preferWalking && supported(session.player.getBoundingBox()))flying(false);
            if(ticks-lastAction<(breaking?5:1))return;
            lastAction=ticks;
            session.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.LookAndOnGround(session.player.getYaw(),session.player.getPitch(),session.player.isOnGround(),session.player.horizontalCollision));
            if(breaking){Tools.select(session,current);lastInteraction="break="+session.interactionManager.attackBlock(pos,hit.getSide());}
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
        flyTo(hit,pos,!breaking);
    }
    private void equip(BlockState desired) {
        var inv=session.player.getInventory();int slot=8;
        ItemStack wanted=new ItemStack(desired.getBlock().asItem(),Math.min(64,desired.getBlock().asItem().getMaxCount()));
        if(inv.getStack(slot).getItem()!=wanted.getItem() || inv.getStack(slot).isEmpty()) {
            inv.setStack(slot,wanted);session.interactionManager.clickCreativeStack(wanted,36+slot);
        }
        inv.setSelectedSlot(slot);
    }
    private BlockHitResult anchor(BuildPlan.Cell cell,BlockState desired) {
        return anchor(building.position(cell),desired,building.properties.get(cell));
    }
    private BlockHitResult anchor(BlockPos target,BlockState desired,Map<String,String> props) {
        BlockHitResult best=null;double distance=Double.MAX_VALUE;boolean bestVisible=false;
        for(Direction side:Direction.values()) {
            BlockPos support=target.offset(side.getOpposite());BlockState state=session.world.getBlockState(support);
            if(state.isAir() || state.isReplaceable() || state.getCollisionShape(session.world,support).isEmpty())continue;
            String half=props==null?null:props.get("half"),type=props==null?null:props.get("type"),axis=props==null?null:props.get("axis");
            if(axis!=null && !side.getAxis().name().toLowerCase(Locale.ROOT).equals(axis))continue;
            if((desired.isOf(net.minecraft.block.Blocks.TORCH) || desired.isOf(net.minecraft.block.Blocks.SOUL_TORCH) || desired.isOf(net.minecraft.block.Blocks.REDSTONE_TORCH)) && side!=Direction.UP)continue;
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
            double d=session.player.getEyePos().squaredDistanceTo(point);
            BlockHitResult candidate=new BlockHitResult(point,side,support,false);
            boolean sees=visible(session.player.getEyePos(),candidate);
            if(best==null || sees && !bestVisible || sees==bestVisible && d<distance){best=candidate;distance=d;bestVisible=sees;}
        }
        return best;
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
    private boolean free(FlightRoute.Point p) {
        if(p.y()<session.world.getBottomY() || p.y()+2>session.world.getTopYInclusive() || !session.world.isChunkLoaded(p.x()>>4,p.z()>>4))return false;
        return session.world.isSpaceEmpty(session.player,new Box(p.x()+0.18,p.y()+0.05,p.z()+0.18,p.x()+0.82,p.y()+1.85,p.z()+0.82));
    }
    private boolean supported(Box body){return !session.world.isSpaceEmpty(session.player,body.offset(0,-0.15,0));}
    private boolean ground(FlightRoute.Point p){return free(p) && supported(new Box(p.x()+0.18,p.y()+0.05,p.z()+0.18,p.x()+0.82,p.y()+1.85,p.z()+0.82));}
    private static Box body(FlightRoute.Point p){return new Box(p.x()+0.18,p.y()+0.05,p.z()+0.18,p.x()+0.82,p.y()+1.85,p.z()+0.82);}
    private static BlockPos position(PassageJournal.Entry e){return new BlockPos(e.x(),e.y(),e.z());}
    private static BlockState original(PassageJournal.Entry e){return net.minecraft.registry.Registries.BLOCK.get(net.minecraft.util.Identifier.of(e.block())).getDefaultState();}
    private void forget(PassageJournal.Entry e) {
        try{passage.restored(e);}catch(java.io.IOException failure){building.navigationFailure("Не удалось сохранить восстановленный проход.");}
    }
    /** Only ordinary, state-free full cubes can be restored exactly by normal placement. */
    private boolean removable(BlockPos pos) {
        BlockState state=session.world.getBlockState(pos);
        var relative=new BuildPlan.Cell(pos.getX()-building.plan.origin[0],pos.getY()-building.plan.origin[1],pos.getZ()-building.plan.origin[2]);
        if(building.states.containsKey(relative) || state.hasBlockEntity() || !state.getEntries().isEmpty() || state.getBlock().asItem()==net.minecraft.item.Items.AIR || state.getHardness(session.world,pos)<0 || !state.isFullCube(session.world,pos) || !state.getFluidState().isEmpty())return false;
        // Never remove a container, a fluid barrier, or support for a fragile decoration.
        for(Direction d:Direction.values()) {
            BlockPos adjacent=pos.offset(d);BlockState neighbor=session.world.getBlockState(adjacent);
            if(!neighbor.getFluidState().isEmpty() || neighbor.getBlock() instanceof net.minecraft.block.FallingBlock || neighbor.getBlock() instanceof net.minecraft.block.TorchBlock || neighbor.getBlock() instanceof net.minecraft.block.WallTorchBlock || neighbor.getBlock() instanceof net.minecraft.block.LanternBlock || neighbor.getBlock() instanceof net.minecraft.block.DoorBlock || neighbor.getBlock() instanceof net.minecraft.block.BedBlock || neighbor.hasBlockEntity())return false;
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
    private boolean usable(BlockHitResult hit){return hit!=null && session.player.getEyePos().squaredDistanceTo(hit.getPos())<Math.pow(Math.min(session.player.getBlockInteractionRange(),4.5)-0.2,2) && visible(session.player.getEyePos(),hit);}
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
        var entries=new ArrayList<>(passage.entries());
        // A ceiling opening is closed from its farthest layer toward the player.
        entries.sort(Comparator.comparingDouble((PassageJournal.Entry e)->session.player.squaredDistanceTo(Vec3d.ofCenter(position(e)))).reversed());
        PassageJournal.Entry approach=null;BlockHitResult approachHit=null;
        for(var e:entries) {
            BlockPos pos=position(e);
            if(!session.world.isChunkLoaded(pos.getX()>>4,pos.getZ()>>4))continue;
            if(session.world.getBlockState(pos).equals(original(e))){forget(e);continue;}
            if(original(e).isAir()) {
                if(!onlyRepairs)continue;
                if(!allowPassage){building.navigationFailure("Для удаления временных опор включи ломание в меню ИИ.");return true;}
                if(!session.world.getBlockState(pos).isOf(Blocks.STONE)){building.navigationFailure("Временная опора изменена: "+pos.toShortString()+". Проверь её перед продолжением.");return true;}
                BlockHitResult hit=breakHit(pos);
                if(usable(hit)) {
                    if(ticks-lastAction<5)return true;
                    navigationInteract(e,hit,false);navigationRestore=true;return true;
                }
                if(approach==null){approach=e;approachHit=hit;}
                continue;
            }
            if(!session.world.getBlockState(pos).isReplaceable()){building.navigationFailure("Временный проход изменился: "+pos.toShortString()+". Сохранённые блоки оставлены для восстановления.");return true;}
            if(session.player.getBoundingBox().expand(0.2).intersects(new Box(pos)) || path.stream().anyMatch(p->body(p).intersects(new Box(pos))))continue;
            BlockHitResult hit=anchor(pos,original(e),Map.of());
            if(usable(hit)){navigationInteract(e,hit,true);return true;}
            if(onlyRepairs && approach==null && hit!=null){approach=e;approachHit=hit;}
        }
        if(approach!=null){allowPassage=false;flyTo(approachHit,position(approach),!original(approach).isAir());return true;}
        return false;
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
        if(search!=null) {
            search.advance(600,3_000_000L);
            if(!search.done()){lastGate="поиск подхода "+routeMode+", узлов="+search.visited();return;}
            path=search.result();search=null;
            if(path.isEmpty() && routeMode<2) {
                if(routeMode==0)beginSearch(1);
                else if(allowPassage && passage.entries().stream().allMatch(e->original(e).isAir()))beginSearch(2);
                if(search!=null)return;
            }
            if(!path.isEmpty()) {
                if(routeMode==2) {
                    Set<BlockPos> opening=new HashSet<>();path.forEach(p->opening.addAll(blockers(p)));
                    if(opening.size()>6){path=List.of();lastGate="проход требует слишком много блоков";}
                }
                walking=routeMode==0;
                if(!path.isEmpty() && free(routeStart)) {
                    Vec3d center=new Vec3d(routeStart.x()+0.5,walking?session.player.getY():routeStart.y()+0.08,routeStart.z()+0.5);
                    if(center.squaredDistanceTo(new Vec3d(session.player.getX(),session.player.getY(),session.player.getZ()))>0.000625) {
                        var centered=new ArrayList<FlightRoute.Point>();centered.add(routeStart);centered.addAll(path);path=centered;
                    }
                }
            }
            if(path.isEmpty() && ++failedRoutes>=3){postpone();return;}
        }
        if(path.isEmpty()) {
            if(ticks-lastRoute<15)return;
            lastRoute=ticks;routeStart=point(session.player.getBlockPos());
            List<FlightRoute.Point> candidates=new ArrayList<>();
            for(int y=-2;y<=3;y++)for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++) {
                FlightRoute.Point p=new FlightRoute.Point(pos.getX()+x,pos.getY()+y,pos.getZ()+z);
                Vec3d eye=new Vec3d(p.x()+0.5,p.y()+session.player.getStandingEyeHeight(),p.z()+0.5);
                if(failedVantages.getOrDefault(p,0)<=ticks && eye.squaredDistanceTo(hit.getPos())<16 && (!placing || !body(p).intersects(new Box(pos))) && free(p) && visible(eye,hit))candidates.add(p);
            }
            candidates.sort(Comparator.<FlightRoute.Point>comparingInt(p->preferWalking && ground(p)?0:1).thenComparingInt(p->p.distance(routeStart)));
            routeGoals=candidates.stream().limit(16).toList();walking=false;
            boolean sameFloor=routeGoals.stream().anyMatch(p->Math.abs(p.y()-routeStart.y())<=1);
            beginSearch(preferWalking && sameFloor && supported(session.player.getBoundingBox())?0:1);
            return;
        }
        if(path.isEmpty())return;
        FlightRoute.Point next=path.getFirst();
        if(!free(next)) {
            if(excavate(next))return;
            path=List.of();search=null;lastRoute=ticks-15;return;
        }
        Vec3d dest=new Vec3d(next.x()+0.5,next.y()+0.08,next.z()+0.5);
        Vec3d delta=dest.subtract(new Vec3d(session.player.getX(),session.player.getY(),session.player.getZ()));
        if(walking)delta=new Vec3d(delta.x,0,delta.z);
        if(delta.length()<(path.size()==1?0.025:0.16)){
            // Reaching a theoretically visible cell is insufficient if the actual
            // eye is still blocked. Do not select the same failed pose forever.
            if(path.size()==1)failedVantages.put(next,ticks+100);
            path=path.subList(1,path.size());if(path.isEmpty())lastRoute=ticks-15;return;
        }
        boolean sprintWalk=walking && path.size()>2;
        double rate=walking?(sprintWalk?0.28:0.22):speed.step(path.size());
        Vec3d step=delta.normalize().multiply(Math.min(rate,delta.length()));
        // The current box can slightly intersect a freshly acknowledged block.
        // Including it in every swept check prevents even moving out of the block,
        // leaving the bot immobile until somebody nudges it. Check the destination;
        // vanilla movement still resolves collisions along the actual motion.
        if(!session.world.isSpaceEmpty(session.player,session.player.getBoundingBox().offset(step))){failedVantages.put(path.getLast(),ticks+60);path=List.of();lastRoute=ticks-15;lastGate+=" destination obstructed";return;}
        if(walking && !supported(session.player.getBoundingBox().offset(step))){failedVantages.put(path.getLast(),ticks+60);path=List.of();lastRoute=ticks-15;return;}
        flying(!walking);session.player.setSprinting(sprintWalk || !walking && rate>0.3);
        look(hit.getPos());session.player.setVelocity(walking?new Vec3d(step.x,session.player.getVelocity().y,step.z):step);
    }
    private void beginSearch(int mode) {
        routeMode=mode;
        var goals=mode==0?routeGoals.stream().filter(this::ground).toList():routeGoals;
        search=new FlightRoute.Search(routeStart,goals,p->mode==0?(ground(p)?1:0):mode==1?(free(p)?1:0):passageCost(p),mode==0?2000:30000);
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
    private void placementLook(BlockHitResult hit,BlockState desired) {
        look(hit.getPos());
        var property=desired.getBlock().getStateManager().getProperty("facing");
        if(property==null || target==null || !building.properties.get(target).containsKey("facing"))return;
        var context=new net.minecraft.item.ItemPlacementContext(session.player,Hand.MAIN_HAND,session.player.getMainHandStack(),hit);
        BlockState predicted=desired.getBlock().getPlacementState(context);
        // Furniture faces the player; stairs and beds face away. Use the actual
        // block placement rule instead of applying one yaw to every block type.
        if(predicted!=null && !predicted.get(property).equals(desired.get(property))) {
            session.player.setYaw(session.player.getYaw()+180);session.player.setHeadYaw(session.player.getYaw());
        }
    }
    private void postpone(){if(target!=null)postponed.put(target,ticks+120);target=null;path=List.of();search=null;failedRoutes=0;}
    private static FlightRoute.Point point(BlockPos p){return new FlightRoute.Point(p.getX(),p.getY(),p.getZ());}
}
