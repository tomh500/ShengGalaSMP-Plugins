package top.luotiany1.minigames;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

final class GameRoom {
    final String id;
    final String name;
    final int slot;
    final GameType dedicated;
    final Map<GameType, RoomRules> rules = new EnumMap<>(GameType.class);
    private final MinigamesPlugin plugin;
    private final Map<UUID, Player> queue = new LinkedHashMap<>();
    private final Set<UUID> spectators = new LinkedHashSet<>();
    private long countdownEnd;
    private GameType countdownGame;
    private GameType queueGame;
    private Round round;

    GameRoom(MinigamesPlugin plugin, String id, String name, int slot, GameType dedicated) {
        this.plugin = plugin;
        this.id = id;
        this.name = name;
        this.slot = slot;
        this.dedicated = dedicated;
    }

    boolean contains(Player player) {
        UUID id = player.getUniqueId();
        return queue.containsKey(id) || inMatch(player);
    }

    boolean inMatch(Player player) {
        UUID id = player.getUniqueId();
        return spectators.contains(id) || round != null && round.alive.containsKey(id);
    }

    boolean competitive(Player player) {
        return round != null && round.alive.containsKey(player.getUniqueId());
    }

    UUID voiceRound(Player player) {
        return round != null && player.getWorld() == round.world && inMatch(player) ? round.id : null;
    }

    boolean voiceSpectator(Player player) {
        return round != null && spectators.contains(player.getUniqueId());
    }

    int queued() {
        return queue.size();
    }

    GameType game() {
        if (dedicated != null) return dedicated;
        if (queueGame != null) return queueGame;
        return round == null ? null : round.type;
    }

    boolean accepts(GameType type) {
        GameType locked = game();
        return locked == null || locked == type;
    }

    String queuedNames() {
        List<String> names = new ArrayList<>();
        for (Player player : queue.values()) names.add(player.getName());
        return String.join("、", names);
    }

    boolean playing() {
        return round != null;
    }

    boolean watchable() {
        return round != null && round.returnAt == 0;
    }

    boolean uses(GameType type) {
        return round != null && round.type == type;
    }

    int alive() {
        return round == null ? 0 : round.alive.size();
    }

    boolean join(Player player, GameType type) {
        if (!accepts(type) || queue.size() >= rules.get(type).max) return false;
        queue.put(player.getUniqueId(), player);
        queueGame = type;
        plugin.waiting(player);
        announce(player.getName() + " 加入 " + type.title + " 队列（"
                + queue.size() + "/" + rules.get(type).min + " 人开局）。");
        return true;
    }

    boolean watch(Player player) {
        if (round == null || round.returnAt != 0) return false;
        spectate(player);
        return true;
    }

    void leave(Player player) {
        UUID playerId = player.getUniqueId();
        queue.remove(playerId);
        if (queue.isEmpty()) queueGame = null;
        spectators.remove(playerId);
        if (round != null && round.alive.remove(playerId) != null) {
            round.game.onEliminated(player);
            announce(player.getName() + " 退出了本局。");
        }
    }

    void tick(long tick, GameType type, LocalDate today) {
        if (round != null) {
            tickRound(tick);
            return;
        }
        if (type == null) {
            clearCountdown();
            return;
        }
        RoomRules settings = rules.get(type);
        if (queue.size() < settings.min) {
            if (countdownGame != null) announce("人数不足，倒计时取消。");
            clearCountdown();
            return;
        }
        if (countdownGame != type) {
            countdownGame = type;
            countdownEnd = tick + settings.countdown;
            announce(type.title + " 将在 " + settings.countdown / 20 + " 秒后开始。");
        }
        long remaining = countdownEnd - tick;
        if (remaining == 100 || remaining == 60 || remaining == 40 || remaining == 20) {
            announce("还有 " + remaining / 20 + " 秒开局。");
        }
        if (remaining <= 0) start(tick, type, today, settings, false);
    }

    boolean shorten(long tick, int seconds) {
        if (round != null || countdownGame == null || queue.size() < rules.get(countdownGame).min
                || countdownEnd - tick <= seconds * 20L) return false;
        countdownEnd = tick + seconds * 20L;
        announce("倒计时已缩短至 " + seconds + " 秒。");
        return true;
    }

    boolean forceStart(long tick, GameType type, LocalDate today) {
        if (round != null || queue.isEmpty() || !accepts(type)) return false;
        return start(tick, type, today, rules.get(type), true);
    }

    private boolean start(long tick, GameType type, LocalDate today, RoomRules settings, boolean forced) {
        World gameWorld;
        try {
            gameWorld = plugin.arena(type);
        } catch (IllegalStateException failure) {
            countdownEnd = tick + settings.countdown;
            plugin.getLogger().warning(failure.getMessage());
            announce("竞技场暂时无法加载。");
            return false;
        }
        List<Player> players = new ArrayList<>(queue.values());
        players = new ArrayList<>(players.subList(0, Math.min(players.size(), settings.max)));
        MiniGame game = type.factory.create(gameWorld, slot, settings);
        round = new Round(type, game, gameWorld, today, tick + settings.deadline,
                forced && players.size() < settings.min);
        for (Player player : players) {
            UUID playerId = player.getUniqueId();
            queue.remove(playerId);
            round.alive.put(playerId, player);
            round.participants.put(playerId, player.getName());
            plugin.preparePlayer(player);
        }
        if (queue.isEmpty()) queueGame = null;
        clearCountdown();
        game.start(players);
        announce(type.title + " 开始！" + (round.practice ? "本局为练习。" : "") + type.instructions);
        return true;
    }

    private void tickRound(long tick) {
        if (round.returnAt != 0) {
            if (tick >= round.returnAt) closeRound();
            return;
        }
        for (Player player : new ArrayList<>(round.alive.values())) {
            if (round.game.isEliminated(player)) eliminate(player);
        }
        MiniGame.Result result = round.game.result(round.alive.values(), tick >= round.deadline);
        if (result == null) {
            round.game.tick(tick, round.alive.values());
        } else {
            finish(tick, result);
        }
    }

    void attack(Player attacker, Player target, EntityDamageByEntityEvent event) {
        if (round == null || round.returnAt != 0 || !competitive(attacker) || !competitive(target)) return;
        Player eliminated = round.game.attack(attacker, target, event);
        if (eliminated != null) eliminate(eliminated);
    }

    private void eliminate(Player player) {
        round.alive.remove(player.getUniqueId());
        round.game.onEliminated(player);
        spectate(player);
        announce(player.getName() + " 已出局，剩余 " + round.alive.size() + " 人。");
    }

    private void spectate(Player player) {
        spectators.add(player.getUniqueId());
        Location view = ArenaGenerator.spectator(round.world, slot, round.type);
        plugin.spectate(player, view);
    }

    void keepSpectatorNearby(Player player) {
        if (round == null || !spectators.contains(player.getUniqueId())) return;
        Location center = ArenaGenerator.spectator(round.world, slot, round.type);
        Location current = player.getLocation();
        if (player.getGameMode() != GameMode.SPECTATOR || player.getWorld() != round.world
                || Math.abs(current.getX() - center.getX()) > 31
                || Math.abs(current.getZ() - center.getZ()) > 27 || Math.abs(current.getY() - center.getY()) > 35) {
            plugin.spectate(player, center);
        }
    }

    private void finish(long tick, MiniGame.Result result) {
        round.returnAt = tick + 80;
        announce(round.practice ? "练习结束。" : result.message);
        for (Player player : round.alive.values()) spectate(player);
        round.alive.clear();
        RoundResultEvent.Outcome outcome = round.practice ? RoundResultEvent.Outcome.PRACTICE : result.outcome;
        Bukkit.getPluginManager().callEvent(new RoundResultEvent(round.id, round.type.id, round.date,
                round.participants, round.practice ? null : result.winner, outcome, id,
                round.practice ? null : result.winningTeam));
        plugin.getLogger().info("Round " + round.id + " " + id + " " + round.type.id
                + " " + outcome);
    }

    private void closeRound() {
        round.game.reset();
        round = null;
        for (UUID id : spectators) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) plugin.waiting(player);
        }
        spectators.clear();
    }

    void stopMatch() {
        if (round != null) {
            for (Player player : new ArrayList<>(round.alive.values())) plugin.waiting(player);
            closeRound();
        }
    }

    void clearQueue() {
        if (!queue.isEmpty()) announce("玩法已更换，请重新选择房间。");
        queue.clear();
        queueGame = null;
        clearCountdown();
    }

    void shutdown() {
        stopMatch();
        queue.clear();
        queueGame = null;
        clearCountdown();
    }

    void clearCountdown() {
        countdownGame = null;
        countdownEnd = 0;
    }

    void announce(String message) {
        Set<Player> audience = new LinkedHashSet<>(queue.values());
        if (round != null) audience.addAll(round.alive.values());
        for (UUID id : spectators) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) audience.add(player);
        }
        for (Player player : audience) plugin.tell(player, "[" + name + "] " + message);
    }

    private static final class Round {
        final UUID id = UUID.randomUUID();
        final GameType type;
        final MiniGame game;
        final World world;
        final LocalDate date;
        final long deadline;
        final boolean practice;
        final Map<UUID, Player> alive = new LinkedHashMap<>();
        final Map<UUID, String> participants = new LinkedHashMap<>();
        long returnAt;

        Round(GameType type, MiniGame game, World world, LocalDate date, long deadline, boolean practice) {
            this.type = type;
            this.game = game;
            this.world = world;
            this.date = date;
            this.deadline = deadline;
            this.practice = practice;
        }
    }
}
