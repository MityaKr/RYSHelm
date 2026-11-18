package works.repka.ryshelm.logic;

import org.jetbrains.annotations.Nullable;

/**
 * Потолки сроков по роли (план 5.4, решение вопроса 7): модератор глушит до 1 дня, банит до 7 дней, навсегда только
 * оператор. Числа правит оператор во вкладке «Роли».
 */
public final class Caps {
    public static final long DEFAULT_MUTE = Term.DAY, DEFAULT_BAN = 7 * Term.DAY;

    private final long muteMax, banMax;

    public Caps(long muteMax, long banMax) {
        this.muteMax = muteMax;
        this.banMax = banMax;
    }

    public static Caps defaults() {
        return new Caps(DEFAULT_MUTE, DEFAULT_BAN);
    }

    public long muteMax() {
        return muteMax;
    }

    public long banMax() {
        return banMax;
    }

    /**
     * Можно ли выдать такое наказание.
     * @param operator у выдающего роль оператора (потолков нет)
     * @param permanentBan есть ли у выдающего право «бан навсегда» (узел {@code ryshelm.ban})
     * @return null, если можно, иначе причина для игрока
     */
    public @Nullable String check(boolean operator, boolean permanentBan, Kind kind, long millis) {
        if (!kind.timed() || operator) return null;
        if (millis <= 0) return "Нужен срок";
        if (kind == Kind.BAN) {
            if (millis == Term.FOREVER) return permanentBan ? null : "Бан навсегда даёт только оператор";
            if (permanentBan) return null;
            return millis > banMax ? "Модератору бан до " + Term.label(banMax) : null;
        }
        if (millis == Term.FOREVER || millis > muteMax) return "Модератору заглушить до " + Term.label(muteMax);
        return null;
    }

    /** Самый длинный срок, который можно дать: для подписи «модератору до 1 дня». */
    public long max(boolean operator, boolean permanentBan, Kind kind) {
        if (operator) return Term.FOREVER;
        if (kind == Kind.BAN) return permanentBan ? Term.FOREVER : banMax;
        return muteMax;
    }
}
