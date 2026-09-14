package com.dummymod.bot;

import com.dummymod.dummy.DummyManager;
import com.dummymod.dummy.PlayerSession;
import com.dummymod.dummy.baritone.BaritoneBridge;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class BotController {
    private static final Logger LOGGER = LoggerFactory.getLogger("DummyMod-Bot");
    public final PlayerSession session;
    private BotGoal currentGoal = new BotGoal.Idle();
    private List<BlockPos> currentPath = Collections.emptyList();
    private int pathIndex = 0;
    private int repathCooldown = 0;
    private int stuckTicks = 0;
    private Vec3d lastPos = Vec3d.ZERO;
    private boolean paused = false;
    private boolean baritoneManaged = false;
    private String baritoneStatus = "";

    public BotController(PlayerSession session) {
        this.session = session;
    }

    public BotGoal getGoal() { return currentGoal; }
    public String getStatus() {
        if (baritoneManaged) return (paused ? "Пауза: " : "Baritone: ") + baritoneStatus;
        if (paused) return "На паузе: " + currentGoal.getStatus();
        return currentGoal.getStatus();
    }
    public boolean isActive() { return baritoneManaged || !(currentGoal instanceof BotGoal.Idle); }

    public void setGoal(BotGoal goal) {
        this.currentGoal = goal;
        this.currentPath = Collections.emptyList();
        this.pathIndex = 0;
        this.repathCooldown = 0;
        this.stuckTicks = 0;
        this.paused = false;
        this.baritoneManaged = false;
        this.baritoneStatus = "";
    }

    public void stop() {
        if (baritoneManaged) BaritoneBridge.execute(session, "stop");
        setGoal(new BotGoal.Idle());
    }

    public void togglePause() {
        this.paused = !this.paused;
        if (baritoneManaged) BaritoneBridge.execute(session, this.paused ? "pause" : "resume");
    }

    public double distanceTo(BlockPos target) {
        if (session.player == null) return Double.MAX_VALUE;
        return Math.sqrt(session.player.squaredDistanceTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5));
    }

    public void tick(MinecraftClient client) {
        if (session.player == null || session.world == null || paused || currentGoal instanceof BotGoal.Idle) {
            return;
        }

        ClientPlayerEntity player = session.player;
        ClientWorld world = session.world;

        // Check if goal finished
        if (currentGoal.isFinished(this)) {
            notify("§a[AltManager+ Bot] Цель завершена!");
            stop();
            return;
        }

        // Handle specific goals
        if (currentGoal instanceof BotGoal.Follow followGoal) {
            tickFollow(followGoal, player, world);
        } else if (currentGoal instanceof BotGoal.Mine mineGoal) {
            tickMine(mineGoal, player, world);
        } else if (currentGoal instanceof BotGoal.Goto gotoGoal) {
            tickGoto(gotoGoal.target, player, world);
        }

        // Follow path movement
        navigateAlongPath(player, world);
    }

    private void tickFollow(BotGoal.Follow follow, ClientPlayerEntity player, ClientWorld world) {
        PlayerEntity targetPlayer = null;
        for (AbstractClientPlayerEntity p : world.getPlayers()) {
            if (p.getGameProfile().name().equalsIgnoreCase(follow.targetName)) {
                targetPlayer = p;
                break;
            }
        }
        if (targetPlayer == null && follow.targetName.equalsIgnoreCase("me") && DummyManager.mainSession.player != null) {
            targetPlayer = DummyManager.mainSession.player;
        }

        if (targetPlayer == null) {
            if (repathCooldown-- <= 0) {
                repathCooldown = 40;
                notify("§e[AltManager+ Bot] Поиск игрока '" + follow.targetName + "'...");
            }
            return;
        }

        double dist = player.distanceTo(targetPlayer);
        if (dist <= follow.minDistance) {
            currentPath = Collections.emptyList();
            pathIndex = 0;
            lookAt(player, targetPlayer.getEyePos());
            return;
        }

        if (repathCooldown-- <= 0 || currentPath.isEmpty()) {
            repathCooldown = 20;
            BlockPos targetBlock = targetPlayer.getBlockPos();
            currentPath = PathFinder.findPath(world, player.getBlockPos(), targetBlock, 1500);
            pathIndex = 0;
        }
    }

    private void tickGoto(BlockPos target, ClientPlayerEntity player, ClientWorld world) {
        if (currentPath.isEmpty() || pathIndex >= currentPath.size()) {
            if (repathCooldown-- <= 0) {
                repathCooldown = 30;
                currentPath = PathFinder.findPath(world, player.getBlockPos(), target, 2500);
                pathIndex = 0;
            }
        }
    }

    private void tickMine(BotGoal.Mine mine, ClientPlayerEntity player, ClientWorld world) {
        if (mine.currentMiningTarget == null || world.getBlockState(mine.currentMiningTarget).isAir()) {
            mine.currentMiningTarget = findNearestOre(mine.targetBlockKeywords, player.getBlockPos(), world, 32);
            mine.breakProgressTicks = 0;
            currentPath = Collections.emptyList();
            pathIndex = 0;
            if (mine.currentMiningTarget == null) {
                if (repathCooldown-- <= 0) {
                    repathCooldown = 60;
                    notify("§e[AltManager+ Bot] Руда не найдена в радиусе 32 блоков.");
                }
                return;
            } else {
                notify("§a[AltManager+ Bot] Найдена руда: " + world.getBlockState(mine.currentMiningTarget).getBlock().getName().getString() + " на " + mine.currentMiningTarget.toShortString());
            }
        }

        BlockPos orePos = mine.currentMiningTarget;
        double distSq = player.squaredDistanceTo(orePos.getX() + 0.5, orePos.getY() + 0.5, orePos.getZ() + 0.5);

        if (distSq <= 18.0) {
            // Within mining range -> face and attack block
            lookAt(player, Vec3d.ofCenter(orePos));
            if (session.interactionManager != null) {
                Direction side = Direction.UP;
                session.interactionManager.attackBlock(orePos, side);
                session.interactionManager.updateBlockBreakingProgress(orePos, side);
                player.swingHand(Hand.MAIN_HAND);
                mine.breakProgressTicks++;
            }
        } else {
            // Pathfind toward the ore
            if (currentPath.isEmpty() || pathIndex >= currentPath.size()) {
                if (repathCooldown-- <= 0) {
                    repathCooldown = 30;
                    currentPath = PathFinder.findPath(world, player.getBlockPos(), orePos, 2000);
                    pathIndex = 0;
                }
            }
        }
    }

    private BlockPos findNearestOre(Set<String> keywords, BlockPos center, ClientWorld world, int radius) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        int cx = center.getX(), cy = center.getY(), cz = center.getZ();
        int minX = cx - radius, maxX = cx + radius;
        int minY = Math.max(world.getBottomY(), cy - 16), maxY = Math.min(world.getTopYInclusive(), cy + 16);
        int minZ = cz - radius, maxZ = cz + radius;

        for (int x = minX; x <= maxX; x += 2) {
            for (int z = minZ; z <= maxZ; z += 2) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = world.getBlockState(p);
                    if (s.isAir()) continue;
                    String name = s.getBlock().getTranslationKey().toLowerCase();
                    for (String kw : keywords) {
                        if (name.contains(kw)) {
                            double d = center.getSquaredDistance(p);
                            if (d < bestDist) {
                                bestDist = d;
                                best = p;
                            }
                            break;
                        }
                    }
                }
            }
        }

        return best;
    }

    private void navigateAlongPath(ClientPlayerEntity player, ClientWorld world) {
        if (currentPath == null || currentPath.isEmpty() || pathIndex >= currentPath.size()) {
            return;
        }

        BlockPos targetNode = currentPath.get(pathIndex);
        Vec3d targetCenter = new Vec3d(targetNode.getX() + 0.5, targetNode.getY(), targetNode.getZ() + 0.5);

        double dx = targetCenter.x - player.getX();
        double dz = targetCenter.z - player.getZ();
        double horizDist = Math.sqrt(dx * dx + dz * dz);

        // Advance to next node if reached
        if (horizDist < 0.6 && Math.abs(player.getY() - targetNode.getY()) < 1.5) {
            pathIndex++;
            if (pathIndex >= currentPath.size()) {
                currentPath = Collections.emptyList();
                pathIndex = 0;
                return;
            }
            targetNode = currentPath.get(pathIndex);
            targetCenter = new Vec3d(targetNode.getX() + 0.5, targetNode.getY(), targetNode.getZ() + 0.5);
            dx = targetCenter.x - player.getX();
            dz = targetCenter.z - player.getZ();
        }

        // Steer player look & input
        lookAt(player, targetCenter.add(0, 1.0, 0));

        // Detect if stuck
        Vec3d currentPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        if (lastPos.squaredDistanceTo(currentPos) < 0.001) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastPos = currentPos;

        // Apply movement inputs
        boolean jump = targetNode.getY() > player.getBlockY() || (stuckTicks > 8 && player.isOnGround());
        boolean sprint = horizDist > 3.0 && !player.horizontalCollision;
        if (sprint) player.setSprinting(true);
        if (player.input != null) {
            player.input.playerInput = new net.minecraft.util.PlayerInput(true, false, false, false, jump, false, sprint);
        }
    }

    public static void lookAt(ClientPlayerEntity player, Vec3d target) {
        double dx = target.x - player.getX();
        double dy = target.y - player.getEyeY();
        double dz = target.z - player.getZ();
        double r = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) (-(MathHelper.atan2(dy, r) * (180.0 / Math.PI)));

        player.setYaw(yaw);
        player.setPitch(pitch);
        player.headYaw = yaw;
        player.bodyYaw = yaw;
    }

    private boolean tryBaritone(String command, String status) {
        if (!BaritoneBridge.execute(session, command)) return false;
        this.currentGoal = new BotGoal.Idle();
        this.currentPath = Collections.emptyList();
        this.pathIndex = 0;
        this.repathCooldown = 0;
        this.stuckTicks = 0;
        this.paused = false;
        this.baritoneManaged = true;
        this.baritoneStatus = status;
        notify("§a[AltManager+ Bot] Baritone: " + status);
        return true;
    }

    public boolean executeCommand(String input) {
        if (input == null || input.trim().isEmpty()) return false;
        String raw = input.trim();
        if (raw.startsWith("#")) raw = raw.substring(1).trim();
        String[] parts = raw.split("\\s+");
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "goto": {
                if (parts.length < 3) {
                    notify("§c[AltManager+ Bot] Использование: #goto <x> <z> ИЛИ #goto <x> <y> <z>");
                    return true;
                }
                if (tryBaritone(raw, "goto " + String.join(" ", Arrays.copyOfRange(parts, 1, parts.length)))) return true;
                try {
                    ClientPlayerEntity p = session.player;
                    int x = parseCoord(parts[1], p != null ? p.getBlockX() : 0);
                    int y = parts.length >= 4 ? parseCoord(parts[2], p != null ? p.getBlockY() : 64) : (p != null ? p.getBlockY() : 64);
                    int z = parts.length >= 4 ? parseCoord(parts[3], p != null ? p.getBlockZ() : 0) : parseCoord(parts[2], p != null ? p.getBlockZ() : 0);
                    BlockPos target = new BlockPos(x, y, z);
                    setGoal(new BotGoal.Goto(target, 1.5));
                    notify("§a[AltManager+ Bot] Иду на координаты: " + x + " " + y + " " + z);
                    return true;
                } catch (Exception e) {
                    notify("§c[AltManager+ Bot] Неверные координаты: " + e.getMessage());
                    return true;
                }
            }

            case "follow": {
                String target = parts.length > 1 ? parts[1] : "me";
                if (parts.length > 2 && parts[1].equalsIgnoreCase("player")) {
                    target = parts[2];
                }
                String baritoneFollow = target.equalsIgnoreCase("me") ? "follow player " + DummyManager.mainSession.displayName() : "follow player " + target;
                if (!(target.equalsIgnoreCase("me") && session.main) && tryBaritone(baritoneFollow, "follow " + target)) return true;
                if (target.equalsIgnoreCase("me") && session.main) {
                    notify("§c[AltManager+ Bot] Основной аккаунт не может следовать сам за собой. Укажите ник игрока.");
                    return true;
                }
                setGoal(new BotGoal.Follow(target, 2.5, 40.0));
                notify("§a[AltManager+ Bot] Следую за: " + target);
                return true;
            }

            case "mine": {
                if (parts.length < 2) {
                    notify("§c[AltManager+ Bot] Использование: #mine <блок> (например: #mine diamond_ore)");
                    return true;
                }
                if (tryBaritone(raw, "mine " + String.join(" ", Arrays.copyOfRange(parts, 1, parts.length)))) return true;
                Set<String> keywords = new HashSet<>();
                for (int i = 1; i < parts.length; i++) {
                    keywords.add(parts[i].toLowerCase().replace("minecraft:", ""));
                }
                setGoal(new BotGoal.Mine(keywords));
                notify("§a[AltManager+ Bot] Начат поиск и добыча: " + String.join(", ", keywords));
                return true;
            }

            case "stop":
            case "cancel": {
                stop();
                notify("§e[AltManager+ Bot] Действие остановлено.");
                return true;
            }

            case "pause": {
                if (!paused) togglePause();
                notify("§e[AltManager+ Bot] Статус: Пауза");
                return true;
            }

            case "resume": {
                if (paused) togglePause();
                notify("§e[AltManager+ Bot] Статус: Возобновлено");
                return true;
            }

            case "click":
            case "attack": {
                session.autoclicker.enabled = !session.autoclicker.enabled;
                notify("§a[AltManager+ Bot] Автокликер: " + (session.autoclicker.enabled ? "ВКЛ (" + session.autoclicker.getCps() + " CPS)" : "ВЫКЛ"));
                return true;
            }

            case "say":
            case "chat": {
                if (parts.length > 1 && session.networkHandler != null) {
                    String msg = raw.substring(raw.indexOf(' ') + 1);
                    session.networkHandler.sendChatMessage(msg);
                }
                return true;
            }

            case "help": {
                notify("§6[AltManager+ Bot Команды]:");
                notify("§e#goto <x> <y> <z> §7- идти к точке");
                notify("§e#follow <ник/me> §7- следовать за игроком");
                notify("§e#mine <блок> §7- авто-поиск и добыча руды");
                notify("§e#stop §7- остановить всё");
                notify("§e#pause §7- пауза/продолжить");
                notify("§e#click §7- переключить автокликер");
                notify("§e#all <команда> §7- запустить команду на всех твинках");
                return true;
            }

            default:
                notify("§c[AltManager+ Bot] Неизвестная команда '#" + cmd + "'. Напишите '#help' для списка.");
                return false;
        }
    }

    private static int parseCoord(String text, int base) {
        if (text.startsWith("~")) {
            if (text.length() == 1) return base;
            return base + Integer.parseInt(text.substring(1));
        }
        return Integer.parseInt(text);
    }

    private void notify(String msg) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c != null && c.player != null) {
            c.player.sendMessage(Text.literal(msg), false);
        }
    }
}
