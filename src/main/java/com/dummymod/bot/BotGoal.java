package com.dummymod.bot;

import net.minecraft.util.math.BlockPos;

import java.util.Set;

public interface BotGoal {
    String getStatus();
    boolean isFinished(BotController controller);

    class Idle implements BotGoal {
        @Override public String getStatus() { return "Ожидание"; }
        @Override public boolean isFinished(BotController controller) { return true; }
    }

    class Goto implements BotGoal {
        public final BlockPos target;
        public final double tolerance;
        public Goto(BlockPos target, double tolerance) { this.target = target; this.tolerance = tolerance; }
        @Override public String getStatus() { return "Идет к " + target.getX() + " " + target.getY() + " " + target.getZ(); }
        @Override public boolean isFinished(BotController controller) { return controller.distanceTo(target) <= tolerance; }
    }

    class Follow implements BotGoal {
        public final String targetName;
        public final double minDistance;
        public final double maxDistance;
        public Follow(String targetName, double minDistance, double maxDistance) {
            this.targetName = targetName;
            this.minDistance = minDistance;
            this.maxDistance = maxDistance;
        }
        @Override public String getStatus() { return "Следует за " + targetName; }
        @Override public boolean isFinished(BotController controller) { return false; }
    }

    class Mine implements BotGoal {
        public final Set<String> targetBlockKeywords;
        public BlockPos currentMiningTarget;
        public int breakProgressTicks;
        public Mine(Set<String> targetBlockKeywords) {
            this.targetBlockKeywords = targetBlockKeywords;
        }
        @Override public String getStatus() {
            return currentMiningTarget != null
                    ? "Добыча (" + currentMiningTarget.getX() + " " + currentMiningTarget.getY() + " " + currentMiningTarget.getZ() + ")"
                    : "Поиск руды: " + String.join(", ", targetBlockKeywords);
        }
        @Override public boolean isFinished(BotController controller) { return false; }
    }
}
