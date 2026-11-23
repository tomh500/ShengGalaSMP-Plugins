package top.luotiany1.minigames;

import java.util.Random;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.material.MaterialData;

final class ArenaGenerator extends ChunkGenerator {
    static final int TOP = 72;
    static final int BOTTOM = 56;
    static final int HEIGHT_BETWEEN_FLOORS = 8;
    static final int ELIMINATION_Y = 49;
    static final int MURDER_Z = 320;
    static final int MURDER_FLOOR = 62;
    private static final int LOBBY_X = 40;
    private static final int ROOM_SPACING = 320;

    static int center(int slot) {
        return slot * ROOM_SPACING;
    }

    static Location lobby(World world) {
        return new Location(world, LOBBY_X + 0.5, 83, 0.5, 90, 0);
    }

    static Location spectator(World world, int slot, GameType type) {
        return type == GameType.TNT_RUN
                ? new Location(world, center(slot) + 0.5, 83, 0.5, 0, 45)
                : new Location(world, center(slot) + 0.5, 74, MURDER_Z + 0.5, 0, 55);
    }

    static boolean isFloor(int x, int y, int z) {
        return y >= BOTTOM && y <= TOP && (TOP - y) % HEIGHT_BETWEEN_FLOORS == 0
                && insideArena(x, z, 16, 24);
    }

    private static boolean insideArena(int x, int z, int radius, int diagonal) {
        return Math.abs(x) <= radius && Math.abs(z) <= radius && Math.abs(x) + Math.abs(z) <= diagonal;
    }

    static Material surface(int x, int z) {
        return ((Math.floorDiv(x, 4) + Math.floorDiv(z, 4)) & 1) == 0 ? Material.SAND : Material.GRAVEL;
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        if (world.getName().equals(ArenaWorlds.worldName(GameType.TNT_RUN))) {
            return new Location(world, 0.5, TOP + 1, 0.5);
        }
        if (world.getName().equals(ArenaWorlds.worldName(GameType.MURDER_MYSTERY))) {
            return new Location(world, 0.5, MURDER_FLOOR + 1, MURDER_Z + 0.5);
        }
        return lobby(world);
    }

    @Override
    public ChunkData generateChunkData(World world, Random random, int chunkX, int chunkZ, BiomeGrid biomes) {
        ChunkData chunk = createChunkData(world);
        boolean tnt = world.getName().equals(ArenaWorlds.worldName(GameType.TNT_RUN));
        boolean murder = world.getName().equals(ArenaWorlds.worldName(GameType.MURDER_MYSTERY));
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int x = chunkX * 16 + localX;
                int z = chunkZ * 16 + localZ;
                int slot = Math.floorDiv(x + ROOM_SPACING / 2, ROOM_SPACING);
                if (slot >= 0 && slot < 10) {
                    if (tnt) generateArenaColumn(chunk, localX, localZ, x - center(slot), z);
                    if (murder) generateMurderColumn(chunk, localX, localZ, x - center(slot), z - MURDER_Z);
                }
                if (!tnt && !murder) generateLobbyColumn(chunk, localX, localZ, x, z);
            }
        }
        return chunk;
    }

    private void generateArenaColumn(ChunkData chunk, int localX, int localZ, int x, int z) {
        if (!insideArena(x, z, 20, 30)) return;
        chunk.setBlock(localX, 47, localZ, Material.NETHER_BRICK);
        if (insideArena(x, z, 16, 24)) {
            for (int y = BOTTOM; y <= TOP; y += HEIGHT_BETWEEN_FLOORS) {
                chunk.setBlock(localX, y - 1, localZ, Material.TNT);
                chunk.setBlock(localX, y, localZ, surface(x, z));
            }
            return;
        }
        if (insideArena(x, z, 19, 29)) return;
        boolean pillar = (x % 5 == 0 && z % 5 == 0) || x == 0 || z == 0;
        for (int y = 48; y <= 80; y++) {
            Material material = pillar || y % 8 <= 1 ? Material.NETHER_BRICK : Material.STAINED_GLASS;
            if (y == 49 || y == 78) material = Material.SEA_LANTERN;
            if (material == Material.STAINED_GLASS) {
                chunk.setBlock(localX, y, localZ, new MaterialData(material, (byte) 1));
            } else {
                chunk.setBlock(localX, y, localZ, material);
            }
        }
    }

    private void generateMurderColumn(ChunkData chunk, int localX, int localZ, int x, int z) {
        int dx = Math.abs(x);
        int dz = Math.abs(z);
        if (dx > 24 || dz > 20) return;
        boolean outerWall = dx == 24 || dz == 20;
        boolean separator = (dx == 8 && dz >= 5 && dz <= 16 && dz != 10 && dz != 11)
                || (dz == 7 && dx >= 11 && dx <= 23 && dx != 16 && dx != 17);
        boolean pillar = dx == 4 && dz == 4;
        Material floor = (dx == 0 && dz % 5 == 0) || (dz == 0 && dx % 5 == 0)
                ? Material.SEA_LANTERN : dx < 8 && dz < 7 ? Material.QUARTZ_BLOCK : Material.SMOOTH_BRICK;
        chunk.setBlock(localX, MURDER_FLOOR, localZ, floor);
        if (outerWall || separator || pillar) {
            int height = outerWall ? 7 : 4;
            for (int y = 1; y <= height; y++) {
                Material material = y == height ? Material.DARK_OAK_STAIRS
                        : y == 3 && (x + z) % 4 == 0 ? Material.STAINED_GLASS : Material.SMOOTH_BRICK;
                chunk.setBlock(localX, MURDER_FLOOR + y, localZ, material);
            }
        } else if (dx >= 12 && dz >= 11 && dx % 6 == 0 && dz % 6 == 0) {
            chunk.setBlock(localX, MURDER_FLOOR + 1, localZ, Material.BOOKSHELF);
            chunk.setBlock(localX, MURDER_FLOOR + 2, localZ, Material.BOOKSHELF);
        }
    }

    private void generateLobbyColumn(ChunkData chunk, int localX, int localZ, int x, int z) {
        int dx = Math.abs(x - LOBBY_X);
        int dz = Math.abs(z);
        if (dx > 8 || dz > 6) return;
        boolean edge = dx == 8 || dz == 6;
        Material floor = dx == 0 || dz == 0 ? Material.SEA_LANTERN : Material.QUARTZ_BLOCK;
        chunk.setBlock(localX, 81, localZ, Material.NETHER_BRICK);
        chunk.setBlock(localX, 82, localZ, floor);
        if (edge) chunk.setBlock(localX, 83, localZ, Material.STAINED_GLASS);
    }
}
