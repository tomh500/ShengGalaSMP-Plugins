package top.luotiany1.minigames;

import org.bukkit.configuration.ConfigurationSection;

final class RoomRules {
    final int min;
    final int max;
    final int countdown;
    final int deadline;
    final int disappearTicks;
    final int goldForBow;

    RoomRules(ConfigurationSection config, String roomId, GameType game) {
        String path = "games." + game.id + ".";
        String override = "rooms." + roomId + "." + path;
        min = number(config, path, override, "min-players", game.minimum, 24);
        max = number(config, path, override, "max-players", min, 24);
        countdown = number(config, path, override, "countdown-seconds", 1, 120) * 20;
        deadline = number(config, path, override, "round-seconds", 10, 900) * 20;
        disappearTicks = game == GameType.TNT_RUN
                ? number(config, path, override, "disappear-ticks", 1, 40) : 0;
        goldForBow = game == GameType.MURDER_MYSTERY
                ? number(config, path, override, "gold-for-bow", 1, 20) : 0;
    }

    private static int number(ConfigurationSection config, String path, String override,
                              String name, int min, int max) {
        String key = config.contains(override + name) ? override + name : path + name;
        int value = config.getInt(key);
        if (value < min || value > max) {
            throw new IllegalArgumentException(key + " 必须介于 " + min + " 和 " + max + " 之间");
        }
        return value;
    }
}
