package works.repka.ryshelm.logic;

import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/** Вид наказания (план 5.4). Заморозка не наказание, а действие модератора: она живёт, пока он в сети, и пишется в журнал. */
public enum Kind {
    WARN("warn", false),
    MUTE("mute", true),
    KICK("kick", false),
    BAN("ban", true);

    private final String id;
    private final boolean timed;

    Kind(String id, boolean timed) {
        this.id = id;
        this.timed = timed;
    }

    public String id() {
        return id;
    }

    /** Есть ли у наказания срок. */
    public boolean timed() {
        return timed;
    }

    public static @Nullable Kind parse(@Nullable String s) {
        if (s == null) return null;
        String k = s.trim().toLowerCase(Locale.ROOT);
        for (Kind kind : values()) if (kind.id.equals(k)) return kind;
        return switch (k) {
            case "warning", "предупреждение", "предупредить" -> WARN;
            case "заглушить", "мут" -> MUTE;
            case "выгнать" -> KICK;
            case "бан", "tempban" -> BAN;
            default -> null;
        };
    }

    /** «предупреждение», «заглушить», «выгнать», «бан». */
    public String noun() {
        return switch (this) {
            case WARN -> "предупреждение";
            case MUTE -> "заглушить";
            case KICK -> "выгнать";
            case BAN -> "бан";
        };
    }
}
