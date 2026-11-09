package dev.deathechoes.echo;

import org.bukkit.block.Block;

/** Unveränderliche Blockposition (ohne Welt). */
public record BlockPos(int x, int y, int z) {

    public static BlockPos of(Block b) {
        return new BlockPos(b.getX(), b.getY(), b.getZ());
    }

    public int chunkX() {
        return x >> 4;
    }

    public int chunkZ() {
        return z >> 4;
    }

    @Override
    public String toString() {
        return x + " " + y + " " + z;
    }
}
