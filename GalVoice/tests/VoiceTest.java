package top.luotiany1.galvoice;

import com.andrei1058.bedwars.api.arena.GameState;
import com.andrei1058.bedwars.api.arena.IArena;
import com.andrei1058.bedwars.api.arena.team.ITeam;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import top.luotiany1.galvoice.VoicePacket.Channel;
import top.luotiany1.galvoice.VoiceRoom.Scope;

public final class VoiceTest {
    public static void main(String[] args) {
        codecAndWire();
        roomIsolation();
        bedWarsState();
        System.out.println("PASS voice codec, packet checks, attenuation, team/arena isolation and BedWars death states");
    }

    private static void codecAndWire() {
        short[] samples = new short[VoicePacket.SAMPLES];
        check(Arrays.equals(samples, ImaAdpcm.decode(ImaAdpcm.encode(samples))), "silence");
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) (Math.sin(i * 0.15) * 14000);
        }
        byte[] audio = ImaAdpcm.encode(samples);
        check(VoicePacket.validAudio(audio), "encoded frame");
        short[] decoded = ImaAdpcm.decode(audio);
        long sumError = 0;
        for (int i = 0; i < decoded.length; i++) {
            sumError += Math.abs(decoded[i] - samples[i]);
        }
        check(sumError / decoded.length < 600, "voice reconstruction");
        UUID speaker = UUID.randomUUID();
        byte[] relay = VoicePacket.relay(speaker, VoicePacket.nameBytes("语音测试"), Channel.TEAM, 145, audio);
        VoicePacket packet = VoicePacket.decode(relay);
        check(packet != null && packet.speaker.equals(speaker) && packet.name.equals("语音测试")
                && packet.channel == Channel.TEAM && packet.gain == 145 && Arrays.equals(packet.audio, audio),
                "relay identity and fields");
        check(VoicePacket.decode(Arrays.copyOf(relay, relay.length - 1)) == null, "truncated relay");
        relay[16] = 99;
        check(VoicePacket.decode(relay) == null, "unknown channel");
        relay[16] = 1;
        relay[18] = 0;
        check(VoicePacket.decode(relay) == null, "invalid name length");
        audio[2] = 89;
        check(!VoicePacket.validAudio(audio), "invalid ADPCM index");
    }

    private static void roomIsolation() {
        World world = fake(World.class, method -> null);
        World otherWorld = fake(World.class, method -> null);
        Location here = new Location(world, 0, 64, 0);
        Location nearby = new Location(world, 0, 64, 7);
        Location distant = new Location(world, 0, 64, 30);
        VoiceRoom arena = new VoiceRoom(Scope.MINIGAMES, world, "round 1", Channel.NEARBY, null);
        check(arena.gainTo(arena, here, nearby, 8, 48, false) == 255, "nearby full volume");
        check(arena.gainTo(arena, here, distant, 8, 48, false) == 115, "linear distance fade");
        check(arena.gainTo(arena, here, distant, 8, 29, true) == 0, "match never uses world listening");
        VoiceRoom normal = new VoiceRoom(Scope.WORLD, world, null, Channel.NEARBY, null);
        VoiceRoom elsewhere = new VoiceRoom(Scope.WORLD, otherWorld, null, Channel.NEARBY, null);
        check(normal.gainTo(normal, here, distant, 8, 29, false) == 0, "proximity cutoff");
        check(normal.gainTo(normal, here, distant, 8, 29, true) == 255, "opt-in world bypasses distance");
        check(normal.gainTo(elsewhere, here, distant, 8, 29, true) == 0, "opt-in never spans worlds");
        check(gain(arena, new VoiceRoom(Scope.MINIGAMES, world, null, Channel.NEARBY, null)) == 0,
                "round versus waiting");
        VoiceRoom team = new VoiceRoom(Scope.BEDWARS, world, "arena 1", Channel.TEAM, "blue");
        check(gain(team, team) == 255, "same team full volume");
        check(gain(team, new VoiceRoom(Scope.BEDWARS, world, "arena 1", Channel.TEAM, "red")) == 0, "enemy team");
        check(gain(team, new VoiceRoom(Scope.BEDWARS, world, "arena 2", Channel.TEAM, "blue")) == 0, "other arena");
        check(gain(team, new VoiceRoom(Scope.BEDWARS, otherWorld, "arena 1", Channel.TEAM, "blue")) == 0,
                "other world");
        VoiceRoom spectators = new VoiceRoom(Scope.BEDWARS, world, "arena 1", Channel.SPECTATOR, null);
        check(gain(team, spectators) == 0 && gain(spectators, team) == 0, "live and spectator isolation");
        check(gain(spectators, spectators) == 255, "same arena spectators");
    }

    private static void bedWarsState() {
        World world = fake(World.class, method -> null);
        UUID id = UUID.randomUUID();
        Player player = fake(Player.class, method -> {
            switch (method) {
                case "getWorld": return world;
                case "getUniqueId": return id;
                case "getGameMode": return GameMode.SURVIVAL;
                case "isDead": return false;
                default: throw new AssertionError(method);
            }
        });
        ITeam team = fake(ITeam.class, method -> null);
        check(GameRooms.bedWars(player, arena(GameState.playing, team, false, false), true).channel == Channel.TEAM,
                "active player");
        check(GameRooms.bedWars(player, arena(GameState.playing, team, true, false), true).channel == Channel.SPECTATOR,
                "eliminated player");
        check(GameRooms.bedWars(player, arena(GameState.playing, team, false, true), true) == null,
                "temporary respawn isolated from permanent spectators");
        check(GameRooms.bedWars(player, arena(GameState.playing, null, false, false), true) == null,
                "missing team fails closed");
        check(GameRooms.bedWars(player, arena(GameState.waiting, null, false, false), true).channel == Channel.NEARBY,
                "pregame proximity");
        check(GameRooms.bedWars(player, arena(GameState.waiting, null, false, false), false) == null,
                "pregame proximity disabled");
    }

    private static IArena arena(GameState state, ITeam team, boolean spectator, boolean respawning) {
        return fake(IArena.class, method -> {
            switch (method) {
                case "getStatus": return state;
                case "getTeam": return team;
                case "isSpectator": return spectator;
                case "isReSpawning": return respawning;
                case "isPlayer": return !spectator;
                default: throw new AssertionError(method);
            }
        });
    }

    private static int gain(VoiceRoom sender, VoiceRoom receiver) {
        return sender.gainTo(receiver, null, null, 8, 48, false);
    }

    private static <T> T fake(Class<T> type, Function<String, Object> method) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, call, args) -> {
            if (call.getName().equals("equals")) {
                return proxy == args[0];
            }
            return method.apply(call.getName());
        }));
    }

    private static void check(boolean condition, String name) {
        if (!condition) {
            throw new AssertionError(name);
        }
    }
}
