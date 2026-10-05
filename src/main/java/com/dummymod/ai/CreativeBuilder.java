package com.dummymod.ai;

import com.dummymod.dummy.PlayerSession;
import net.minecraft.block.BlockState;
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
    private final Map<BuildPlan.Cell,Integer> postponed=new HashMap<>();
    private final Map<FlightRoute.Point,Integer> failedVantages=new HashMap<>();
    CreativeBuilder(PlayerSession session,Building building){this.session=session;this.building=building;}
    boolean controls(){return controlling;}
    void speed(FlightSpeed value){speed=value==null?FlightSpeed.AUTO:value;}
    void preferWalking(boolean value){if(preferWalking!=value){preferWalking=value;retry();}}
    static boolean available(PlayerSession s){return s.player!=null && s.player.isCreative() && s.player.getAbilities().allowFlying;}
    void release() {
        if(controlling && session.player!=null){session.player.setVelocity(Vec3d.ZERO);session.player.input=new Input();session.player.setSprinting(false);}
        controlling=false;target=null;path=List.of();awaitingConfirmation=null;
    }
    void retry(){target=null;path=List.of();postponed.clear();failedVantages.clear();failedRoutes=0;}
    String diagnostic(){return lastTarget+", position="+new Vec3d(session.player.getX(),session.player.getY(),session.player.getZ())+", route="+path.size()+", travel="+(walking?"walk":"fly")+", gate="+lastGate+", interaction="+lastInteraction;}
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
        session.player.input=new Input();
        session.player.setSprinting(false);
        if(session.player.getAbilities().flying)session.player.setVelocity(Vec3d.ZERO);
        else session.player.setVelocity(0,session.player.getVelocity().y,0);
        // Local placement is a prediction. Do not advance, change the held item,
        // or resend this action until its own world's server acknowledgement arrives.
        if(awaitingConfirmation!=null) {
            if(awaiting(awaitingConfirmation)){lastGate="ожидаю подтверждение сервера";return;}
            boolean accepted=awaitingBreak?session.world.getBlockState(awaitingConfirmation).isAir():target!=null && building.matches(target,session.world.getBlockState(awaitingConfirmation));
            if(accepted)rejectedActions=0;
            else if(++rejectedActions>=2) {
                // A predicted success can still be rejected by the server. Leave
                // this approach rather than repeatedly clicking from the same pose.
                rejectedPose=session.player.getEyePos();
                failedVantages.put(point(session.player.getBlockPos()),ticks+100);
                path=List.of();lastRoute=ticks-40;
            }
            awaitingConfirmation=null;
        }
        if(target!=null && (!pending(target) || !stage.containsKey(target) || postponed.getOrDefault(target,0)>ticks)){target=null;path=List.of();}
        if(target==null) {
            int layer=clearing?stage.keySet().stream().filter(this::pending).mapToInt(BuildPlan.Cell::y).max().orElse(-1):stage.keySet().stream().filter(this::pending).mapToInt(BuildPlan.Cell::y).min().orElse(-1);
            target=stage.keySet().stream().filter(c->(clearing?c.y()>=layer-1:c.y()<=layer+1) && pending(c) && postponed.getOrDefault(c,0)<=ticks)
                .filter(c->building.loaded(c) && (clearing || building.states.get(c).isAir() || !session.world.getBlockState(building.position(c)).isReplaceable() || anchor(c,building.states.get(c))!=null))
                .min(Comparator.comparingDouble(c->session.player.squaredDistanceTo(Vec3d.ofCenter(building.position(c))))).orElse(null);
            if(target==null)return;
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
            path=List.of();look(hit.getPos());
            if(preferWalking && supported(session.player.getBoundingBox()))flying(false);
            if(ticks-lastAction<(breaking?5:1))return;
            lastAction=ticks;
            session.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.LookAndOnGround(session.player.getYaw(),session.player.getPitch(),session.player.isOnGround(),session.player.horizontalCollision));
            if(breaking){Tools.select(session,current);lastInteraction="break="+session.interactionManager.attackBlock(pos,hit.getSide());}
            else {
                equip(desired);
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
        BlockPos target=building.position(cell);
        BlockHitResult best=null;double distance=Double.MAX_VALUE;
        for(Direction side:Direction.values()) {
            BlockPos support=target.offset(side.getOpposite());BlockState state=session.world.getBlockState(support);
            if(state.isAir() || state.isReplaceable() || state.getCollisionShape(session.world,support).isEmpty())continue;
            var props=building.properties.get(cell);
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
            if(d<distance && (best==null || visible(session.player.getEyePos(),candidate))){best=candidate;distance=d;}
        }
        return best;
    }
    private BlockHitResult breakHit(BlockPos p) {
        BlockHitResult best=null;double distance=Double.MAX_VALUE;
        var shape=session.world.getBlockState(p).getOutlineShape(session.world,p);
        Box bounds=shape.isEmpty()?new Box(0,0,0,1,1,1):shape.getBoundingBox();
        for(Direction side:Direction.values()) {
            Vec3d point=new Vec3d(p.getX()+(bounds.minX+bounds.maxX)/2,p.getY()+(bounds.minY+bounds.maxY)/2,p.getZ()+(bounds.minZ+bounds.maxZ)/2);
            point=switch(side){case UP->new Vec3d(point.x,p.getY()+bounds.maxY,point.z);case DOWN->new Vec3d(point.x,p.getY()+bounds.minY,point.z);case EAST->new Vec3d(p.getX()+bounds.maxX,point.y,point.z);case WEST->new Vec3d(p.getX()+bounds.minX,point.y,point.z);case SOUTH->new Vec3d(point.x,point.y,p.getZ()+bounds.maxZ);case NORTH->new Vec3d(point.x,point.y,p.getZ()+bounds.minZ);};
            BlockHitResult hit=new BlockHitResult(point,side,p,false);double d=session.player.getEyePos().squaredDistanceTo(point);
            if(d<distance && (best==null || visible(session.player.getEyePos(),hit))){best=hit;distance=d;}
        }
        return best;
    }
    private boolean visible(Vec3d eye,BlockHitResult hit) {
        Vec3d end=hit.getPos().add(Vec3d.of(hit.getSide().getVector()).multiply(-0.015));
        // Tall grass does not prevent clicking a solid support behind it.
        boolean solid=!session.world.getBlockState(hit.getBlockPos()).getCollisionShape(session.world,hit.getBlockPos()).isEmpty();
        BlockHitResult ray=session.world.raycast(new RaycastContext(eye,end,solid?RaycastContext.ShapeType.COLLIDER:RaycastContext.ShapeType.OUTLINE,RaycastContext.FluidHandling.NONE,session.player));
        return ray.getType()==HitResult.Type.BLOCK && ray.getBlockPos().equals(hit.getBlockPos());
    }
    private boolean free(FlightRoute.Point p) {
        if(p.y()<session.world.getBottomY() || p.y()+2>session.world.getTopYInclusive() || !session.world.isChunkLoaded(p.x()>>4,p.z()>>4))return false;
        return session.world.isSpaceEmpty(session.player,new Box(p.x()+0.18,p.y()+0.05,p.z()+0.18,p.x()+0.82,p.y()+1.85,p.z()+0.82));
    }
    private boolean supported(Box body){return !session.world.isSpaceEmpty(session.player,body.offset(0,-0.15,0));}
    private boolean ground(FlightRoute.Point p){return free(p) && supported(new Box(p.x()+0.18,p.y()+0.05,p.z()+0.18,p.x()+0.82,p.y()+1.85,p.z()+0.82));}
    private void flying(boolean value) {
        if(session.player.getAbilities().flying!=value){session.player.getAbilities().flying=value;session.player.sendAbilitiesUpdate();}
    }
    private void flyTo(BlockHitResult hit,BlockPos pos,boolean placing) {
        if(ticks-lastRoute>=40 || path.isEmpty()) {
            if(ticks-lastRoute<15)return;
            lastRoute=ticks;
            FlightRoute.Point start=point(session.player.getBlockPos());
            List<FlightRoute.Point> candidates=new ArrayList<>();
            for(int y=-2;y<=3;y++)for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++) {
                FlightRoute.Point p=new FlightRoute.Point(pos.getX()+x,pos.getY()+y,pos.getZ()+z);
                Vec3d eye=new Vec3d(p.x()+0.5,p.y()+session.player.getStandingEyeHeight(),p.z()+0.5);
                Box body=new Box(p.x()+0.18,p.y()+0.05,p.z()+0.18,p.x()+0.82,p.y()+1.85,p.z()+0.82);
                if(failedVantages.getOrDefault(p,0)<=ticks && eye.squaredDistanceTo(hit.getPos())<16 && (!placing || !body.intersects(new Box(pos))) && free(p) && visible(eye,hit))candidates.add(p);
            }
            candidates.sort(Comparator.<FlightRoute.Point>comparingInt(p->preferWalking && ground(p)?0:1).thenComparingInt(p->p.distance(start)));
            path=List.of();long until=System.nanoTime()+4_000_000L;
            walking=false;
            // Prefer a route over an existing floor. No placement or excavation
            // is used for navigation; a missing surface falls back to flight.
            if(preferWalking && supported(session.player.getBoundingBox())) {
                for(FlightRoute.Point goal:candidates.stream().filter(this::ground).limit(6).toList()) {
                    path=route(start,goal,true);
                    if(!path.isEmpty()){walking=true;break;}
                    if(System.nanoTime()>until)break;
                }
            }
            if(!walking) {
            for(FlightRoute.Point goal:candidates.stream().limit(6).toList()) {
                // A* has no steps when both endpoints are the same cell. The
                // actual player may still stand on its edge, blocking placement.
                path=route(start,goal,false);
                if(!path.isEmpty() || System.nanoTime()>until)break;
            }
            }
            if(path.isEmpty() && ++failedRoutes>=3){postpone();return;}
        }
        if(path.isEmpty())return;
        FlightRoute.Point next=path.getFirst();Vec3d dest=new Vec3d(next.x()+0.5,next.y()+0.08,next.z()+0.5);
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
    private List<FlightRoute.Point> route(FlightRoute.Point start,FlightRoute.Point goal,boolean onGround) {
        List<FlightRoute.Point> result=goal.equals(start)?List.of(goal):FlightRoute.find(start,goal,onGround?this::ground:this::free,1200);
        if(!result.isEmpty() && !goal.equals(start) && free(start)) {
            Vec3d center=new Vec3d(start.x()+0.5,onGround?session.player.getY():start.y()+0.08,start.z()+0.5);
            if(center.squaredDistanceTo(new Vec3d(session.player.getX(),session.player.getY(),session.player.getZ()))>0.000625) {
                var centered=new ArrayList<FlightRoute.Point>();centered.add(start);centered.addAll(result);result=centered;
            }
        }
        return result;
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
    private void postpone(){if(target!=null)postponed.put(target,ticks+120);target=null;path=List.of();failedRoutes=0;}
    private static FlightRoute.Point point(BlockPos p){return new FlightRoute.Point(p.getX(),p.getY(),p.getZ());}
}
