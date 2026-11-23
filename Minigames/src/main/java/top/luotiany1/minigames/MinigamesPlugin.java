package top.luotiany1.minigames;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

public final class MinigamesPlugin extends JavaPlugin implements Listener {
    private static final String WORLD = "minigames";
    private static final String CHANNEL = "BungeeCord";
    private static final String PREFIX = ChatColor.GOLD + "[小游戏] " + ChatColor.WHITE;

    private final Map<String, GameRoom> rooms = new LinkedHashMap<>();
    private final Set<UUID> countdownVips = new HashSet<>();
    private DailyRotation rotation;
    private List<GameType> override;
    private List<GameType> active;
    private List<GameType> desired;
    private ZonedDateTime now;
    private World world;
    private ArenaWorlds arenaWorlds;
    private RoomMenus menus;
    private long tick;
    private String lobbyServer;
    private boolean forceProxyLobby;

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return new ArenaGenerator();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
    }

    private void loadSettings() {
        rotation = new DailyRotation(getConfig());
        now = rotation.now();
        String configuredMode = getConfig().getString("rotation.override", "daily");
        override = configuredMode.equals("daily") ? null : GameType.selection(Arrays.asList(configuredMode.split(",", -1)));
        desired = override == null ? rotation.gamesAt(now) : override;
        active = desired;
        lobbyServer = getConfig().getString("proxy.lobby-server", "lobby").trim();
        String forceReturn = getConfig().getString("rotation.force-return", "lobby");
        if (lobbyServer.isEmpty() || !forceReturn.equals("lobby") && !forceReturn.equals("waiting")) {
            throw new IllegalArgumentException("检查 proxy.lobby-server 和 rotation.force-return");
        }
        forceProxyLobby = forceReturn.equals("lobby");
        for (String id : getConfig().getStringList("countdown.vip-players")) {
            countdownVips.add(UUID.fromString(id));
        }

        ConfigurationSection section = getConfig().isSet("rooms") ? getConfig().getConfigurationSection("rooms")
                : getConfig().getDefaults().getConfigurationSection("rooms");
        if (section == null || section.getKeys(false).size() > 10) {
            throw new IllegalArgumentException("rooms 最多配置十个房间");
        }
        int slot = 0;
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (!id.matches("[a-z0-9_-]{1,24}") || entry == null) {
                throw new IllegalArgumentException("无效房间 ID " + id);
            }
            if (!entry.getBoolean("enabled", true)) continue;
            String name = entry.getString("name", id);
            if (name.trim().isEmpty() || name.length() > 28) {
                throw new IllegalArgumentException("房间显示名称应在 1–28 个字符：" + id);
            }
            String game = entry.getString("game", "auto");
            GameType dedicated = game.equals("auto") ? null : GameType.fromId(game);
            GameRoom room = new GameRoom(this, id, name, slot++, dedicated);
            for (GameType type : GameType.values()) room.rules.put(type, new RoomRules(getConfig(), id, type));
            rooms.put(id, room);
        }
        if (rooms.isEmpty()) throw new IllegalArgumentException("至少需要一个启用的房间");
    }

    private void prepareWorld() {
        World candidate = Bukkit.getWorld(WORLD);
        if (candidate == null || !(candidate.getGenerator() instanceof ArenaGenerator)) {
            getLogger().severe("检查 bukkit.yml 的 Minigames 地图生成器和 server.properties 的 level-name。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            arenaWorlds = new ArenaWorlds();
        } catch (IllegalStateException unavailable) {
            getLogger().severe(unavailable.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        world = candidate;
        world.setSpawnLocation(ArenaGenerator.lobby(world));
        world.setGameRuleValue("doDaylightCycle", "false");
        world.setGameRuleValue("doMobSpawning", "false");
        world.setGameRuleValue("doWeatherCycle", "false");
        world.setTime(6000);
        menus = new RoomMenus(this);
        menus.spawnNpcs();
        new LobbyChat(this);
        for (Player player : world.getPlayers()) waiting(player);
        getLogger().info("Arena ready. Daily game: " + labels(active, false) + "; rooms=" + rooms.size());
    }

    @Override
    public void onDisable() {
        if (menus != null) menus.removeNpcs();
        for (GameRoom room : rooms.values()) room.shutdown();
    }

    private void tick() {
        if (world == null) {
            prepareWorld();
            return;
        }
        tick++;
        if (tick % 20 == 0) {
            now = rotation.now();
            requestMode(override == null ? rotation.gamesAt(now) : override, false);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getGameMode() == GameMode.ADVENTURE && player.getLocation().getY() < 40
                        && !inMatch(player)) waiting(player);
                GameRoom room = roomOf(player);
                if (room != null) room.keepSpectatorNearby(player);
            }
            arenaWorlds.unloadIdle(tick, type -> rooms.values().stream().anyMatch(room -> room.uses(type)));
        }
        for (GameRoom room : rooms.values()) {
            if (room.playing() || !switchPending()) room.tick(tick, currentGame(room), now.toLocalDate());
        }
        if (switchPending() && !hasMatch()) applyMode();
    }

    private boolean hasMatch() {
        return rooms.values().stream().anyMatch(GameRoom::playing);
    }

    private void requestMode(List<GameType> next, boolean force) {
        if (!desired.equals(next)) {
            desired = next;
            rooms.values().forEach(GameRoom::clearCountdown);
            if (switchPending() && hasMatch() && !force) {
                announceLobby("将在所有对局结束后切换至 " + labels(next, false) + "。切换期间暂停开新局。");
            }
        }
        if (!switchPending()) return;
        if (force) {
            List<Player> affected = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (inMatch(player)) affected.add(player);
            }
            for (GameRoom room : rooms.values()) room.stopMatch();
            for (Player player : affected) {
                tell(player, "管理员强制切换玩法，本局已取消。");
                if (forceProxyLobby) connectLobby(player);
            }
        }
        if (!hasMatch()) applyMode();
    }

    private void applyMode() {
        active = desired;
        for (GameRoom room : rooms.values()) {
            if (room.game() != null && !active.contains(room.game())) room.clearQueue();
        }
        announceLobby("当前开放 " + labels(active, false) + "。打开 /mg 选择房间。");
        if (menus != null) menus.modeChanged();
        getLogger().info("Game changed to " + labels(active, true));
    }

    World world() {
        return world;
    }

    World arena(GameType type) {
        return arenaWorlds.acquire(type);
    }

    List<GameType> activeModes() {
        return active;
    }

    List<GameType> pendingModes() {
        return switchPending() ? desired : java.util.Collections.emptyList();
    }

    private boolean switchPending() {
        return !active.equals(desired);
    }

    GameType currentGame(GameRoom room) {
        GameType fixed = room.game();
        if (fixed == null) return active.get(0);
        return active.contains(fixed) ? fixed : null;
    }

    static String labels(List<GameType> types, boolean ids) {
        List<String> names = new ArrayList<>();
        for (GameType type : types) names.add(ids ? type.id : type.title);
        return String.join(ids ? "," : "、", names);
    }

    Collection<GameRoom> rooms() {
        return rooms.values();
    }

    GameRoom roomOf(Player player) {
        for (GameRoom room : rooms.values()) {
            if (room.contains(player)) return room;
        }
        return null;
    }

    boolean inMatch(Player player) {
        GameRoom room = roomOf(player);
        return room != null && room.inMatch(player);
    }

    /** Round identity for voice routing. The waiting area has no round. */
    public UUID voiceRound(Player player) {
        GameRoom room = roomOf(player);
        return room != null ? room.voiceRound(player) : null;
    }

    public boolean voiceSpectator(Player player) {
        GameRoom room = roomOf(player);
        return room != null && room.voiceSpectator(player);
    }

    private GameRoom defaultJoinRoom(GameType type) {
        GameRoom choice = null;
        for (GameRoom room : rooms.values()) {
            if (room.accepts(type) && room.queued() < room.rules.get(type).max
                    && (choice == null || room.queued() > choice.queued())) choice = room;
        }
        return choice;
    }

    boolean join(Player player, String id, GameType requested) {
        if (!player.hasPermission("minigames.play") || !player.isOnline()) return false;
        if (switchPending()) {
            tell(player, "当前正在等待切换玩法，请在结算后重新排队。");
            return false;
        }
        GameRoom previous = roomOf(player);
        if (previous != null && previous.competitive(player)) {
            tell(player, "你正在参赛。");
            return false;
        }
        GameRoom room = id == null ? null : rooms.get(id);
        if (id != null && room == null) {
            tell(player, "没有这个房间：" + id);
            return false;
        }
        GameType type = requested;
        if (type == null && id == null && previous != null && !previous.inMatch(player)) {
            type = previous.game();
        }
        if (type == null) type = room == null ? active.get(0) : currentGame(room);
        if (type == null || !active.contains(type)) {
            tell(player, "该玩法当前未开放。");
            return false;
        }
        if (previous != null && !previous.inMatch(player) && previous.game() == type
                && (room == null || previous == room)) {
            tell(player, "你已在 " + previous.name + " 排队。");
            return true;
        }
        if (room == null) room = defaultJoinRoom(type);
        if (room == null || !room.accepts(type) || room.queued() >= room.rules.get(type).max) {
            tell(player, "该玩法暂无可排队的房间，请用 /mg 查看其他房间。");
            return false;
        }
        if (previous != null) previous.leave(player);
        return room.join(player, type);
    }

    boolean watch(Player player, String id) {
        if (!player.hasPermission("minigames.play")) return false;
        GameRoom room = id == null ? rooms.values().stream().filter(GameRoom::watchable).findFirst().orElse(null)
                : rooms.get(id);
        GameRoom previous = roomOf(player);
        if (room == null || !room.watchable() || previous != null && previous.competitive(player)) {
            tell(player, "当前无法观战。");
            return false;
        }
        if (previous != null) previous.leave(player);
        return room.watch(player);
    }

    void leave(Player player) {
        GameRoom room = roomOf(player);
        if (room != null) room.leave(player);
        waiting(player);
        tell(player, "已返回小游戏等候区。");
    }

    void preparePlayer(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR) player.setSpectatorTarget(null);
        player.setGameMode(GameMode.ADVENTURE);
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setFallDistance(0);
        player.setFoodLevel(20);
        player.getInventory().clear();
        player.getInventory().setHeldItemSlot(0);
    }

    void waiting(Player player) {
        player.teleport(ArenaGenerator.lobby(world));
        preparePlayer(player);
        player.getInventory().setItem(0, RoomMenus.item(Material.COMPASS, "房间选择", "右键打开小游戏菜单"));
    }

    void spectate(Player player, Location point) {
        player.getInventory().clear();
        player.teleport(point);
        player.setGameMode(GameMode.SPECTATOR);
        player.setSpectatorTarget(null);
    }

    void connectLobby(Player player) {
        ByteArrayDataOutput message = ByteStreams.newDataOutput();
        message.writeUTF("Connect");
        message.writeUTF(lobbyServer);
        player.sendPluginMessage(this, CHANNEL, message.toByteArray());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        if (action.equals("mode")) return modeCommand(sender, args);
        if (action.equals("start") || action.equals("shorten")) return startCommand(sender, args);
        if (action.equals("status") || action.equals("rooms") || !(sender instanceof Player) && action.equals("menu")) {
            tell(sender, now.toLocalDate() + " 当前 " + labels(active, false)
                    + (switchPending() ? "，待切换至 " + labels(desired, false) : ""));
            for (GameRoom room : rooms.values()) {
                GameType type = currentGame(room);
                if (type == null) {
                    tell(sender, room.id + " " + room.name + "，" + room.dedicated.title + " 未开放");
                    continue;
                }
                RoomRules policy = room.rules.get(type);
                String gameLabel = room.game() == null && active.size() > 1 ? "待选玩法" : type.title;
                tell(sender, room.id + " " + room.name + " · " + gameLabel + "，排队 " + room.queued() + "/" + policy.max
                        + "，" + policy.min + " 人开始 " + policy.countdown / 20 + " 秒倒计时"
                        + (room.playing() ? "，局内 " + room.alive() + " 人" : ""));
                if (room.queued() > 0) tell(sender, "排队玩家：" + room.queuedNames());
            }
            return true;
        }
        if (!(sender instanceof Player) || menus == null
                || args.length > (action.equals("join") ? 3 : 2)) return false;
        Player player = (Player) sender;
        if (!player.hasPermission("minigames.play")) return true;
        String room = args.length >= 2 ? args[1] : null;
        switch (action) {
            case "menu": menus.open(player, null, null); return true;
            case "join":
                try {
                    GameType type = args.length == 3 ? GameType.fromId(args[2]) : null;
                    if (type == null && room != null && !rooms.containsKey(room)) {
                        type = GameType.fromId(room);
                        room = null;
                    }
                    join(player, room, type);
                } catch (IllegalArgumentException invalid) {
                    tell(player, invalid.getMessage());
                }
                return true;
            case "watch": watch(player, room); return true;
            case "leave": leave(player); return true;
            case "lobby": connectLobby(player); return true;
            default: return false;
        }
    }

    int shorteningSeconds(CommandSender sender) {
        if (sender.hasPermission("minigames.manage") || sender.hasPermission("minigames.countdown.admin")) return 5;
        boolean configuredVip = sender instanceof Player && countdownVips.contains(((Player) sender).getUniqueId());
        return sender.hasPermission("minigames.countdown.vip") || configuredVip ? 30 : 0;
    }

    boolean startCommand(CommandSender sender, String[] args) {
        if (args.length > 3 || world == null) return false;
        boolean force = args.length > 1 && args[args.length - 1].equalsIgnoreCase("force");
        if (args.length == 3 && !force) return false;
        int seconds = shorteningSeconds(sender);
        if (seconds == 0 || force && seconds != 5) {
            tell(sender, "你没有执行该开局操作的权限。");
            return true;
        }
        if (switchPending()) {
            tell(sender, "玩法切换期间暂停开新局。");
            return true;
        }
        boolean explicit = args.length > 1 && !args[1].equalsIgnoreCase("force");
        Player player = sender instanceof Player ? (Player) sender : null;
        GameRoom playerRoom = player == null ? null : roomOf(player);
        GameRoom room = explicit ? rooms.get(args[1]) : playerRoom;
        if (room == null && !explicit && seconds == 5) {
            room = rooms.values().stream().filter(candidate -> candidate.queued() > 0 && !candidate.playing())
                    .findFirst().orElse(null);
            if (room == null && player != null) room = defaultJoinRoom(active.get(0));
        }
        if (room == null || room.playing() || seconds == 30 && (playerRoom != room || room.inMatch(player))) {
            tell(sender, "选择一个你可以操作且尚未开局的房间。");
            return true;
        }
        if (!force) {
            if (!room.shorten(tick, seconds)) tell(sender, "人数尚未达标，或剩余倒计时已足够短。");
            return true;
        }
        GameType type = currentGame(room);
        if (type == null) {
            tell(sender, "该房间的玩法未开放。");
            return true;
        }
        if (room.queued() == 0 && player != null) join(player, room.id, type);
        if (!room.forceStart(tick, type, now.toLocalDate())) tell(sender, "强制开局至少需要一名排队玩家。");
        return true;
    }

    private boolean modeCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("minigames.manage")) {
            tell(sender, "你没有切换玩法的权限。");
            return true;
        }
        if (args.length == 1 || args[1].equals("list")) {
            tell(sender, "当前 " + labels(active, true) + (switchPending() ? "，待切换 " + labels(desired, true) : "")
                    + "。/mg mode <daily|玩法ID[,玩法ID]> [force]");
            return true;
        }
        if (args.length > 3 || args.length == 3 && !args[2].equalsIgnoreCase("force") || world == null) return false;
        String target = args[1].toLowerCase(Locale.ROOT);
        try {
            List<GameType> nextOverride = target.equals("daily") ? null
                    : GameType.selection(Arrays.asList(target.split(",", -1)));
            getConfig().set("rotation.override", target);
            saveConfig();
            override = nextOverride;
            now = rotation.now();
            requestMode(override == null ? rotation.gamesAt(now) : override, args.length == 3);
            tell(sender, switchPending() ? "已登记切换：" + labels(desired, false) : "当前玩法：" + labels(active, false));
        } catch (IllegalArgumentException invalid) {
            tell(sender, invalid.getMessage());
        }
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (world == null) return;
        waiting(event.getPlayer());
        tell(event.getPlayer(), "当前开放：" + labels(active, false) + "。右键指南针或输入 /mg 选房。");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        GameRoom room = roomOf(event.getPlayer());
        if (room != null) room.leave(event.getPlayer());
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        event.setCancelled(true);
        if (!(event instanceof EntityDamageByEntityEvent)) return;
        EntityDamageByEntityEvent combat = (EntityDamageByEntityEvent) event;
        Player target = (Player) event.getEntity();
        Player attacker = null;
        if (combat.getDamager() instanceof Player) {
            attacker = (Player) combat.getDamager();
        } else if (combat.getDamager() instanceof Projectile) {
            ProjectileSource source = ((Projectile) combat.getDamager()).getShooter();
            if (source instanceof Player) attacker = (Player) source;
        }
        if (attacker == null) return;
        GameRoom room = roomOf(target);
        if (room != null) room.attack(attacker, target, combat);
    }

    @EventHandler public void onHunger(FoodLevelChangeEvent event) { event.setCancelled(true); }
    @EventHandler public void onBreak(BlockBreakEvent event) { event.setCancelled(true); }
    @EventHandler public void onPlace(BlockPlaceEvent event) { event.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent event) { event.setCancelled(true); }
    @EventHandler public void onPortal(PlayerPortalEvent event) { event.setCancelled(true); }

    private void announceLobby(String text) {
        for (Player player : world.getPlayers()) {
            if (!inMatch(player)) tell(player, text);
        }
    }

    void tell(CommandSender sender, String text) {
        sender.sendMessage(PREFIX + text);
    }
}
