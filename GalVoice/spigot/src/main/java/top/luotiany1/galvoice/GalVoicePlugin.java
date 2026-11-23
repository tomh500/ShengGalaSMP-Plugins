package top.luotiany1.galvoice;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import top.luotiany1.galvoice.VoicePacket.Channel;
import top.luotiany1.galvoice.VoiceRoom.Scope;
import top.luotiany1.mcqq.crossplay.GalApi;
import top.luotiany1.mcqq.crossplay.SpigotMasterGAL;

public final class GalVoicePlugin extends JavaPlugin implements Listener {
    private final Map<UUID, Peer> peers = new HashMap<>();
    private GalApi<Player> gal;
    private Function<Player, VoiceRoom> gameRooms;
    private Set<String> disabledWorlds;
    private Set<String> teamWorlds;
    private boolean proximity;
    private boolean spectators;
    private boolean allowWorldListening;
    private double range;
    private double fadeStart;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        disabledWorlds = new HashSet<>(getConfig().getStringList("disabled-worlds"));
        teamWorlds = new HashSet<>(getConfig().getStringList("scoreboard-team-worlds"));
        proximity = getConfig().getBoolean("proximity.enabled", true);
        spectators = getConfig().getBoolean("spectators.enabled", true);
        allowWorldListening = getConfig().getBoolean("listen-mode.allow-world", false);
        range = distance("proximity.range-blocks", 48, 1, 512);
        fadeStart = distance("proximity.fade-start-blocks", 8, 0, range);
        try {
            Plugin bedWars = getServer().getPluginManager().getPlugin("BedWars1058");
            Plugin minigames = getServer().getPluginManager().getPlugin("Minigames");
            if (bedWars != null) {
                gameRooms = GameRooms.bedWars(getConfig().getBoolean("bedwars.waiting-proximity", true));
            } else if (minigames != null) {
                gameRooms = GameRooms.minigames(minigames);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            getLogger().log(Level.SEVERE, "Voice disabled because the installed game integration could not be loaded", failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        gal = JavaPlugin.getPlugin(SpigotMasterGAL.class).api();
        gal.onEvent(VoicePacket.UPLOAD, this::upload);
        if (allowWorldListening) {
            gal.onEvent(VoicePacket.LISTEN_MODE, this::listenMode);
            gal.onReady(player -> {
                Peer peer = peers.get(player.getUniqueId());
                if (peer != null) {
                    peer.worldListening = false;
                }
            });
        }
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void listenMode(Player player, byte[] data) {
        if (isEnabled() && data.length == 1 && (data[0] == 0 || data[0] == 1)
                && player.hasPermission("galvoice.listen") && gal.canSendEvent(player, VoicePacket.AUDIO)) {
            peer(player).worldListening = data[0] == 1;
        }
    }

    private double distance(String key, double fallback, double min, double max) {
        double value = getConfig().getDouble(key, fallback);
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : Math.min(fallback, max);
    }

    private VoiceRoom room(Player player) {
        String world = player.getWorld().getName();
        if (disabledWorlds.contains(world)) {
            return null;
        }
        if (gameRooms != null) {
            return gameRooms.apply(player);
        }
        if (player.getGameMode() == GameMode.SPECTATOR || player.isDead()) {
            return new VoiceRoom(Scope.WORLD, player.getWorld(), null, Channel.SPECTATOR, null);
        }
        if (teamWorlds.contains(world)) {
            ScoreboardManager manager = getServer().getScoreboardManager();
            Team team = manager == null ? null : manager.getMainScoreboard().getEntryTeam(player.getName());
            if (team != null) {
                return new VoiceRoom(Scope.WORLD, player.getWorld(), null, Channel.TEAM, team.getName());
            }
        }
        return new VoiceRoom(Scope.WORLD, player.getWorld(), null, Channel.NEARBY, null);
    }

    private void upload(Player speaker, byte[] audio) {
        if (!isEnabled() || !VoicePacket.validAudio(audio) || !speaker.hasPermission("galvoice.speak")
                || !gal.canSendEvent(speaker, VoicePacket.AUDIO)) {
            return;
        }
        long now = System.nanoTime();
        if (!peer(speaker).upload.take(now)) {
            return;
        }
        VoiceRoom room = room(speaker);
        if (room == null || (room.channel == Channel.NEARBY && !proximity)
                || (room.channel == Channel.SPECTATOR && !spectators)) {
            return;
        }
        Location from = room.channel == Channel.NEARBY ? speaker.getLocation() : null;
        byte[] name = VoicePacket.nameBytes(speaker.getName());
        for (Player listener : room.world.getPlayers()) {
            if (listener == speaker || !listener.hasPermission("galvoice.listen")
                    || !gal.canSendEvent(listener, VoicePacket.AUDIO)) {
                continue;
            }
            Location to = from == null ? null : listener.getLocation();
            Peer listenerPeer = peers.get(listener.getUniqueId());
            boolean worldListening = allowWorldListening && listenerPeer != null && listenerPeer.worldListening;
            int gain = room.gainTo(room(listener), from, to, fadeStart, range, worldListening);
            if (gain > 0 && peer(listener).download.take(now)) {
                byte[] packet = VoicePacket.relay(speaker.getUniqueId(), name, room.channel, gain, audio);
                if (packet != null) {
                    gal.sendEvent(listener, VoicePacket.AUDIO, packet);
                }
            }
        }
    }

    private Peer peer(Player player) {
        return peers.computeIfAbsent(player.getUniqueId(), id -> new Peer());
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        peers.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public void onDisable() {
        peers.clear();
    }

    private static final class Peer {
        final Bucket upload = new Bucket(25, 6);
        final Bucket download = new Bucket(200, 24);
        boolean worldListening;
    }

    private static final class Bucket {
        final int rate;
        final int burst;
        long last = System.nanoTime();
        double tokens;

        Bucket(int rate, int burst) {
            this.rate = rate;
            this.burst = burst;
            this.tokens = burst;
        }

        boolean take(long now) {
            tokens = Math.min(burst, tokens + Math.max(0, now - last) * (rate / 1e9));
            last = now;
            if (tokens < 1) {
                return false;
            }
            tokens--;
            return true;
        }
    }
}
