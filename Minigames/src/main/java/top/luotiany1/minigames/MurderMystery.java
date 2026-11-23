package top.luotiany1.minigames;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

final class MurderMystery implements MiniGame {
    private static final int[][] STARTS = {
        {-20, -15}, {-14, -15}, {-5, -17}, {5, -17}, {14, -15}, {20, -15},
        {-21, -11}, {-16, -10}, {-5, -11}, {5, -11}, {16, -10}, {21, -11},
        {-21, 11}, {-16, 10}, {-5, 11}, {5, 11}, {16, 10}, {21, 11},
        {-20, 15}, {-14, 15}, {-5, 17}, {5, 17}, {14, 15}, {20, 15}
    };
    private static final int[][] GOLD = {
        {-18, -14}, {-11, -4}, {0, -14}, {11, -4}, {18, -14}, {-19, 0}, {0, 0},
        {19, 0}, {-18, 14}, {-11, 4}, {0, 14}, {11, 4}, {18, 14}, {-6, 0}, {6, 0}
    };
    private final World world;
    private final int center;
    private final int goldForBow;
    private final Map<UUID, Integer> gold = new LinkedHashMap<>();
    private final Map<Block, Long> coins = new LinkedHashMap<>();
    private UUID murderer;
    private UUID detective;
    private Location lostBow;

    MurderMystery(World world, int slot, int goldForBow) {
        this.world = world;
        this.center = ArenaGenerator.center(slot);
        this.goldForBow = goldForBow;
    }

    @Override
    public void start(List<Player> players) {
        Block bowPlate = location(0, 2).getBlock();
        if (bowPlate.getType() == Material.IRON_PLATE) bowPlate.setType(Material.AIR, false);
        List<Player> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);
        murderer = shuffled.size() == 1 ? null : shuffled.get(0).getUniqueId();
        detective = shuffled.get(shuffled.size() == 1 ? 0 : 1).getUniqueId();
        for (int i = 0; i < shuffled.size(); i++) {
            Player player = shuffled.get(i);
            player.teleport(location(STARTS[i][0], STARTS[i][1]));
            if (player.getUniqueId().equals(murderer)) {
                player.sendTitle(ChatColor.RED + "杀手", "用短剑淘汰其他玩家", 5, 60, 10);
                player.sendMessage(ChatColor.RED + "你的身份是杀手。用短剑淘汰其他玩家。");
                player.getInventory().setItem(1, named(Material.IRON_SWORD, ChatColor.RED + "短剑"));
            } else if (player.getUniqueId().equals(detective)) {
                player.sendTitle(ChatColor.AQUA + "侦探", "用弓找出杀手", 5, 60, 10);
                player.sendMessage(ChatColor.AQUA + "你的身份是侦探。弓命中杀手即可获胜，误射会让自己出局。");
                giveBow(player);
            } else {
                player.sendTitle(ChatColor.GREEN + "平民", "躲避杀手，收集金币", 5, 60, 10);
                player.sendMessage(ChatColor.GREEN + "你的身份是平民。收集 " + goldForBow + " 枚金币可换一发弓箭。");
            }
        }
        for (int[] spot : GOLD) {
            Block block = location(spot[0], spot[1]).getBlock();
            block.setType(Material.GOLD_PLATE, false);
            coins.put(block, 0L);
        }
    }

    private Location location(int x, int z) {
        return new Location(world, center + x + 0.5, ArenaGenerator.MURDER_FLOOR + 1,
                ArenaGenerator.MURDER_Z + z + 0.5, 90, 0);
    }

    private static ItemStack named(Material material, String title) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(title);
        item.setItemMeta(meta);
        return item;
    }

    private static void giveBow(Player player) {
        player.getInventory().setItem(2, named(Material.BOW, ChatColor.AQUA + "侦探弓"));
        player.getInventory().addItem(new ItemStack(Material.ARROW));
    }

    @Override
    public boolean isEliminated(Player player) {
        Location point = player.getLocation();
        return player.getWorld() != world || point.getY() < ArenaGenerator.MURDER_FLOOR - 2
                || Math.abs(point.getX() - center) > 25 || Math.abs(point.getZ() - ArenaGenerator.MURDER_Z) > 21;
    }

    @Override
    public Player attack(Player attacker, Player target, EntityDamageByEntityEvent event) {
        if (attacker.getUniqueId().equals(target.getUniqueId())) return null;
        if (event.getDamager() instanceof Arrow && !attacker.getUniqueId().equals(murderer)) {
            event.getDamager().remove();
            if (target.getUniqueId().equals(murderer)) return target;
            attacker.sendMessage(ChatColor.RED + "你误射了无辜玩家，已出局。");
            return attacker;
        }
        if (event.getDamager() == attacker && attacker.getUniqueId().equals(murderer)
                && attacker.getInventory().getItemInMainHand().getType() == Material.IRON_SWORD) {
            return target;
        }
        return null;
    }

    @Override
    public void onEliminated(Player player) {
        if (player.getUniqueId().equals(detective)) {
            detective = null;
            lostBow = location(0, 2);
            // A visible plate marks the bow and disappears on pickup or round cleanup.
            lostBow.getBlock().setType(Material.IRON_PLATE, false);
            for (Entity viewer : world.getNearbyEntities(lostBow, 25, 8, 21)) {
                if (viewer instanceof Player) {
                    ((Player) viewer).sendMessage(ChatColor.AQUA + "侦探弓可在中央大厅的铁色踏板处拾取。");
                }
            }
        }
    }

    @Override
    public Result result(Collection<Player> alive, boolean timeout) {
        if (murderer == null && !alive.isEmpty() && !timeout) return null;
        int innocentCount = 0;
        Player killer = null;
        for (Player player : alive) {
            if (player.getUniqueId().equals(murderer)) killer = player;
            else innocentCount++;
        }
        if (alive.isEmpty()) return new Result(RoundResultEvent.Outcome.DRAW, null, null, "双方均出局，本局平局。");
        if (killer == null) return new Result(RoundResultEvent.Outcome.WINNER, null, "innocents",
                "杀手已出局，侦探和平民获胜！");
        if (innocentCount == 0) {
            return new Result(RoundResultEvent.Outcome.WINNER, killer.getUniqueId(), "murderer", "杀手获胜！");
        }
        return timeout ? new Result(RoundResultEvent.Outcome.WINNER, null, "innocents",
                "时间到，侦探和平民获胜！") : null;
    }

    @Override
    public void tick(long tick, Collection<Player> alive) {
        for (Map.Entry<Block, Long> coin : coins.entrySet()) {
            if (coin.getValue() != 0 && coin.getValue() <= tick) {
                coin.getKey().setType(Material.GOLD_PLATE, false);
                coin.setValue(0L);
            }
        }
        for (Player player : alive) {
            if (player.getUniqueId().equals(murderer)) continue;
            Block feet = player.getLocation().getBlock();
            if (lostBow != null && lostBow.getBlock().equals(feet)) {
                clearLostBow();
                detective = player.getUniqueId();
                giveBow(player);
                player.sendMessage(ChatColor.AQUA + "你捡起了侦探弓。");
            }
            if (feet.getType() != Material.GOLD_PLATE || !coins.containsKey(feet)) continue;
            feet.setType(Material.AIR, false);
            coins.put(feet, tick + 180);
            int count = gold.getOrDefault(player.getUniqueId(), 0) + 1;
            if (count >= goldForBow) {
                giveBow(player);
                count = 0;
                player.sendMessage(ChatColor.GOLD + "金币已兑换成弓箭。");
            } else {
                player.sendMessage(ChatColor.GOLD + "金币 " + count + "/" + goldForBow);
            }
            gold.put(player.getUniqueId(), count);
        }
    }

    private void clearLostBow() {
        if (lostBow != null && lostBow.getBlock().getType() == Material.IRON_PLATE) {
            lostBow.getBlock().setType(Material.AIR, false);
        }
        lostBow = null;
    }

    @Override
    public void reset() {
        clearLostBow();
        for (Block coin : coins.keySet()) {
            if (coin.getType() == Material.GOLD_PLATE) coin.setType(Material.AIR, false);
        }
        for (Arrow arrow : world.getEntitiesByClass(Arrow.class)) {
            Location point = arrow.getLocation();
            if (Math.abs(point.getX() - center) <= 26 && Math.abs(point.getZ() - ArenaGenerator.MURDER_Z) <= 23) {
                arrow.remove();
            }
        }
        coins.clear();
        gold.clear();
    }
}
