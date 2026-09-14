package com.dummymod.dummy.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.IBaritoneProvider;
import baritone.api.utils.IBaritoneClientContext;
import baritone.api.utils.input.Input;
import com.dummymod.dummy.PlayerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Session-safe bridge to the patched Baritone per-instance context API. */
public final class BaritoneBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("DummyMod-Baritone");

    private BaritoneBridge() {}

    /**
     * Resolve one registered Baritone per PlayerSession. No MinecraftClient
     * fields are swapped while creating or using the instance.
     */
    public static IBaritone resolve(PlayerSession session) {
        if (session == null || session.player == null || session.world == null || session.interactionManager == null) {
            return null;
        }
        IBaritone cached = session.baritone;
        if (cached != null) return cached;

        try {
            IBaritoneProvider provider = BaritoneAPI.getProvider();
            IBaritoneClientContext context = session.baritoneContext;
            if (context == null) {
                context = new SessionClientContext(session);
                session.baritoneContext = context;
            }

            IBaritone baritone;
            if (session.main) {
                // Keep upstream's primary instance and chat integration, but pin
                // its account view to the AltManager main session so switching
                // the visible Minecraft player cannot retarget an active path.
                baritone = provider.getPrimaryBaritone();
                baritone.bindClientContext(context);
            } else {
                baritone = provider.createBaritone(context);
            }
            session.baritone = baritone;
            return baritone;
        } catch (Throwable t) {
            LOGGER.warn("Unable to resolve Baritone for {}", session.displayName(), t);
            return null;
        }
    }

    public static void destroy(PlayerSession session) {
        if (session == null) return;
        IBaritone baritone = session.baritone;
        try {
            if (baritone != null && !session.main) {
                BaritoneAPI.getProvider().destroyBaritone(baritone);
            } else if (baritone != null) {
                baritone.getInputOverrideHandler().clearAllKeys();
                baritone.getPathingBehavior().cancelEverything();
            }
        } catch (Throwable t) {
            LOGGER.debug("Unable to destroy Baritone for {}", session.displayName(), t);
        } finally {
            session.baritone = null;
            session.baritoneContext = null;
        }
    }

    public static boolean execute(PlayerSession session, String command) {
        if (session == null) return false;
        final String normalized = normalize(command);
        if (normalized.isEmpty()) return false;
        IBaritone baritone = resolve(session);
        if (baritone == null) return false;
        try {
            return baritone.getCommandManager().execute(normalized);
        } catch (Throwable t) {
            LOGGER.warn("Baritone command failed for {}: {}", session.displayName(), command, t);
            return false;
        }
    }

    private static String normalize(String command) {
        String cmd = command == null ? "" : command.trim();
        while (cmd.startsWith("#")) cmd = cmd.substring(1).trim();
        return cmd;
    }

    public static boolean ownsInput(PlayerSession session) {
        IBaritone baritone = session == null ? null : session.baritone;
        return baritone != null && ownsInputDirect(baritone);
    }

    public static boolean isAutomationActive(PlayerSession session) {
        IBaritone baritone = session == null ? null : session.baritone;
        if (baritone == null) return false;
        try {
            return ownsInputDirect(baritone)
                    || baritone.getFollowProcess().isActive()
                    || baritone.getMineProcess().isActive()
                    || baritone.getBuilderProcess().isActive()
                    || baritone.getExploreProcess().isActive()
                    || baritone.getFarmProcess().isActive()
                    || baritone.getCustomGoalProcess().isActive()
                    || baritone.getGetToBlockProcess().isActive()
                    || baritone.getElytraProcess().isActive();
        } catch (Throwable t) {
            return ownsInputDirect(baritone);
        }
    }

    private static boolean ownsInputDirect(IBaritone baritone) {
        try {
            if (baritone.getPathingBehavior().isPathing()) return true;
            for (Input input : Input.values()) {
                if (baritone.getInputOverrideHandler().isInputForcedDown(input)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
