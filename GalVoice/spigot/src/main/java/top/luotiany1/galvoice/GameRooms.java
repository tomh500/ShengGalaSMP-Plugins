package top.luotiany1.galvoice;

import com.andrei1058.bedwars.api.BedWars;
import com.andrei1058.bedwars.api.arena.GameState;
import com.andrei1058.bedwars.api.arena.IArena;
import com.andrei1058.bedwars.api.arena.team.ITeam;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import top.luotiany1.galvoice.VoicePacket.Channel;
import top.luotiany1.galvoice.VoiceRoom.Scope;

final class GameRooms {
    static Function<Player, VoiceRoom> bedWars(boolean waitingVoice) {
        BedWars api = Bukkit.getServicesManager().load(BedWars.class);
        if (api == null || api.getArenaUtil() == null) {
            throw new IllegalStateException("BedWars1058 arena service is unavailable");
        }
        return player -> bedWars(player, api.getArenaUtil().getArenaByPlayer(player), waitingVoice);
    }

    static VoiceRoom bedWars(Player player, IArena arena, boolean waitingVoice) {
        if (arena == null) {
            Channel channel = player.getGameMode() == GameMode.SPECTATOR || player.isDead()
                    ? Channel.SPECTATOR : Channel.NEARBY;
            return new VoiceRoom(Scope.WORLD, player.getWorld(), null, channel, null);
        }
        if (arena.isSpectator(player)) {
            return new VoiceRoom(Scope.BEDWARS, player.getWorld(), arena, Channel.SPECTATOR, null);
        }
        // Respawning players can rejoin battle and must not hear permanent spectators.
        if (!arena.isPlayer(player) || arena.isReSpawning(player.getUniqueId())
                || player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
            return null;
        }
        GameState status = arena.getStatus();
        if (status == GameState.playing) {
            ITeam team = arena.getTeam(player);
            return team == null ? null : new VoiceRoom(Scope.BEDWARS, player.getWorld(), arena, Channel.TEAM, team);
        }
        if (waitingVoice && (status == GameState.waiting || status == GameState.starting)) {
            return new VoiceRoom(Scope.BEDWARS, player.getWorld(), arena, Channel.NEARBY, null);
        }
        return null;
    }

    static Function<Player, VoiceRoom> minigames(Plugin plugin) throws ReflectiveOperationException {
        // The Minigames round lookup is optional on other backends.
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle voiceRound = lookup.findVirtual(plugin.getClass(), "voiceRound",
                MethodType.methodType(UUID.class, Player.class)).bindTo(plugin);
        MethodHandle voiceSpectator = lookup.findVirtual(plugin.getClass(), "voiceSpectator",
                MethodType.methodType(boolean.class, Player.class)).bindTo(plugin);
        return player -> plugin.isEnabled() ? minigames(player, voiceRound, voiceSpectator) : null;
    }

    private static VoiceRoom minigames(Player player, MethodHandle voiceRound, MethodHandle voiceSpectator) {
        UUID round;
        boolean spectating;
        try {
            round = (UUID) voiceRound.invokeExact(player);
            spectating = (boolean) voiceSpectator.invokeExact(player);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not query Minigames voice round", failure);
        }
        if (round == null && spectating) {
            return null; // World transfer in progress.
        }
        Channel channel = spectating || player.getGameMode() == GameMode.SPECTATOR || player.isDead()
                ? Channel.SPECTATOR : Channel.NEARBY;
        return new VoiceRoom(Scope.MINIGAMES, player.getWorld(), round, channel, null);
    }

    private GameRooms() {}
}
