package ru.mond.minibaritone;

import net.minecraft.util.math.BlockPos;

final class MoveOption {
    final BlockPos pos;
    final double cost;

    MoveOption(BlockPos pos, double cost) {
        this.pos = pos.toImmutable();
        this.cost = cost;
    }
}
