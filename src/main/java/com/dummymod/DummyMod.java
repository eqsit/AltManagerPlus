package com.dummymod;

import com.dummymod.dummy.DummyManager;
import com.dummymod.gui.AltManagerScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class DummyMod implements ClientModInitializer {
    public static final String MOD_ID = "dummymod";
    public static KeyBinding OPEN_GUI_KEY, SWITCH_KEY, SPAWN_RANDOM_KEY, TOGGLE_AUTOCLICKER_KEY;
    public static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));

    @Override
    public void onInitializeClient() {
        OPEN_GUI_KEY = bind("key.dummymod.open_gui", GLFW.GLFW_KEY_RIGHT_SHIFT);
        SWITCH_KEY = bind("key.dummymod.switch_next", GLFW.GLFW_KEY_V);
        SPAWN_RANDOM_KEY = bind("key.dummymod.spawn_random", GLFW.GLFW_KEY_UNKNOWN);
        TOGGLE_AUTOCLICKER_KEY = bind("key.dummymod.toggle_autoclicker", GLFW.GLFW_KEY_UNKNOWN);


        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_GUI_KEY.wasPressed()) client.setScreen(new AltManagerScreen(client.currentScreen));
            while (SWITCH_KEY.wasPressed()) DummyManager.switchNextSession();
            while (SPAWN_RANDOM_KEY.wasPressed()) DummyManager.spawnRandomDummy();
            while (TOGGLE_AUTOCLICKER_KEY.wasPressed()) DummyManager.toggleAutoclicker(DummyManager.getActiveSession());
            DummyManager.tick(client);
        });
    }

    private static KeyBinding bind(String id, int code) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(id, InputUtil.Type.KEYSYM, code, CATEGORY));
    }
}
