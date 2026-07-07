package ru.mond.minibaritone;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

final class SimplePathfinder {
    private static final Direction[] HORIZONTAL = new Direction[]{
            Direction.NORTH,
            Direction.SOUTH,
            Direction.WEST,
            Direction.EAST
    };

    List<BlockPos> findPath(World world, BlockPos rawStart, BlockPos rawGoal, BotSettings settings) {
        BlockPos start = normalizeToStandable(world, rawStart);
        BlockPos goal = normalizeGoal(world, rawGoal, settings);

        PriorityQueue<PathNode> open = new PriorityQueue<>(Comparator.comparingDouble(PathNode::f));
        Map<BlockPos, Double> best = new HashMap<>();

        PathNode startNode = new PathNode(start, null, 0.0, heuristic(start, goal));
        open.add(startNode);
        best.put(start, 0.0);

        int visited = 0;

        while (!open.isEmpty() && visited < settings.maxSearchNodes) {
            visited++;
            PathNode current = open.poll();

            if (current.pos.equals(goal) || current.pos.getManhattanDistance(goal) <= 1) {
                return reconstruct(current);
            }

            for (MoveOption move : neighbors(world, current.pos, settings)) {
                double nextG = current.g + move.cost;
                Double known = best.get(move.pos);

                if (known == null || nextG < known) {
                    best.put(move.pos, nextG);
                    open.add(new PathNode(move.pos, current, nextG, heuristic(move.pos, goal)));
                }
            }
        }

        return List.of();
    }

    private BlockPos normalizeToStandable(World world, BlockPos pos) {
        if (canStandAt(world, pos)) {
            return pos.toImmutable();
        }

        for (int dy = 1; dy >= -3; dy--) {
            BlockPos candidate = pos.add(0, dy, 0);
            if (canStandAt(world, candidate)) {
                return candidate.toImmutable();
            }
        }

        return pos.toImmutable();
    }

    private BlockPos normalizeGoal(World world, BlockPos goal, BotSettings settings) {
        if (canStandAt(world, goal)) {
            return goal.toImmutable();
        }

        for (int radius = 1; radius <= 4; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                            continue;
                        }

                        BlockPos candidate = goal.add(dx, dy, dz);
                        if (canStandAt(world, candidate) || canBecomeStandable(world, candidate, settings)) {
                            return candidate.toImmutable();
                        }
                    }
                }
            }
        }

        return goal.toImmutable();
    }

    private List<MoveOption> neighbors(World world, BlockPos pos, BotSettings settings) {
        List<MoveOption> result = new ArrayList<>(16);

        for (Direction direction : HORIZONTAL) {
            BlockPos flat = pos.offset(direction);

            if (canStandAt(world, flat)) {
                result.add(new MoveOption(flat, 1.0));
                continue;
            }

            BlockPos up = flat.up();
            if (canStandAt(world, up)) {
                result.add(new MoveOption(up, 1.0 + settings.jumpCost));
                continue;
            }

            BlockPos down = flat.down();
            for (int fall = 1; fall <= settings.maxFallHeight; fall++) {
                if (canStandAt(world, down)) {
                    result.add(new MoveOption(down, 1.0 + settings.fallCost * fall));
                    break;
                }
                down = down.down();
            }

            if (settings.allowBreak && canBecomeStandable(world, flat, settings)) {
                double cost = 1.0 + settings.breakCost + breakPenalty(world, flat);
                result.add(new MoveOption(flat, cost));
            }

            if (settings.allowPlace && canPlaceSupportFor(world, flat)) {
                result.add(new MoveOption(flat, 1.0 + settings.placeCost));
            }
        }

        return result;
    }

    private double heuristic(BlockPos a, BlockPos b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int dz = Math.abs(a.getZ() - b.getZ());
        return dx + dz + dy * 2.5;
    }

    private List<BlockPos> reconstruct(PathNode end) {
        ArrayList<BlockPos> reversed = new ArrayList<>();
        PathNode node = end;

        while (node != null) {
            reversed.add(node.pos);
            node = node.parent;
        }

        ArrayList<BlockPos> result = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            result.add(reversed.get(i));
        }
        return result;
    }

    boolean canStandAt(World world, BlockPos feet) {
        BlockState below = world.getBlockState(feet.down());
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(feet.up());

        if (isHazard(feetState) || isHazard(headState) || isHazard(below)) {
            return false;
        }

        boolean freeBody = isPassable(world, feet, feetState) && isPassable(world, feet.up(), headState);
        boolean support = isSolidSupport(world, feet.down()) || isWater(feetState);

        return freeBody && support;
    }

    boolean canBecomeStandable(World world, BlockPos feet, BotSettings settings) {
        if (!settings.allowBreak) {
            return false;
        }

        BlockState below = world.getBlockState(feet.down());
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(feet.up());

        if (isHazard(feetState) || isHazard(headState) || isHazard(below)) {
            return false;
        }

        if (!isSolidSupport(world, feet.down())) {
            return false;
        }

        return isBreakable(world, feet) || isBreakable(world, feet.up());
    }

    private boolean canPlaceSupportFor(World world, BlockPos feet) {
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(feet.up());

        if (!isPassable(world, feet, feetState) || !isPassable(world, feet.up(), headState)) {
            return false;
        }

        BlockPos support = feet.down();
        if (!world.getBlockState(support).isAir()) {
            return false;
        }

        for (Direction direction : Direction.values()) {
            if (direction == Direction.DOWN) {
                continue;
            }
            if (isSolidSupport(world, support.offset(direction))) {
                return true;
            }
        }

        return false;
    }

    private double breakPenalty(World world, BlockPos feet) {
        double cost = 0.0;

        if (isBreakable(world, feet)) {
            cost += hardnessPenalty(world, feet);
        }

        if (isBreakable(world, feet.up())) {
            cost += hardnessPenalty(world, feet.up());
        }

        return cost;
    }

    private double hardnessPenalty(World world, BlockPos pos) {
        float hardness = world.getBlockState(pos).getHardness(world, pos);
        if (hardness < 0) {
            return 10_000.0;
        }
        return Math.max(1.0, hardness * 2.0);
    }

    boolean isPassable(World world, BlockPos pos, BlockState state) {
        return state.isAir()
                || !state.isSolidBlock(world, pos)
                || isWater(state);
    }

    boolean isSolidSupport(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir()
                && !state.getFluidState().isIn(FluidTags.LAVA)
                && state.isSideSolidFullSquare(world, pos, Direction.UP);
    }

    boolean isBreakable(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        if (state.isOf(Blocks.BEDROCK) || state.isOf(Blocks.BARRIER) || state.isOf(Blocks.COMMAND_BLOCK)) {
            return false;
        }
        return state.getHardness(world, pos) >= 0;
    }

    boolean isHazard(BlockState state) {
        return state.getFluidState().isIn(FluidTags.LAVA)
                || state.isOf(Blocks.CACTUS)
                || state.isOf(Blocks.FIRE)
                || state.isOf(Blocks.SOUL_FIRE)
                || state.isOf(Blocks.MAGMA_BLOCK)
                || state.isOf(Blocks.SWEET_BERRY_BUSH);
    }

    boolean isWater(BlockState state) {
        return state.getFluidState().isIn(FluidTags.WATER);
    }
}
