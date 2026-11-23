package works.repka.ryshelm.logic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * Лестница наказаний сервера (план 5.4, окно наказания): шаги по порядку, по ним подсказка «Следующий шаг по правилам
 * сервера: заглушить на 1 ч. Дальше по лестнице: бан на 1 день». Шаг выбирается числом наказаний игрока за окно
 * (по умолчанию 30 дней); снятые наказания не считаются, последний шаг повторяется. Наказание со сроком (заглушить, бан)
 * считается, только пока действует: истёкший бан в счёт не идёт (решение 19.11, макет 09). Предупреждение и «выгнать»
 * срока не имеют и считаются в окне.
 *
 * <p>Строка настроек: {@code warn, mute 1h, ban 1d, ban 7d, ban perm}.
 */
public final class Ladder {
    public static final String DEFAULT = "warn, mute 1h, ban 1d, ban 7d, ban perm";
    public static final int DEFAULT_WINDOW_DAYS = 30;

    /** Шаг лестницы: вид и срок (у предупреждения и «выгнать» срок 0). */
    public record Step(Kind kind, long millis) {
        /** «предупреждение», «заглушить на 1 ч», «бан на 1 день», «бан навсегда». */
        public String label() {
            return switch (kind) {
                case WARN -> "предупреждение";
                case KICK -> "выгнать";
                case MUTE -> "заглушить " + Term.onLabel(millis);
                case BAN -> "бан " + Term.onLabel(millis);
            };
        }

        public String code() {
            return kind.timed() ? kind.id() + " " + Term.code(millis) : kind.id();
        }
    }

    /** Прошлое наказание для счёта: вид, когда дано, снято ли, когда кончается (0 навсегда или без срока). */
    public record Past(Kind kind, long created, boolean revoked, long expires) {
        public Past(Kind kind, long created, boolean revoked) {
            this(kind, created, revoked, 0);
        }
    }

    /** Подсказка окна: сколько уже было, следующий шаг и шаг за ним. */
    public record Hint(int count, int warnings, long since, Step next, @Nullable Step after) {}

    private final List<Step> steps;
    private final long window;

    public Ladder(List<Step> steps, long windowMillis) {
        if (steps.isEmpty()) throw new IllegalArgumentException("пустая лестница");
        this.steps = List.copyOf(steps);
        this.window = windowMillis;
    }

    public static Ladder defaults() {
        return parse(DEFAULT, DEFAULT_WINDOW_DAYS);
    }

    /** Из строки настроек; непонятная строка даёт лестницу по умолчанию. */
    public static Ladder parse(String text, int windowDays) {
        long window = Math.max(1, windowDays) * Term.DAY;
        try {
            return new Ladder(steps(text), window);
        } catch (RuntimeException e) {
            return new Ladder(steps(DEFAULT), window);
        }
    }

    /** Шаги из строки {@code warn, mute 1h, ban 7d}; ошибка бросает IllegalArgumentException. */
    public static List<Step> steps(String text) {
        List<Step> out = new ArrayList<>();
        for (String part : text.split("[,;\\n]")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            String[] w = p.split("\\s+");
            Kind kind = Kind.parse(w[0]);
            if (kind == null) throw new IllegalArgumentException("вид: " + w[0]);
            long millis = 0;
            if (kind.timed()) {
                if (w.length < 2) throw new IllegalArgumentException("нет срока: " + p);
                millis = Term.parse(w[1]);
            }
            out.add(new Step(kind, millis));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("пустая лестница");
        return out;
    }

    public List<Step> steps() {
        return steps;
    }

    public long window() {
        return window;
    }

    public String text() {
        StringBuilder b = new StringBuilder();
        for (Step s : steps) {
            if (!b.isEmpty()) b.append(", ");
            b.append(s.code());
        }
        return b.toString();
    }

    /** Считается ли наказание: не снято; со сроком пока действует, без срока (предупреждение, «выгнать») в окне. */
    private boolean counts(Past p, long now) {
        if (p.revoked) return false;
        if (p.kind.timed()) return p.expires == 0 || p.expires > now;
        return now - p.created <= window;
    }

    private int counted(Collection<Past> history, long now) {
        int n = 0;
        for (Past p : history) if (counts(p, now)) n++;
        return n;
    }

    /** Следующий шаг для игрока с такой историей. */
    public Hint next(Collection<Past> history, long now) {
        int count = counted(history, now);
        int warnings = 0;
        long since = now;
        for (Past p : history) {
            if (!counts(p, now)) continue;
            if (p.kind == Kind.WARN) warnings++;
            since = Math.min(since, p.created);
        }
        int i = Math.min(count, steps.size() - 1);
        Step next = steps.get(i);
        Step after = i + 1 < steps.size() ? steps.get(i + 1) : null;
        return new Hint(count, warnings, since, next, after);
    }

    /**
     * Текст подсказки макета 09: «У Grib_ok 1 предупреждение за 3 дня. Следующий шаг по правилам сервера: заглушить на 1 ч.
     * Дальше по лестнице: бан на 1 день.» Без прошлых наказаний: «Наказаний за 30 дней нет. Первый шаг по правилам сервера:
     * предупреждение.»
     */
    public String describe(String name, Collection<Past> history, long now) {
        return describe(Lang.ru(), name, history, now);
    }

    /** То же на языке {@code lang}. */
    public String describe(Lang lang, String name, Collection<Past> history, long now) {
        Hint h = next(history, now);
        StringBuilder b = new StringBuilder();
        if (h.count() == 0) {
            b.append(lang.t("ryshelm.ladder.none", lang.term(window), lang.step(h.next())));
        } else {
            long days = Math.max(1, (now - h.since() + Term.DAY - 1) / Term.DAY);
            b.append(lang.t("ryshelm.ladder.had", name, summary(lang, h), lang.n("ryshelm.term.days", days), lang.step(h.next())));
        }
        if (h.after() != null) b.append(' ').append(lang.t("ryshelm.ladder.after", lang.step(h.after())));
        return b.toString();
    }

    /** «1 предупреждение», «2 наказания», «1 предупреждение и 1 наказание». */
    static String summary(Lang lang, Hint h) {
        int other = h.count() - h.warnings();
        String w = h.warnings() > 0 ? lang.n("ryshelm.ladder.warnings", h.warnings()) : "";
        String o = other > 0 ? lang.n("ryshelm.ladder.punishments", other) : "";
        if (w.isEmpty()) return o;
        if (o.isEmpty()) return w;
        return lang.t("ryshelm.ladder.and", w, o);
    }

    @Override
    public String toString() {
        return text().toLowerCase(Locale.ROOT) + " / " + window / Term.DAY + "d";
    }
}
