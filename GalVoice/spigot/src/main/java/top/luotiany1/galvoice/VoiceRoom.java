package top.luotiany1.galvoice;

import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.World;
import top.luotiany1.galvoice.VoicePacket.Channel;

final class VoiceRoom {
    enum Scope { WORLD, BEDWARS, MINIGAMES }

    final Scope scope;
    final World world;
    final Object round;
    final Channel channel;
    final Object team;

    VoiceRoom(Scope scope, World world, Object round, Channel channel, Object team) {
        this.scope = scope;
        this.world = world;
        this.round = round;
        this.channel = channel;
        this.team = team;
    }

    int gainTo(VoiceRoom other, Location from, Location to, double fadeStart, double range, boolean worldListening) {
        if (other == null || scope != other.scope || world != other.world || channel != other.channel
                || !Objects.equals(round, other.round) || !Objects.equals(team, other.team)) {
            return 0;
        }
        if (channel != Channel.NEARBY || (worldListening && scope == Scope.WORLD)) {
            return 255;
        }
        double distanceSquared = from.distanceSquared(to);
        if (distanceSquared > range * range) {
            return 0;
        }
        if (distanceSquared <= fadeStart * fadeStart || fadeStart == range) {
            return 255;
        }
        return Math.max(1, (int) Math.round(255 * (range - Math.sqrt(distanceSquared)) / (range - fadeStart)));
    }
}
