package top.luotiany1.minigames;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Predicate;
import com.onarandombox.MultiverseCore.MultiverseCore;
import com.onarandombox.MultiverseCore.api.MVWorldManager;
import com.onarandombox.MultiverseCore.api.MultiverseWorld;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.bukkit.plugin.Plugin;

final class ArenaWorlds {
    private static final long IDLE_TICKS = 1200;
    private final MVWorldManager manager;
    private final Map<GameType, Long> idleSince = new EnumMap<>(GameType.class);

    ArenaWorlds() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
        if (!(plugin instanceof MultiverseCore) || !plugin.isEnabled()) {
            throw new IllegalStateException("安装并启用 Multiverse-Core 2.5.0 后再启动小游戏服。");
        }
        manager = ((MultiverseCore) plugin).getMVWorldManager();
    }

    World acquire(GameType type) {
        String name = worldName(type);
        World world = Bukkit.getWorld(name);
        if (world == null) {
            boolean loaded = manager.isMVWorld(name) ? manager.loadWorld(name)
                    : manager.addWorld(name, World.Environment.NORMAL, null, WorldType.NORMAL, false, "Minigames", false);
            if (!loaded) throw new IllegalStateException("Multiverse 无法载入 " + name);
            world = Bukkit.getWorld(name);
        }
        if (world == null || !(world.getGenerator() instanceof ArenaGenerator)) {
            throw new IllegalStateException(name + " 缺少 Minigames 生成器，检查 worlds.yml");
        }
        idleSince.remove(type);
        MultiverseWorld registration = manager.getMVWorld(name);
        if (registration == null) throw new IllegalStateException(name + " 未纳入 Multiverse 管理");
        registration.setAutoLoad(false);
        registration.setKeepSpawnInMemory(false);
        registration.setAllowAnimalSpawn(false);
        registration.setAllowMonsterSpawn(false);
        registration.setAdjustSpawn(false);
        registration.setGameMode(GameMode.ADVENTURE);
        registration.setPVPMode(type.pvp);
        world.setKeepSpawnInMemory(false);
        world.setGameRuleValue("doMobSpawning", "false");
        world.setGameRuleValue("doWeatherCycle", "false");
        world.setGameRuleValue("doDaylightCycle", "false");
        world.setTime(6000);
        manager.saveWorldsConfig();
        return world;
    }

    void unloadIdle(long tick, Predicate<GameType> used) {
        for (GameType type : GameType.values()) {
            String name = worldName(type);
            World world = Bukkit.getWorld(name);
            if (world == null || !world.getPlayers().isEmpty() || used.test(type)) {
                idleSince.remove(type);
            } else if (tick - idleSince.computeIfAbsent(type, key -> tick) >= IDLE_TICKS) {
                manager.unloadWorld(name, true);
                idleSince.remove(type);
            }
        }
    }

    static String worldName(GameType type) {
        return type.worldName;
    }
}
