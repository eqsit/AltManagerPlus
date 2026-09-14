package com.dummymod.bot;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.*;

public final class PathFinder {
    private static final int MAX_ITERATIONS = 2500;

    private static class Node implements Comparable<Node> {
        final BlockPos pos;
        final Node parent;
        final double gCost;
        final double hCost;
        final double fCost;

        Node(BlockPos pos, Node parent, double gCost, double hCost) {
            this.pos = pos;
            this.parent = parent;
            this.gCost = gCost;
            this.hCost = hCost;
            this.fCost = gCost + hCost;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(this.fCost, o.fCost);
        }
    }

    public static List<BlockPos> findPath(ClientWorld world, BlockPos start, BlockPos goal, int maxIterations) {
        if (world == null || start == null || goal == null) return Collections.emptyList();
        if (start.equals(goal)) return List.of(goal);

        PriorityQueue<Node> openSet = new PriorityQueue<>();
        Map<BlockPos, Double> openSetMap = new HashMap<>();
        Set<BlockPos> closedSet = new HashSet<>();

        Node startNode = new Node(start, null, 0, start.getSquaredDistance(goal));
        openSet.add(startNode);
        openSetMap.put(start, startNode.fCost);

        Node closestNode = startNode;
        double closestDist = start.getSquaredDistance(goal);
        int iterations = 0;
        int limit = maxIterations > 0 ? maxIterations : MAX_ITERATIONS;

        while (!openSet.isEmpty() && iterations++ < limit) {
            Node current = openSet.poll();
            openSetMap.remove(current.pos);
            closedSet.add(current.pos);

            double d = current.pos.getSquaredDistance(goal);
            if (d < closestDist) {
                closestDist = d;
                closestNode = current;
            }

            if (current.pos.equals(goal) || (goal.getY() == current.pos.getY() && current.pos.getSquaredDistance(goal) <= 1.5)) {
                return reconstructPath(current);
            }

            for (BlockPos neighbor : getValidNeighbors(world, current.pos)) {
                if (closedSet.contains(neighbor)) continue;

                double moveCost = (neighbor.getY() != current.pos.getY()) ? 1.4 : 1.0;
                double g = current.gCost + moveCost;
                double h = Math.sqrt(neighbor.getSquaredDistance(goal));
                double f = g + h;

                Double existingF = openSetMap.get(neighbor);
                if (existingF == null || f < existingF) {
                    Node neighborNode = new Node(neighbor, current, g, h);
                    openSet.add(neighborNode);
                    openSetMap.put(neighbor, f);
                }
            }
        }

        return reconstructPath(closestNode);
    }

    private static List<BlockPos> reconstructPath(Node node) {
        List<BlockPos> path = new ArrayList<>();
        Node cur = node;
        while (cur != null) {
            path.add(cur.pos);
            cur = cur.parent;
        }
        Collections.reverse(path);
        return path;
    }

    private static List<BlockPos> getValidNeighbors(ClientWorld world, BlockPos pos) {
        List<BlockPos> list = new ArrayList<>(8);
        Direction[] dirs = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

        for (Direction dir : dirs) {
            BlockPos forward = pos.offset(dir);

            // 1. Walk straight on same level
            if (isStandable(world, forward)) {
                list.add(forward);
            }
            // 2. Jump up 1 block
            else if (isStandable(world, forward.up()) && isPassable(world, pos.up(2))) {
                list.add(forward.up());
            }
            // 3. Step down 1, 2 or 3 blocks
            else {
                for (int dy = 1; dy <= 3; dy++) {
                    BlockPos down = forward.down(dy);
                    if (isStandable(world, down)) {
                        boolean clear = true;
                        for (int k = 0; k < dy; k++) {
                            if (!isPassable(world, forward.down(k)) || !isPassable(world, forward.down(k).up())) {
                                clear = false;
                                break;
                            }
                        }
                        if (clear) list.add(down);
                        break;
                    }
                }
            }
        }

        return list;
    }

    public static boolean isStandable(ClientWorld world, BlockPos pos) {
        if (!isPassable(world, pos) || !isPassable(world, pos.up())) return false;
        BlockPos below = pos.down();
        BlockState belowState = world.getBlockState(below);
        if (belowState.isAir()) {
            FluidState fluid = world.getFluidState(pos);
            return fluid.isIn(FluidTags.WATER);
        }
        if (isHazard(belowState)) return false;
        return belowState.isSolidBlock(world, below);
    }

    public static boolean isPassable(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return true;
        if (isHazard(state)) return false;
        return !state.blocksMovement() || state.getCollisionShape(world, pos).isEmpty();
    }

    private static boolean isHazard(BlockState state) {
        String name = state.getBlock().getTranslationKey().toLowerCase();
        return name.contains("lava") || name.contains("fire") || name.contains("magma") || name.contains("cactus") || name.contains("sweet_berry");
    }
}
