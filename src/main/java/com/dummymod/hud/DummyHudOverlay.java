package com.dummymod.hud;

import com.dummymod.dummy.DummyManager;
import com.dummymod.dummy.PlayerSession;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

import java.util.List;

public class DummyHudOverlay {
    public static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options.hudHidden || client.player == null) return;

        List<PlayerSession> sessions = DummyManager.getSessions();
        if (sessions.size() <= 1 && !DummyManager.isControllingDummy()) return;

        PlayerSession active = DummyManager.getActiveSession();
        PlayerSession next = DummyManager.getNextSession();
        if (active == null) return;

        String nextName = next != null ? next.displayName() : active.displayName();
        String botStatus = active.botController != null ? active.botController.getStatus() : "Ожидание";
        int clickerCps = active.autoclicker.enabled ? active.autoclicker.getCps() : 0;
        String textLine = "§6[AltManager+] §fУправление: §e" + active.displayName()
                + " §8| §7[V] -> §b" + nextName
                + " §8| §a🤖 " + botStatus
                + " §8| §c⚔ " + clickerCps + " CPS";

        int x = 6;
        int y = 6;
        int textWidth = client.textRenderer.getWidth(textLine);
        int border = active.main ? 0xFFCC9A27 : 0xFFFFAA33;
        context.fill(x - 4, y - 4, x + textWidth + 6, y + 13, 0xB0101010);
        context.drawStrokedRectangle(x - 4, y - 4, textWidth + 10, 17, border);
        context.drawTextWithShadow(client.textRenderer, Text.literal(textLine), x, y, 0xFFFFFF);
    }
}
