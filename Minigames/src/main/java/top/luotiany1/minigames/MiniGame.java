package top.luotiany1.minigames;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

interface MiniGame {
    final class Result {
        final RoundResultEvent.Outcome outcome;
        final UUID winner;
        final String winningTeam;
        final String message;

        Result(RoundResultEvent.Outcome outcome, UUID winner, String winningTeam, String message) {
            this.outcome = outcome;
            this.winner = winner;
            this.winningTeam = winningTeam;
            this.message = message;
        }
    }

    void start(List<Player> players);

    boolean isEliminated(Player player);

    default void onEliminated(Player player) { }

    default Player attack(Player attacker, Player target, EntityDamageByEntityEvent event) {
        return null;
    }

    Result result(Collection<Player> alive, boolean timeout);

    void tick(long tick, Collection<Player> alive);

    void reset();
}
