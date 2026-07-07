package ru.mond.minibaritone;

import net.minecraft.util.math.BlockPos;

final class PathNode {
    final BlockPos pos;
    final PathNode parent;
    final double g;
    final double h;

    PathNode(BlockPos pos, PathNode parent, double g, double h) {
        this.pos = pos.toImmutable();
        this.parent = parent;
        this.g = g;
        this.h = h;
    }

    double f() {
        return g + h;
    }
}
