package ru.mond.minibaritone;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;
import java.util.Optional;

final class PathBot {
    private final BotSettings settings = new BotSettings();
    private final SimplePathfinder pathfinder = new SimplePathfinder();

    private BotMode mode = BotMode.IDLE;
    private BlockPos goal;
    private List<BlockPos> path = List.of();
    private int pathIndex = 0;
    private int repathCooldown = 0;
    private int stuckTicks = 0;
    private Vec3d lastPlayerPos = Vec3d.ZERO;
    private int treeRadius = 32;
    private BlockPos currentTreeTarget;

    BotSettings settings() {
        return settings;
    }

    void gotoBlock(BlockPos target) {
        this.mode = BotMode.GOTO;
        this.goal = target.toImmutable();
        this.path = List.of();
        this.pathIndex = 0;
        this.currentTreeTarget = null;
        this.repathCooldown = 0;
    }

    void mineTrees(int radius) {
        this.mode = BotMode.TREE;
        this.treeRadius = Math.min(100, Math.max(1, radius));
        this.currentTreeTarget = null;
        this.goal = null;
        this.path = List.of();
        this.pathIndex = 0;
        this.repathCooldown = 0;
    }

    void stop() {
        this.mode = BotMode.IDLE;
        this.goal = null;
        this.currentTreeTarget = null;
        this.path = List.of();
        this.pathIndex = 0;
        releaseKeys(MinecraftClient.getInstance());
    }

    void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            releaseKeys(client);
            return;
        }

        if (mode == BotMode.IDLE) {
            releaseKeys(client);
            return;
        }

        if (repathCooldown > 0) {
            repathCooldown--;
        }

        checkStuck(client);

        if (mode == BotMode.TREE) {
            tickTreeMode(client);
            return;
        }

        tickGotoMode(client);
    }

    boolean breakFrontBlock() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.interactionManager == null) {
            return false;
        }

        BlockPos front = frontBlock(client.player);
        BlockState state = client.world.getBlockState(front);

        if (!pathfinder.isBreakable(client.world, front)) {
            return false;
        }

        breakBlock(client, front);
        return true;
    }

    private void tickGotoMode(MinecraftClient client) {
        if (goal == null) {
            stop();
            return;
        }

        ClientPlayerEntity player = client.player;
        BlockPos playerPos = player.getBlockPos();

        if (playerPos.getManhattanDistance(goal) <= 1) {
            player.sendMessage(Text.literal("MiniBaritone: цель достигнута"), false);
            stop();
            return;
        }

        if (path.isEmpty() || pathIndex >= path.size() || repathCooldown == 0 && stuckTicks > 40) {
            recalcPath(client, goal);
            if (path.isEmpty()) {
                player.sendMessage(Text.literal("MiniBaritone: путь не найден"), false);
                stop();
                return;
            }
        }

        followCurrentPath(client);
    }

    private void tickTreeMode(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        World world = client.world;

        if (currentTreeTarget != null) {
            if (!world.getBlockState(currentTreeTarget).isIn(BlockTags.LOGS)) {
                currentTreeTarget = null;
                path = List.of();
                pathIndex = 0;
            } else if (player.squaredDistanceTo(Vec3d.ofCenter(currentTreeTarget)) <= 16.0) {
                releaseMoveKeys(client);
                lookAt(player, Vec3d.ofCenter(currentTreeTarget));
                breakBlock(client, currentTreeTarget);
                return;
            }
        }

        if (currentTreeTarget == null) {
            Optional<BlockPos> nearestLog = findNearestLog(world, player.getBlockPos(), treeRadius);
            if (nearestLog.isEmpty()) {
                player.sendMessage(Text.literal("MiniBaritone: брёвна в радиусе " + treeRadius + " не найдены"), false);
                stop();
                return;
            }

            currentTreeTarget = nearestLog.get();
            BlockPos stand = findStandPositionNear(world, currentTreeTarget).orElse(currentTreeTarget);
            recalcPath(client, stand);
        }

        if (path.isEmpty() || pathIndex >= path.size() || stuckTicks > 50) {
            BlockPos stand = findStandPositionNear(world, currentTreeTarget).orElse(currentTreeTarget);
            recalcPath(client, stand);

            if (path.isEmpty()) {
                currentTreeTarget = null;
                repathCooldown = 20;
                return;
            }
        }

        followCurrentPath(client);
    }

    private void recalcPath(MinecraftClient client, BlockPos target) {
        if (client.player == null || client.world == null) {
            return;
        }

        this.path = pathfinder.findPath(client.world, client.player.getBlockPos(), target, settings);
        this.pathIndex = Math.min(1, path.size());
        this.repathCooldown = 20;
        this.stuckTicks = 0;
    }

    private void followCurrentPath(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        World world = client.world;

        if (path.isEmpty() || pathIndex >= path.size()) {
            releaseMoveKeys(client);
            return;
        }

        BlockPos target = path.get(pathIndex);
        Vec3d targetCenter = Vec3d.ofBottomCenter(target);
        double distance = player.getPos().distanceTo(targetCenter);

        if (distance < 0.65) {
            pathIndex++;
            releaseMoveKeys(client);
            return;
        }

        handleBlockWorkForStep(client, target);

        lookAt(player, targetCenter.add(0, 0.15, 0));

        client.options.forwardKey.setPressed(true);
        client.options.sprintKey.setPressed(settings.allowSprint && distance > 1.8);

        boolean shouldJump = target.getY() > player.getBlockY()
                || !world.getBlockState(player.getBlockPos().offset(player.getHorizontalFacing())).isAir()
                && player.isOnGround();

        client.options.jumpKey.setPressed(shouldJump);
        client.options.sneakKey.setPressed(false);
    }

    private void handleBlockWorkForStep(MinecraftClient client, BlockPos targetFeet) {
        if (client.world == null || client.player == null) {
            return;
        }

        World world = client.world;
        BlockState feet = world.getBlockState(targetFeet);
        BlockState head = world.getBlockState(targetFeet.up());

        if (settings.allowBreak) {
            if (pathfinder.isBreakable(world, targetFeet) && !feet.isAir()) {
                breakBlock(client, targetFeet);
                return;
            }

            if (pathfinder.isBreakable(world, targetFeet.up()) && !head.isAir()) {
                breakBlock(client, targetFeet.up());
                return;
            }
        }

        if (settings.allowPlace && world.getBlockState(targetFeet.down()).isAir()) {
            placeSupportBlock(client, targetFeet.down());
        }
    }

    private Optional<BlockPos> findNearestLog(World world, BlockPos origin, int radius) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        int verticalRadius = Math.min(32, radius);

        for (BlockPos candidate : BlockPos.iterateOutwards(origin, radius, verticalRadius, radius)) {
            BlockPos immutable = candidate.toImmutable();
            if (!world.getBlockState(immutable).isIn(BlockTags.LOGS)) {
                continue;
            }

            double distance = squaredDistance(immutable, origin);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = immutable;
            }
        }

        return Optional.ofNullable(best);
    }

    private Optional<BlockPos> findStandPositionNear(World world, BlockPos target) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos candidate : BlockPos.iterateOutwards(target, 4, 3, 4)) {
            BlockPos immutable = candidate.toImmutable();
            if (immutable.equals(target) || !pathfinder.canStandAt(world, immutable)) {
                continue;
            }

            double distance = squaredDistance(immutable, target);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = immutable;
            }
        }

        return Optional.ofNullable(best);
    }

    private double squaredDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private void checkStuck(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        Vec3d now = player.getPos();

        if (now.squaredDistanceTo(lastPlayerPos) < 0.0009) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }

        lastPlayerPos = now;
    }

    private void lookAt(ClientPlayerEntity player, Vec3d target) {
        Vec3d eyes = player.getEyePos();
        double dx = target.x - eyes.x;
        double dy = target.y - eyes.y;
        double dz = target.z - eyes.z;

        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));

        player.setYaw(smoothAngle(player.getYaw(), yaw, 18.0F));
        player.setPitch(smoothAngle(player.getPitch(), pitch, 14.0F));
    }

    private float smoothAngle(float current, float target, float maxStep) {
        float delta = wrapDegrees(target - current);
        if (delta > maxStep) {
            delta = maxStep;
        }
        if (delta < -maxStep) {
            delta = -maxStep;
        }
        return current + delta;
    }

    private float wrapDegrees(float value) {
        value %= 360.0F;
        if (value >= 180.0F) {
            value -= 360.0F;
        }
        if (value < -180.0F) {
            value += 360.0F;
        }
        return value;
    }

    private BlockPos frontBlock(ClientPlayerEntity player) {
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0F);
        Vec3d target = eye.add(look.multiply(3.0));
        return BlockPos.ofFloored(target);
    }

    private void breakBlock(MinecraftClient client, BlockPos pos) {
        if (client.interactionManager == null || client.player == null) {
            return;
        }

        Direction side = Direction.UP;
        client.interactionManager.updateBlockBreakingProgress(pos, side);
        client.player.swingHand(Hand.MAIN_HAND);
    }

    private boolean placeSupportBlock(MinecraftClient client, BlockPos supportPos) {
        if (client.interactionManager == null || client.player == null || client.world == null) {
            return false;
        }

        int slot = findBlockInHotbar(client.player.getInventory());
        if (slot < 0) {
            return false;
        }

        client.player.getInventory().selectedSlot = slot;

        Direction placeSide = Direction.UP;
        BlockPos clicked = supportPos.down();
        if (client.world.getBlockState(clicked).isAir()) {
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = supportPos.offset(direction);
                if (!client.world.getBlockState(neighbor).isAir()) {
                    clicked = neighbor;
                    placeSide = direction.getOpposite();
                    break;
                }
            }
        }

        Vec3d sideVector = new Vec3d(placeSide.getOffsetX(), placeSide.getOffsetY(), placeSide.getOffsetZ());
        Vec3d hitPos = Vec3d.ofCenter(clicked).add(sideVector.multiply(0.5));
        BlockHitResult hit = new BlockHitResult(hitPos, placeSide, clicked, false);

        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        client.player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    private int findBlockInHotbar(PlayerInventory inventory) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem) {
                return slot;
            }
        }
        return -1;
    }

    private void releaseKeys(MinecraftClient client) {
        releaseMoveKeys(client);
        if (client.options != null) {
            client.options.attackKey.setPressed(false);
        }
    }

    private void releaseMoveKeys(MinecraftClient client) {
        if (client.options == null) {
            return;
        }

        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
    }
}
