package com.dummymod.dummy;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import java.lang.reflect.Method;

/** Rebuild Sound Physics' cached world after an account switch, before sound starts. */
public final class SoundCompatibility {
    private static boolean checked;
    private static Method refresh;
    private static volatile Method cachedClone;
    static void refresh(MinecraftClient client) {
        if(client.world==null || client.player==null)return;
        if(!checked) {
            checked=true;
            if(FabricLoader.getInstance().isModLoaded("sound_physics_remastered"))try {
                refresh=Class.forName("com.sonicether.soundphysics.utils.LevelAccessUtils").getMethod("tickLevelCache",ClientWorld.class);
                cachedClone=Class.forName("com.sonicether.soundphysics.world.CachingClientLevel").getMethod("sound_physics_remastered$getCachedClone");
            } catch(ReflectiveOperationException ignored){}
        }
        if(refresh!=null)try{refresh.invoke(null,client.world);}catch(ReflectiveOperationException error) {
            org.slf4j.LoggerFactory.getLogger("DummyMod").debug("Sound Physics cache refresh failed ({})",error.getClass().getSimpleName());
        }
    }
    public static Object captureCache() {
        ClientWorld world=MinecraftClient.getInstance().world;
        if(world==null || cachedClone==null)return null;
        try{return cachedClone.invoke(world);}catch(ReflectiveOperationException ignored){return null;}
    }
}
