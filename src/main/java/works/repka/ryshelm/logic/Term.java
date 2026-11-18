package works.repka.ryshelm.logic;

import java.util.Locale;

/**
 * Сроки наказаний без игры: разбор {@code 10m}, {@code 1h}, {@code 1d12h}, {@code 7d}, {@code perm} и подпись по-русски.
 * Навсегда это {@link #FOREVER}.
 */
public final class Term {
    public static final long SECOND = 1000L, MINUTE = 60 * SECOND, HOUR = 60 * MINUTE, DAY = 24 * HOUR, WEEK = 7 * DAY;
    /** Срок «навсегда». В записи наказания это {@code expires = 0}. */
    public static final long FOREVER = Long.MAX_VALUE;

    /** Кнопки окна наказания (план 5.4): 10 мин, 1 ч, 1 день, 7 дней. */
    public static final long[] PRESETS = {10 * MINUTE, HOUR, DAY, 7 * DAY};

    private Term() {}

    /**
     * Срок из строки: число с единицей ({@code s, m, h, d, w}, по-русски {@code с, м, ч, д, н}), части складываются
     * ({@code 1d12h}); {@code perm}, {@code forever}, {@code навсегда} дают {@link #FOREVER}.
     * @throws IllegalArgumentException пустой, нулевой или непонятный срок
     */
    public static long parse(String text) {
        String s = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) throw new IllegalArgumentException("пустой срок");
        if (s.equals("perm") || s.equals("permanent") || s.equals("forever") || s.equals("навсегда") || s.equals("inf")) return FOREVER;
        long total = 0;
        int i = 0;
        while (i < s.length()) {
            int start = i;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (start == i) throw new IllegalArgumentException("нет числа: " + text);
            long n = Long.parseLong(s.substring(start, i));
            if (i >= s.length()) throw new IllegalArgumentException("нет единицы: " + text);
            long unit = switch (s.charAt(i)) {
                case 's', 'с' -> SECOND;
                case 'm', 'м' -> MINUTE;
                case 'h', 'ч' -> HOUR;
                case 'd', 'д' -> DAY;
                case 'w', 'н' -> WEEK;
                default -> throw new IllegalArgumentException("единица: " + s.charAt(i));
            };
            i++;
            // «мин», «ч», «дн» и прочие хвосты одной единицы пропускаем
            while (i < s.length() && Character.isLetter(s.charAt(i))) i++;
            total = Math.addExact(total, Math.multiplyExact(n, unit));
        }
        if (total <= 0) throw new IllegalArgumentException("нулевой срок");
        return total;
    }

    /** Безопасный разбор: непонятное даёт {@code fallback}. */
    public static long parse(String text, long fallback) {
        try {
            return parse(text);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Короткая запись для файлов и команд: {@code 1d}, {@code 90m}, {@code perm}. */
    public static String code(long millis) {
        if (millis == FOREVER) return "perm";
        if (millis % DAY == 0) return millis / DAY + "d";
        if (millis % HOUR == 0) return millis / HOUR + "h";
        if (millis % MINUTE == 0) return millis / MINUTE + "m";
        return Math.max(1, millis / SECOND) + "s";
    }

    /** Подпись срока: «10 мин», «1 ч», «1 день», «2 дня», «7 дней», «навсегда». Неровный срок округляется к крупной единице. */
    public static String label(long millis) {
        if (millis == FOREVER) return "навсегда";
        if (millis >= DAY && millis % DAY == 0) return plural(millis / DAY, "день", "дня", "дней");
        if (millis >= DAY) return plural(Math.round(millis / (double) DAY), "день", "дня", "дней");
        if (millis >= HOUR && millis % HOUR == 0) return millis / HOUR + " ч";
        if (millis >= HOUR) {
            long h = millis / HOUR, m = (millis % HOUR) / MINUTE;
            return m == 0 ? h + " ч" : h + " ч " + m + " мин";
        }
        if (millis >= MINUTE) return millis / MINUTE + " мин";
        return Math.max(1, millis / SECOND) + " с";
    }

    /** «на 1 ч», «навсегда»: для строк журнала и кнопки «Заглушить на 1 ч». */
    public static String onLabel(long millis) {
        return millis == FOREVER ? "навсегда" : "на " + label(millis);
    }

    /** «осталось 0:41», «осталось 3 ч», «осталось 2 дня». */
    public static String left(long millis) {
        if (millis <= 0) return "0 с";
        if (millis < HOUR) {
            long s = millis / SECOND;
            return s / 60 + ":" + String.format(Locale.ROOT, "%02d", s % 60);
        }
        return label(millis < DAY ? (millis / HOUR) * HOUR : (millis / DAY) * DAY);
    }

    /** «1 день», «2 дня», «5 дней» и так же с любыми формами. */
    public static String plural(long n, String one, String few, String many) {
        return n + " " + form(n, one, few, many);
    }

    public static String form(long n, String one, String few, String many) {
        long a = Math.abs(n) % 100, b = a % 10;
        if (a >= 11 && a <= 14) return many;
        if (b == 1) return one;
        if (b >= 2 && b <= 4) return few;
        return many;
    }
}
