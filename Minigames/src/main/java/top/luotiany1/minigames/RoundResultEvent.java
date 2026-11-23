package top.luotiany1.minigames;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class RoundResultEvent extends Event {
    public enum Outcome { WINNER, DRAW, TIME_LIMIT, PRACTICE }

    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID roundId;
    private final String gameId;
    private final LocalDate rotationDate;
    private final Map<UUID, String> participants;
    private final UUID winner;
    private final Outcome outcome;
    private final String roomId;
    private final String winningTeam;

    RoundResultEvent(UUID roundId, String gameId, LocalDate rotationDate, Map<UUID, String> participants,
                     UUID winner, Outcome outcome, String roomId, String winningTeam) {
        this.roundId = roundId;
        this.gameId = gameId;
        this.rotationDate = rotationDate;
        this.participants = Collections.unmodifiableMap(new LinkedHashMap<>(participants));
        this.winner = winner;
        this.outcome = outcome;
        this.roomId = roomId;
        this.winningTeam = winningTeam;
    }

    public UUID getRoundId() {
        return roundId;
    }

    public String getGameId() {
        return gameId;
    }

    public LocalDate getRotationDate() {
        return rotationDate;
    }

    public Map<UUID, String> getParticipants() {
        return participants;
    }

    public UUID getWinner() {
        return winner;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getRoomId() {
        return roomId;
    }

    public String getWinningTeam() {
        return winningTeam;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
