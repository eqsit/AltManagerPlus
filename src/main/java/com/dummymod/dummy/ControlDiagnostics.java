package com.dummymod.dummy;

import com.dummymod.dummy.baritone.BaritoneBridge;
import com.dummymod.mixin.ClientPlayerEntitySprintDiagnostics;
import com.dummymod.mixin.EntityAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Foreground-only diagnostics for session control and sprint behavior.
 *
 * <p>The logger deliberately records only local control/gameplay state. It never
 * reads chat, connection addresses, credentials, cookies, proxy configuration,
 * or packet payloads. Transition logs are immediate; otherwise snapshots while
 * movement/sprint is requested are limited to one per second per session.</p>
 */
public final class ControlDiagnostics {
    private static final Logger LOGGER = LoggerFactory.getLogger("DummyControlDiag");
    private static final long REQUESTED_SNAPSHOT_INTERVAL_NANOS = 1_000_000_000L;
    private static final Map<PlayerSession, DiagState> STATES = Collections.synchronizedMap(new WeakHashMap<>());

    private ControlDiagnostics() {}

    public static void onSwitchAway(PlayerSession session, MinecraftClient client) {
        log(session, client, "switch-away", null, true);
    }

    public static void onSwitchTo(PlayerSession session, MinecraftClient client) {
        log(session, client, "switch-to", null, true);
    }

    public static void pollForeground(MinecraftClient client, String phase) {
        PlayerSession session = DummyManager.getActiveSession();
        if (!isForegroundDummy(session, client, session == null ? null : session.player)) return;
        log(session, client, phase, null, false);
    }

    public static void afterMovement(ClientPlayerEntity player, boolean sprintingBefore) {
        MinecraftClient client = MinecraftClient.getInstance();
        PlayerSession session = DummyManager.getActiveSession();
        if (!isForegroundDummy(session, client, player)) return;
        log(session, client, "movement-post", sprintingBefore, sprintingBefore != player.isSprinting());
    }

    private static boolean isForegroundDummy(PlayerSession session, MinecraftClient client, ClientPlayerEntity player) {
        return session != null
                && !session.main
                && client != null
                && player != null
                && session.player == player
                && client.player == player;
    }

    private static void log(PlayerSession session, MinecraftClient client, String phase,
                            Boolean sprintingBeforeMovement, boolean force) {
        if (!isForegroundDummy(session, client, session == null ? null : session.player)) return;

        try {
            Snapshot snapshot = Snapshot.capture(session, client, sprintingBeforeMovement);
            DiagState diagState;
            synchronized (STATES) {
                diagState = STATES.computeIfAbsent(session, ignored -> new DiagState());
            }

            long now = System.nanoTime();
            boolean transition = diagState.lastControlState == null
                    || !diagState.lastControlState.equals(snapshot.controlState);
            boolean requested = snapshot.forwardRequested || snapshot.sprintRequested;
            boolean rateLimitElapsed = now - diagState.lastRequestedSnapshotNanos >= REQUESTED_SNAPSHOT_INTERVAL_NANOS;

            if (!force && !transition && !(requested && rateLimitElapsed)) return;

            diagState.lastControlState = snapshot.controlState;
            if (requested) diagState.lastRequestedSnapshotNanos = now;

            LOGGER.info(
                    "[DummyControlDiag] phase={} tick={} sessionId={} name=\"{}\" role=\"{}\" " +
                    "toggleSprint={} storedSprintLatch={} sprintKeyPressed={} inputClass={} " +
                    "inputForward={} inputBackward={} inputLeft={} inputRight={} inputSprint={} inputJump={} inputSneak={} " +
                    "sprintBefore={} sprintAfter={} food={} saturation={} onGround={} horizontalCollision={} collidedSoftly={} " +
                    "usingItem={} sneaking={} swimming={} touchingWater={} submergedWater={} vehicle={} flying={} " +
                    "velocity=({},{},{}) clientPlayerOk={} clientWorldOk={} interactionManagerOk={} cameraEntityOk={} cameraFocusedOk={} " +
                    "baritoneOwnsInput={} baritoneActive={} " +
                    "canStartSprinting={} canSprint(flying)={} shouldStopSprinting={} isBlockedFromSprinting={} hasMovementInput={} canVehicleSprint={} " +
                    "shouldSlowDown={} isInSneakingPose={} isCrawling={} hasBlindnessEffect={} isGliding={} isPartlyTouchingWater={} pose={} reason={}",
                    safeToken(phase), snapshot.tick, session.id, safeName(session.displayName()), safeName(DummyManager.getRoleLabel(session)),
                    snapshot.toggleSprint, session.sprintKeyLatched, snapshot.sprintKeyPressed, snapshot.inputClass,
                    snapshot.input.forward(), snapshot.input.backward(), snapshot.input.left(), snapshot.input.right(), snapshot.input.sprint(),
                    snapshot.input.jump(), snapshot.input.sneak(), snapshot.sprintBefore, snapshot.sprintAfter,
                    snapshot.food, fmt(snapshot.saturation), snapshot.onGround, snapshot.horizontalCollision, snapshot.collidedSoftly,
                    snapshot.usingItem, snapshot.sneaking, snapshot.swimming, snapshot.touchingWater, snapshot.submergedWater,
                    snapshot.vehicle, snapshot.flying, fmt(snapshot.velocity.x), fmt(snapshot.velocity.y), fmt(snapshot.velocity.z),
                    snapshot.clientPlayerOk, snapshot.clientWorldOk, snapshot.interactionManagerOk,
                    snapshot.cameraEntityOk, snapshot.cameraFocusedOk, snapshot.baritoneOwnsInput, snapshot.baritoneActive,
                    snapshot.canStartSprinting, snapshot.canSprintFlying, snapshot.shouldStopSprinting, snapshot.isBlockedFromSprinting,
                    snapshot.hasMovementInput, snapshot.canVehicleSprint, snapshot.shouldSlowDown, snapshot.inSneakingPose,
                    snapshot.crawling, snapshot.blindness, snapshot.gliding, snapshot.partlyTouchingWater, snapshot.pose,
                    force ? "forced" : transition ? "transition" : "rate-limited-request"
            );
        } catch (Throwable t) {
            // Do not include exception messages here: third-party messages can contain
            // connection details. The exception type is enough to diagnose collection.
            LOGGER.debug("[DummyControlDiag] phase={} collectionError={}", safeToken(phase), t.getClass().getName());
        }
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String safeName(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder(Math.min(value.length(), 64));
        for (int i = 0; i < value.length() && out.length() < 64; i++) {
            char c = value.charAt(i);
            if (c >= 0x20 && c != 0x7f && c != '\\' && c != '"') out.append(c);
            else out.append('_');
        }
        return out.toString();
    }

    private static String safeToken(String value) {
        if (value == null || value.isEmpty()) return "unknown";
        StringBuilder out = new StringBuilder(Math.min(value.length(), 40));
        for (int i = 0; i < value.length() && out.length() < 40; i++) {
            char c = value.charAt(i);
            out.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
        }
        return out.toString();
    }

    private static final class DiagState {
        private ControlState lastControlState;
        private long lastRequestedSnapshotNanos;
    }

    /** Only discrete, control-relevant values participate in transition detection. */
    private record ControlState(
            boolean toggleSprint,
            boolean storedSprintLatch,
            boolean sprintKeyPressed,
            String inputClass,
            PlayerInput input,
            boolean sprinting,
            boolean onGround,
            boolean horizontalCollision,
            boolean collidedSoftly,
            boolean usingItem,
            boolean sneaking,
            boolean swimming,
            boolean touchingWater,
            boolean submergedWater,
            boolean vehicle,
            boolean flying,
            boolean clientPlayerOk,
            boolean clientWorldOk,
            boolean interactionManagerOk,
            boolean cameraEntityOk,
            boolean cameraFocusedOk,
            boolean baritoneOwnsInput,
            boolean baritoneActive,
            boolean canStartSprinting,
            boolean canSprintFlying,
            boolean shouldStopSprinting,
            boolean isBlockedFromSprinting,
            boolean hasMovementInput,
            boolean canVehicleSprint,
            boolean shouldSlowDown,
            boolean inSneakingPose,
            boolean crawling,
            boolean blindness,
            boolean gliding,
            boolean partlyTouchingWater,
            String pose
    ) {}

    private record Snapshot(
            int tick,
            boolean toggleSprint,
            boolean sprintKeyPressed,
            String inputClass,
            PlayerInput input,
            boolean sprintBefore,
            boolean sprintAfter,
            int food,
            float saturation,
            boolean onGround,
            boolean horizontalCollision,
            boolean collidedSoftly,
            boolean usingItem,
            boolean sneaking,
            boolean swimming,
            boolean touchingWater,
            boolean submergedWater,
            boolean vehicle,
            boolean flying,
            Vec3d velocity,
            boolean clientPlayerOk,
            boolean clientWorldOk,
            boolean interactionManagerOk,
            boolean cameraEntityOk,
            boolean cameraFocusedOk,
            boolean baritoneOwnsInput,
            boolean baritoneActive,
            boolean canStartSprinting,
            boolean canSprintFlying,
            boolean shouldStopSprinting,
            boolean isBlockedFromSprinting,
            boolean hasMovementInput,
            boolean canVehicleSprint,
            boolean shouldSlowDown,
            boolean inSneakingPose,
            boolean crawling,
            boolean blindness,
            boolean gliding,
            boolean partlyTouchingWater,
            String pose,
            boolean forwardRequested,
            boolean sprintRequested,
            ControlState controlState
    ) {
        private static Snapshot capture(PlayerSession session, MinecraftClient client, Boolean sprintingBeforeMovement) {
            ClientPlayerEntity player = session.player;
            Input currentInput = player.input;
            PlayerInput input = currentInput == null || currentInput.playerInput == null
                    ? PlayerInput.DEFAULT
                    : currentInput.playerInput;
            String inputClass = currentInput == null ? "null" : safeToken(currentInput.getClass().getName());
            boolean sprintAfter = player.isSprinting();
            boolean sprintBefore = sprintingBeforeMovement == null ? sprintAfter : sprintingBeforeMovement;
            boolean toggleSprint = Boolean.TRUE.equals(client.options.getSprintToggled().getValue());
            boolean sprintKeyPressed = client.options.sprintKey.isPressed();
            EntityAccessor collision = (EntityAccessor) player;
            boolean horizontalCollision = collision.dummymod$getHorizontalCollision();
            boolean collidedSoftly = collision.dummymod$getCollidedSoftly();
            boolean cameraEntityOk = client.getCameraEntity() == player;
            boolean cameraFocusedOk = client.gameRenderer != null
                    && client.gameRenderer.getCamera() != null
                    && client.gameRenderer.getCamera().getFocusedEntity() == player;
            boolean baritoneOwnsInput = BaritoneBridge.ownsInput(session);
            boolean baritoneActive = BaritoneBridge.isAutomationActive(session);
            boolean flying = player.getAbilities().flying;
            boolean clientPlayerOk = client.player == player;
            boolean clientWorldOk = client.world == session.world;
            boolean interactionManagerOk = client.interactionManager == session.interactionManager;
            ClientPlayerEntitySprintDiagnostics sprintDiag = (ClientPlayerEntitySprintDiagnostics) player;
            boolean canStartSprinting = sprintDiag.dummymod$canStartSprinting();
            boolean canSprintFlying = sprintDiag.dummymod$canSprint(flying);
            boolean shouldStopSprinting = sprintDiag.dummymod$shouldStopSprinting();
            boolean isBlockedFromSprinting = sprintDiag.dummymod$isBlockedFromSprinting();
            boolean hasMovementInput = sprintDiag.dummymod$hasMovementInput();
            boolean canVehicleSprint = !player.hasVehicle() || sprintDiag.dummymod$canVehicleSprint(player.getVehicle());
            boolean shouldSlowDown = player.shouldSlowDown();
            boolean inSneakingPose = player.isInSneakingPose();
            boolean crawling = player.isCrawling();
            boolean blindness = player.hasStatusEffect(StatusEffects.BLINDNESS);
            boolean gliding = player.isGliding();
            boolean partlyTouchingWater = player.isPartlyTouchingWater();
            String pose = safeToken(String.valueOf(player.getPose()));
            boolean forwardRequested = input.forward();
            boolean sprintRequested = input.sprint() || sprintKeyPressed || (toggleSprint && session.sprintKeyLatched);

            ControlState controlState = new ControlState(
                    toggleSprint, session.sprintKeyLatched, sprintKeyPressed, inputClass, input, sprintAfter,
                    player.isOnGround(), horizontalCollision, collidedSoftly, player.isUsingItem(), player.isSneaking(),
                    player.isSwimming(), player.isTouchingWater(), player.isSubmergedInWater(), player.hasVehicle(), flying,
                    clientPlayerOk, clientWorldOk, interactionManagerOk, cameraEntityOk, cameraFocusedOk,
                    baritoneOwnsInput, baritoneActive, canStartSprinting, canSprintFlying, shouldStopSprinting,
                    isBlockedFromSprinting, hasMovementInput, canVehicleSprint, shouldSlowDown, inSneakingPose,
                    crawling, blindness, gliding, partlyTouchingWater, pose
            );

            return new Snapshot(
                    player.age, toggleSprint, sprintKeyPressed, inputClass, input, sprintBefore, sprintAfter,
                    player.getHungerManager().getFoodLevel(), player.getHungerManager().getSaturationLevel(),
                    player.isOnGround(), horizontalCollision, collidedSoftly, player.isUsingItem(), player.isSneaking(),
                    player.isSwimming(), player.isTouchingWater(), player.isSubmergedInWater(), player.hasVehicle(), flying,
                    player.getVelocity(), clientPlayerOk, clientWorldOk, interactionManagerOk, cameraEntityOk, cameraFocusedOk,
                    baritoneOwnsInput, baritoneActive, canStartSprinting, canSprintFlying, shouldStopSprinting,
                    isBlockedFromSprinting, hasMovementInput, canVehicleSprint, shouldSlowDown, inSneakingPose,
                    crawling, blindness, gliding, partlyTouchingWater, pose, forwardRequested, sprintRequested, controlState
            );
        }
    }
}
