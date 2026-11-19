package works.repka.ryshelm.logic;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Антиспам чата без игры (план 9): не больше {@code messages} строк за {@code seconds} секунд, одна и та же строка не чаще
 * трёх раз за 30 секунд. Три отказа за 2 минуты дают «удар»: сервер может заглушить игрока на несколько минут.
 */
public final class SpamGuard {
    public enum Verdict { OK, TOO_FAST, REPEAT, STRIKE }

    private record Line(long time, String text) {}

    static final int REPEATS = 3;
    static final long REPEAT_WINDOW = 30_000, STRIKE_WINDOW = 120_000;
    static final int STRIKES = 3;

    private final Map<UUID, Deque<Line>> lines = new HashMap<>();
    private final Map<UUID, Deque<Long>> denials = new HashMap<>();

    /** Проверить строку и запомнить её, если прошла. */
    public Verdict check(UUID player, String text, long now, int messages, int seconds) {
        Deque<Line> d = lines.computeIfAbsent(player, k -> new ArrayDeque<>());
        while (!d.isEmpty() && now - d.peekFirst().time > Math.max(seconds * 1000L, REPEAT_WINDOW)) d.removeFirst();
        String norm = text.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        int recent = 0, same = 0;
        for (Line l : d) {
            if (now - l.time <= seconds * 1000L) recent++;
            if (now - l.time <= REPEAT_WINDOW && l.text.equals(norm)) same++;
        }
        Verdict v = recent >= messages ? Verdict.TOO_FAST : same >= REPEATS - 1 ? Verdict.REPEAT : Verdict.OK;
        if (v == Verdict.OK) {
            d.addLast(new Line(now, norm));
            return v;
        }
        Deque<Long> den = denials.computeIfAbsent(player, k -> new ArrayDeque<>());
        while (!den.isEmpty() && now - den.peekFirst() > STRIKE_WINDOW) den.removeFirst();
        den.addLast(now);
        if (den.size() >= STRIKES) {
            den.clear();
            return Verdict.STRIKE;
        }
        return v;
    }

    public void forget(UUID player) {
        lines.remove(player);
        denials.remove(player);
    }

    public void clear() {
        lines.clear();
        denials.clear();
    }
}
