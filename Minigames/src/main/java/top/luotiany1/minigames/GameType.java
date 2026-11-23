package top.luotiany1.minigames;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.World;

enum GameType {
    TNT_RUN("tnt-run", "TNT Run", 2, "minigames_tnt", Material.TNT, false,
            "脚下的方块会消失，撑到最后。", (world, slot, rules) -> new TntRun(world, slot, rules.disappearTicks)),
    MURDER_MYSTERY("murder-mystery", "谁是杀手", 3, "minigames_murder", Material.IRON_SWORD, true,
            "身份已私下分配。", (world, slot, rules) -> new MurderMystery(world, slot, rules.goldForBow));

    final String id;
    final String title;
    final int minimum;
    final String worldName;
    final Material icon;
    final boolean pvp;
    final String instructions;
    final Factory factory;

    GameType(String id, String title, int minimum, String worldName, Material icon, boolean pvp,
             String instructions, Factory factory) {
        this.id = id;
        this.title = title;
        this.minimum = minimum;
        this.worldName = worldName;
        this.icon = icon;
        this.pvp = pvp;
        this.instructions = instructions;
        this.factory = factory;
    }

    static GameType fromId(String id) {
        for (GameType type : values()) {
            if (type.id.equals(id)) return type;
        }
        throw new IllegalArgumentException("未知玩法 " + id);
    }

    static List<GameType> selection(List<?> ids) {
        List<GameType> types = new ArrayList<>();
        for (Object id : ids) {
            GameType type = fromId(String.valueOf(id).trim());
            if (!types.contains(type)) types.add(type);
        }
        if (types.isEmpty()) throw new IllegalArgumentException("至少选择一种玩法。");
        return Collections.unmodifiableList(types);
    }

    interface Factory {
        MiniGame create(World world, int slot, RoomRules rules);
    }
}
