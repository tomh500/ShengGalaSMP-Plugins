package top.luotiany1.minigames;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

final class DailyRotation {
    private final ZoneId timezone;
    private final LocalDate start;
    private final List<GameType> order = new ArrayList<>();
    private final List<Window> windows = new ArrayList<>();

    DailyRotation(ConfigurationSection config) {
        timezone = ZoneId.of(config.getString("rotation.timezone"));
        start = LocalDate.parse(config.getString("rotation.start-date"));
        for (String id : config.getStringList("rotation.order")) order.add(GameType.fromId(id));
        if (order.isEmpty()) throw new IllegalArgumentException("rotation.order 至少需要一个玩法");
        for (Map<?, ?> entry : config.getMapList("rotation.windows")) windows.add(new Window(entry));
    }

    ZonedDateTime now() {
        return ZonedDateTime.now(timezone);
    }

    List<GameType> gamesAt(ZonedDateTime now) {
        ZonedDateTime local = now.withZoneSameInstant(timezone);
        for (Window window : windows) {
            if (window.matches(local.toLocalDate(), local.toLocalTime())) return window.types;
        }
        long days = ChronoUnit.DAYS.between(start, local.toLocalDate());
        return Collections.singletonList(order.get((int) Math.floorMod(days, (long) order.size())));
    }

    private static final class Window {
        final List<GameType> types;
        final LocalTime from;
        final LocalTime until;
        final LocalDate firstDate;
        final LocalDate lastDate;
        final Set<DayOfWeek> days;

        Window(Map<?, ?> entry) {
            Object games = entry.get("games");
            if (games != null && !(games instanceof List)) {
                throw new IllegalArgumentException("rotation.windows.games 必须是玩法列表");
            }
            types = games == null ? Collections.singletonList(GameType.fromId(value(entry, "game", "")))
                    : GameType.selection((List<?>) games);
            from = LocalTime.parse(value(entry, "from", "00:00"));
            until = LocalTime.parse(value(entry, "until", "00:00"));
            firstDate = date(entry, "date-from");
            lastDate = date(entry, "date-until");
            if (firstDate != null && lastDate != null && lastDate.isBefore(firstDate)) {
                throw new IllegalArgumentException("rotation.windows 的 date-until 不能早于 date-from");
            }
            Object weekdays = entry.get("days");
            days = EnumSet.noneOf(DayOfWeek.class);
            if (weekdays == null) {
                days.addAll(EnumSet.allOf(DayOfWeek.class));
            } else if (weekdays instanceof List) {
                for (Object day : (List<?>) weekdays) {
                    days.add(DayOfWeek.valueOf(String.valueOf(day).toUpperCase(Locale.ROOT)));
                }
            }
            if (days.isEmpty()) throw new IllegalArgumentException("rotation.windows.days 至少要有一个有效星期");
        }

        boolean matches(LocalDate date, LocalTime time) {
            if (from.isBefore(until)) {
                if (time.isBefore(from) || !time.isBefore(until)) return false;
            } else if (time.isBefore(from)) {
                if (!from.equals(until) && !time.isBefore(until)) return false;
                date = date.minusDays(1);
            }
            return days.contains(date.getDayOfWeek())
                    && (firstDate == null || !date.isBefore(firstDate))
                    && (lastDate == null || !date.isAfter(lastDate));
        }

        private static LocalDate date(Map<?, ?> entry, String key) {
            String value = value(entry, key, "");
            return value.isEmpty() ? null : LocalDate.parse(value);
        }

        private static String value(Map<?, ?> entry, String key, String fallback) {
            Object value = entry.get(key);
            return value == null ? fallback : String.valueOf(value);
        }
    }
}
