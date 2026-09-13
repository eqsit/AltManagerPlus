package com.dummymod;

import com.dummymod.dummy.DummyManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class DummyMod implements ClientModInitializer {
    public static final String MOD_ID = "dummymod";
    public static KeyBinding SWITCH_KEY;

    @Override
    public void onInitializeClient() {
        SWITCH_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.dummymod.switch",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                KeyBinding.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (SWITCH_KEY.wasPressed()) {
                DummyManager.toggleControl();
            }
            DummyManager.tick(client);
        });
    }
}
