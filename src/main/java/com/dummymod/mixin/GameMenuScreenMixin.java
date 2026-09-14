package com.dummymod.mixin;

import com.dummymod.gui.AltManagerScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin extends Screen {
    protected GameMenuScreenMixin(Text title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void dummymod$addAltManagerButton(CallbackInfo ci) {
        addDrawableChild(ButtonWidget.builder(Text.literal("AltManager+"), b -> {
            if (client != null) client.setScreen(new AltManagerScreen(this));
        }).dimensions(6, 6, 92, 20).build());
    }
}
