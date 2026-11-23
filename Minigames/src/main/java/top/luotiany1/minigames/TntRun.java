package top.luotiany1.minigames;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

final class TntRun implements MiniGame {
    private final World world;
    private final int center;
    private final int disappearTicks;
    private final Map<Block, Long> pending = new LinkedHashMap<>();
    private final List<Block> removed = new ArrayList<>();
    private boolean solo;

    TntRun(World world, int slot, int disappearTicks) {
        this.world = world;
        this.center = ArenaGenerator.center(slot);
        this.disappearTicks = disappearTicks;
    }

    @Override
    public void start(List<Player> players) {
        solo = players.size() == 1;
        // Repair a floor saved mid-round during an unexpected shutdown.
        for (int y = ArenaGenerator.BOTTOM; y <= ArenaGenerator.TOP; y += ArenaGenerator.HEIGHT_BETWEEN_FLOORS) {
            for (int x = -16; x <= 16; x++) {
                for (int z = -16; z <= 16; z++) {
                    if (ArenaGenerator.isFloor(x, y, z)) restore(world.getBlockAt(center + x, y, z));
                }
            }
        }
        for (int i = 0; i < players.size(); i++) {
            double angle = i * (2 * Math.PI / players.size());
            double x = center + Math.round(8 * Math.cos(angle)) + 0.5;
            double z = Math.round(8 * Math.sin(angle)) + 0.5;
            Location spawn = new Location(world, x, ArenaGenerator.TOP + 1, z);
            spawn.setDirection(new Location(world, center + 0.5, spawn.getY(), 0.5).subtract(spawn).toVector());
            players.get(i).teleport(spawn);
        }
    }

    @Override
    public boolean isEliminated(Player player) {
        Location point = player.getLocation();
        return player.getWorld() != world || point.getY() < ArenaGenerator.ELIMINATION_Y
                || Math.abs(point.getX() - center) > 23 || Math.abs(point.getZ()) > 23;
    }

    @Override
    public Result result(Collection<Player> alive, boolean timeout) {
        if (solo && !alive.isEmpty() && !timeout) return null;
        if (alive.size() == 1) {
            Player winner = alive.iterator().next();
            return new Result(RoundResultEvent.Outcome.WINNER, winner.getUniqueId(), null,
                    winner.getName() + " 赢得了 TNT Run！");
        }
        if (alive.isEmpty()) return new Result(RoundResultEvent.Outcome.DRAW, null, null, "无人存活，本局平局。");
        return timeout ? new Result(RoundResultEvent.Outcome.TIME_LIMIT, null, null, "时间到，本局平局。") : null;
    }

    @Override
    public void tick(long tick, Collection<Player> players) {
        for (Player player : players) queueTiles(player.getLocation(), tick);
        Iterator<Map.Entry<Block, Long>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Block, Long> entry = iterator.next();
            if (entry.getValue() > tick) break;
            Block surface = entry.getKey();
            surface.setType(Material.AIR, false);
            surface.getRelative(0, -1, 0).setType(Material.AIR, false);
            removed.add(surface);
            iterator.remove();
        }
    }

    private void queueTiles(Location feet, long tick) {
        int y = (int) Math.floor(feet.getY() - 0.01);
        if (feet.getY() - y > 1.05) return;
        int minX = (int) Math.floor(feet.getX() - 0.29);
        int maxX = (int) Math.floor(feet.getX() + 0.29);
        int minZ = (int) Math.floor(feet.getZ() - 0.29);
        int maxZ = (int) Math.floor(feet.getZ() + 0.29);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (ArenaGenerator.isFloor(x - center, y, z)) {
                    Block tile = world.getBlockAt(x, y, z);
                    if (tile.getType() == ArenaGenerator.surface(x - center, z)) {
                        pending.putIfAbsent(tile, tick + disappearTicks);
                    }
                }
            }
        }
    }

    private void restore(Block tile) {
        Block support = tile.getRelative(0, -1, 0);
        if (support.getType() != Material.TNT) support.setType(Material.TNT, false);
        Material material = ArenaGenerator.surface(tile.getX() - center, tile.getZ());
        if (tile.getType() != material) tile.setType(material, false);
    }

    @Override
    public void reset() {
        pending.clear();
        for (Block tile : removed) restore(tile);
        removed.clear();
    }
}
