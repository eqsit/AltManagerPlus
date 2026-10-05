package com.dummymod.mixin;

import com.dummymod.dummy.SoundCompatibility;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep one immutable sound-world snapshot for the whole audio-thread calculation. */
@Pseudo
@Mixin(targets="com.sonicether.soundphysics.SoundPhysics",remap=false)
public abstract class SoundPhysicsMixin {
    @Unique private static final ThreadLocal<Object> dummymod$soundWorld=new ThreadLocal<>();
    @Shadow(remap=false) public static void setDefaultEnvironment(int source){throw new AssertionError();}
    @Inject(method="evaluateEnvironment",at=@At("HEAD"),cancellable=true,require=0,remap=false)
    private static void dummymod$captureSoundWorld(int source,double x,double y,double z,SoundCategory category,Identifier name,boolean auxiliary,CallbackInfoReturnable<Vec3d> ci) {
        Object cache=SoundCompatibility.captureCache();
        if(cache==null){dummymod$soundWorld.remove();setDefaultEnvironment(source);ci.setReturnValue(null);}
        else dummymod$soundWorld.set(cache);
    }
    @Inject(method="getLevelProxy",at=@At("HEAD"),cancellable=true,require=0,remap=false)
    private static void dummymod$useCapturedSoundWorld(CallbackInfoReturnable<Object> ci) {
        Object cache=dummymod$soundWorld.get();if(cache!=null)ci.setReturnValue(cache);
    }
    @Inject(method="evaluateEnvironment",at=@At("RETURN"),require=0,remap=false)
    private static void dummymod$releaseSoundWorld(CallbackInfoReturnable<Vec3d> ci){dummymod$soundWorld.remove();}
}
