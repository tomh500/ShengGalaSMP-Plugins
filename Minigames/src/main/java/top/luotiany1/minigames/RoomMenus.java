package top.luotiany1.minigames;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import top.luotiany1.mcqq.crossplay.BedrockForm;
import top.luotiany1.mcqq.crossplay.GalApi;
import top.luotiany1.mcqq.crossplay.SpigotMasterGAL;

final class RoomMenus implements Listener {
    private static final String NPC_TAG = "minigames-room-npc";
    private static final String UPDATE = "minigames:rooms_changed";
    private final MinigamesPlugin plugin;
    private final Map<UUID, GameRoom> npcs = new HashMap<>();
    private final SpigotMasterGAL bridge;
    private final GalApi<Player> gal;

    RoomMenus(MinigamesPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Plugin installed = Bukkit.getPluginManager().getPlugin("SpigotMasterGAL");
        bridge = installed != null && installed.isEnabled() && installed instanceof SpigotMasterGAL
                ? (SpigotMasterGAL) installed : null;
        gal = bridge == null ? null : bridge.api();
        if (gal != null) registerGal();
    }

    private void registerGal() {
        gal.onRequest("minigames:rooms", (player, bytes) -> bytes.length == 0 ? snapshot(player) : null);
        gal.onRequest("minigames:join", (player, bytes) -> {
            if (bytes.length > 49) return null;
            String[] selection = new String(bytes, StandardCharsets.UTF_8).split(":", -1);
            if (selection.length > 2 || !selection[0].matches("[a-z0-9_-]{1,24}")) return null;
            try {
                GameType type = selection.length == 2 ? GameType.fromId(selection[1]) : null;
                return plugin.join(player, selection[0], type) ? snapshot(player) : null;
            } catch (IllegalArgumentException invalid) {
                return null;
            }
        });
        gal.onRequest("minigames:leave", (player, bytes) -> {
            if (bytes.length != 0 || !player.hasPermission("minigames.play")) return null;
            plugin.leave(player);
            return snapshot(player);
        });
        gal.onReady(this::update);
        plugin.getLogger().info("SpigotMasterGAL routes ready: minigames:rooms, minigames:join, minigames:leave");
    }

    void modeChanged() {
        if (gal != null) plugin.world().getPlayers().forEach(this::update);
    }

    private void update(Player player) {
        if (gal.canSendEvent(player, UPDATE)) {
            byte[] payload = snapshot(player);
            if (payload != null) gal.sendEvent(player, UPDATE, payload);
        }
    }

    private byte[] snapshot(Player player) {
        if (!player.hasPermission("minigames.play")) return null;
        JsonObject response = new JsonObject();
        response.addProperty("schema", 1);
        List<GameType> active = plugin.activeModes();
        List<GameType> pending = plugin.pendingModes();
        response.addProperty("game", active.get(0).id);
        response.add("games", ids(active));
        if (!pending.isEmpty()) {
            response.addProperty("pending", pending.get(0).id);
            response.add("pendingGames", ids(pending));
        }
        GameRoom current = plugin.roomOf(player);
        if (current != null) response.addProperty("room", current.id);
        JsonArray roomList = new JsonArray();
        for (GameRoom room : plugin.rooms()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", room.id);
            item.addProperty("name", room.name);
            item.addProperty("queued", room.queued());
            item.addProperty("alive", room.alive());
            item.addProperty("playing", room.playing());
            if (room.game() != null) item.addProperty("game", room.game().id);
            JsonArray options = new JsonArray();
            for (GameType game : active) {
                if (!room.accepts(game)) continue;
                RoomRules rules = room.rules.get(game);
                JsonObject option = new JsonObject();
                option.addProperty("game", game.id);
                option.addProperty("minPlayers", rules.min);
                option.addProperty("maxPlayers", rules.max);
                option.addProperty("countdownSeconds", rules.countdown / 20);
                options.add(option);
            }
            item.add("options", options);
            roomList.add(item);
        }
        response.add("rooms", roomList);
        return response.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static JsonArray ids(List<GameType> games) {
        JsonArray result = new JsonArray();
        for (GameType game : games) result.add(game.id);
        return result;
    }

    void spawnNpcs() {
        Location lobby = ArenaGenerator.lobby(plugin.world());
        for (Entity entity : plugin.world().getNearbyEntities(lobby, 9, 5, 7)) {
            if (entity.getScoreboardTags().contains(NPC_TAG)) entity.remove();
        }
        if (!plugin.getConfig().getBoolean("npc.enabled")) return;
        spawnNpc(lobby.clone().add(-3, 0, 0), plugin.getConfig().getString("npc.name"), null);
        if (!plugin.getConfig().getBoolean("npc.room-npcs")) return;
        int index = 0;
        for (GameRoom room : plugin.rooms()) {
            double x = -4 + index % 5 * 2;
            double z = index < 5 ? -3.5 : 3.5;
            spawnNpc(lobby.clone().add(x, 0, z), room.name + " · 排队", room);
            index++;
        }
    }

    private void spawnNpc(Location location, String name, GameRoom room) {
        Villager npc = plugin.world().spawn(location, Villager.class);
        npc.addScoreboardTag(NPC_TAG);
        npc.setAI(false);
        npc.setAdult();
        npc.setInvulnerable(true);
        npc.setSilent(true);
        npc.setCollidable(false);
        npc.setRemoveWhenFarAway(false);
        npc.setCustomName(ChatColor.GOLD + name);
        npc.setCustomNameVisible(true);
        npcs.put(npc.getUniqueId(), room);
    }

    void removeNpcs() {
        for (Entity entity : plugin.world().getEntities()) {
            if (npcs.containsKey(entity.getUniqueId())) entity.remove();
        }
        npcs.clear();
    }

    void open(Player player, GameRoom room, GameType filter) {
        if (!player.hasPermission("minigames.play") || plugin.inMatch(player)) return;
        List<Entry> entries = entries(player, room, filter);
        String title = room != null ? room.name : filter != null ? filter.title : "每日小游戏";
        if (bridge != null && plugin.getConfig().getBoolean("ui.bedrock-forms")) {
            List<BedrockForm.Button> buttons = new ArrayList<>();
            for (Entry entry : entries) buttons.add(new BedrockForm.Button(entry.label + "\n" + entry.subtitle));
            List<GameType> pending = plugin.pendingModes();
            String content = pending.isEmpty() ? "当前开放：" + MinigamesPlugin.labels(plugin.activeModes(), false)
                    : "等待对局结束后切换到 " + MinigamesPlugin.labels(pending, false);
            if (bridge.showBedrockForm(player, new BedrockForm(title, content, buttons), index -> {
                if (!player.isOnline() || plugin.inMatch(player)) return;
                if (index == BedrockForm.UNAVAILABLE) openChest(player, title, entries);
                if (index >= 0 && index < entries.size()) entries.get(index).action.accept(player);
            })) return;
        }
        openChest(player, title, entries);
    }

    private List<Entry> entries(Player viewer, GameRoom selected, GameType filter) {
        List<Entry> entries = new ArrayList<>();
        List<GameType> modes = plugin.activeModes();
        if (selected != null) {
            for (GameType game : modes) {
                if (selected.accepts(game) && (filter == null || filter == game)) {
                    entries.add(new Entry(Material.EMERALD, "排队 · " + game.title, detail(selected, game),
                            player -> plugin.join(player, selected.id, game)));
                }
            }
            entries.add(new Entry(Material.EYE_OF_ENDER, "观战", "查看该房间的对局", player -> plugin.watch(player, selected.id)));
            if (plugin.shorteningSeconds(viewer) > 0) {
                entries.add(new Entry(Material.WATCH, "缩短倒计时", "达到人数门槛后可用",
                        player -> plugin.startCommand(player, new String[] {"start", selected.id})));
            }
            entries.add(new Entry(Material.COMPASS, "返回房间列表", "选择其他房间", player -> open(player, null, filter)));
        } else if (filter == null && modes.size() > 1) {
            for (GameType mode : modes) {
                entries.add(new Entry(mode.icon, mode.title, "查看可选房间", player -> open(player, null, mode)));
            }
        } else {
            GameType mode = filter == null ? modes.get(0) : filter;
            for (GameRoom room : plugin.rooms()) {
                if (room.accepts(mode) && modes.contains(mode)) {
                    entries.add(new Entry(Material.WOOL, room.name, detail(room, mode), player -> open(player, room, mode)));
                }
            }
            entries.add(new Entry(Material.EYE_OF_ENDER, "观战", "进入第一个进行中的房间", player -> plugin.watch(player, null)));
            if (filter != null && modes.size() > 1) {
                entries.add(new Entry(Material.COMPASS, "返回玩法列表", "选择其他玩法", player -> open(player, null, null)));
            }
        }
        entries.add(new Entry(Material.BARRIER, "退出排队", "回到小游戏等候区", plugin::leave));
        entries.add(new Entry(Material.NETHER_STAR, "群组大厅", "返回主大厅", plugin::connectLobby));
        return entries;
    }

    private String detail(GameRoom room, GameType game) {
        RoomRules rules = room.rules.get(game);
        return "排队 " + room.queued() + "/" + rules.max + " · " + rules.min + " 人开局 · "
                + rules.countdown / 20 + " 秒" + (room.playing() ? " · 对局中，可排下一局" : "");
    }

    private void openChest(Player player, String title, List<Entry> entries) {
        RoomInventory holder = new RoomInventory(entries);
        holder.inventory = Bukkit.createInventory(holder, entries.size() > 9 ? 18 : 9, title);
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            holder.inventory.setItem(i, item(entry.material, entry.label, entry.subtitle));
        }
        player.openInventory(holder.inventory);
    }

    static ItemStack item(Material material, String name, String lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + name);
        meta.setLore(Arrays.asList(ChatColor.GRAY + lore));
        stack.setItemMeta(meta);
        return stack;
    }

    @EventHandler
    public void onNpcClick(PlayerInteractEntityEvent event) {
        UUID npc = event.getRightClicked().getUniqueId();
        if (!npcs.containsKey(npc)) return;
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND) open(event.getPlayer(), npcs.get(npc), null);
    }

    @EventHandler
    public void onCompass(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getAction().name().startsWith("RIGHT_CLICK")
                || event.getItem() == null || event.getItem().getType() != Material.COMPASS) return;
        event.setCancelled(true);
        open(event.getPlayer(), null, null);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder owner = event.getView().getTopInventory().getHolder();
        if (!(owner instanceof RoomInventory)) return;
        event.setCancelled(true);
        List<Entry> entries = ((RoomInventory) owner).entries;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= entries.size() || !(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != owner) return;
            player.closeInventory();
            entries.get(slot).action.accept(player);
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof RoomInventory) event.setCancelled(true);
    }

    private static final class Entry {
        final Material material;
        final String label;
        final String subtitle;
        final Consumer<Player> action;

        Entry(Material material, String label, String subtitle, Consumer<Player> action) {
            this.material = material;
            this.label = label;
            this.subtitle = subtitle;
            this.action = action;
        }
    }

    private static final class RoomInventory implements InventoryHolder {
        final List<Entry> entries;
        Inventory inventory;

        RoomInventory(List<Entry> entries) {
            this.entries = entries;
        }

        @Override public Inventory getInventory() { return inventory; }
    }
}
