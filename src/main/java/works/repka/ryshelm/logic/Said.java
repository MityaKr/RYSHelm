package works.repka.ryshelm.logic;

import java.util.ArrayList;
import java.util.List;

/**
 * Текст записи журнала ключами (0.2.9): части {@code ryshelm.<k>} с аргументами, окно и команда собирают его на языке
 * читателя. На диск запись ложится как раньше русской строкой (поля {@code d}, {@code n}) и рядом частями ({@code dk},
 * {@code nk}); у старых записей частей нет, они показываются русской строкой.
 *
 * <p>Аргумент части: обычная строка (имя, текст модератора) или слово логики, переводимое при показе: {@link #term},
 * {@link #termOn}, {@link #key}, {@link #count}, {@link #kitRule}.
 */
public final class Said {
    /** Метка слова логики в аргументе: обычный текст её не содержит. */
    private static final char TAG = '\u0001';

    /** Часть: ключ без {@code ryshelm.} и аргументы; пустой ключ значит текст {@code a[0]} как есть. */
    public static final class Part {
        public String k = "";
        public List<String> a = List.of();

        Part() {}

        Part(String k, List<String> a) {
            this.k = k;
            this.a = a;
        }

        boolean literal() {
            return k == null || k.isEmpty();
        }

        String render(Lang lang) {
            List<String> args = a == null ? List.of() : a;
            if (literal()) return args.isEmpty() ? "" : args.get(0);
            Object[] out = new Object[args.size()];
            for (int i = 0; i < out.length; i++) out[i] = arg(lang, args.get(i));
            return lang.t("ryshelm." + k, out);
        }
    }

    public static final Said NONE = new Said(List.of());

    private final List<Part> parts;

    private Said(List<Part> parts) {
        this.parts = parts;
    }

    /** Часть по ключу {@code ryshelm.<key>}. */
    public static Said of(String key, Object... args) {
        List<String> a = new ArrayList<>(args.length);
        for (Object o : args) a.add(String.valueOf(o));
        return new Said(List.of(new Part(key, List.copyOf(a))));
    }

    /** Текст как есть: имя набора, заметка модератора, место {@code @dim x y z}. */
    public static Said lit(String text) {
        return text == null || text.isEmpty() ? NONE : new Said(List.of(new Part("", List.of(text))));
    }

    /** Этот текст, потом {@code next} без разделителя. */
    public Said then(Said next) {
        if (next.parts.isEmpty()) return this;
        if (parts.isEmpty()) return next;
        List<Part> all = new ArrayList<>(parts);
        all.addAll(next.parts);
        return new Said(List.copyOf(all));
    }

    /** Части через {@code sep} (литерал), пустые пропускаются. */
    public static Said join(String sep, List<Said> items) {
        Said out = NONE;
        for (Said s : items) {
            if (s.parts.isEmpty()) continue;
            out = out.parts.isEmpty() ? s : out.then(lit(sep)).then(s);
        }
        return out;
    }

    public boolean isEmpty() {
        return parts.isEmpty();
    }

    /** Строка на языке {@code lang}. */
    public String render(Lang lang) {
        return render(parts, lang);
    }

    /** Части для записи на диск: null, если текст целиком литеральный (он и так лежит строкой). */
    public List<Part> stored() {
        for (Part p : parts) if (!p.literal()) return List.copyOf(parts);
        return null;
    }

    public static String render(List<Part> parts, Lang lang) {
        StringBuilder b = new StringBuilder();
        for (Part p : parts) if (p != null) b.append(p.render(lang));
        return b.toString();
    }

    // ---- слова логики в аргументах ----

    /** Срок: «10 мин», «2 дня», «навсегда». */
    public static String term(long millis) {
        return TAG + "t" + millis;
    }

    /** Срок с предлогом: «на 1 ч», «навсегда». */
    public static String termOn(long millis) {
        return TAG + "o" + millis;
    }

    /** Строка ключа {@code ryshelm.<key>} без аргументов. */
    public static String key(String key) {
        return TAG + "k" + key;
    }

    /** Число со склонением по ключу {@code ryshelm.<key>.one/.few/.many}: «3 предмета». */
    public static String count(String key, long n) {
        return TAG + "n" + n + ":" + key;
    }

    /** Пауза набора: «один раз», «раз в 1 день». */
    public static String kitRule(int cooldownSeconds) {
        return TAG + "r" + cooldownSeconds;
    }

    static String arg(Lang lang, String a) {
        if (a == null || a.length() < 2 || a.charAt(0) != TAG) return a == null ? "" : a;
        String v = a.substring(2);
        try {
            return switch (a.charAt(1)) {
                case 't' -> lang.term(Long.parseLong(v));
                case 'o' -> lang.termOn(Long.parseLong(v));
                case 'k' -> lang.t("ryshelm." + v);
                case 'n' -> {
                    int c = v.indexOf(':');
                    yield lang.n("ryshelm." + v.substring(c + 1), Long.parseLong(v.substring(0, c)));
                }
                case 'r' -> lang.kitRule(Integer.parseInt(v));
                default -> v;
            };
        } catch (RuntimeException e) {
            return v;
        }
    }
}
