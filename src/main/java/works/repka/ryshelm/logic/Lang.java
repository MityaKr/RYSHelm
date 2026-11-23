package works.repka.ryshelm.logic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Тексты мода по ключам {@code ryshelm.*} из {@code assets/ryshelm/lang/*.json}. На клиенте ключи читает язык игры, на
 * сервере (где переводов игры нет) файлы мода берутся из его jar: так сервер пишет игроку на его языке, а консоли
 * по-английски. Склонения как в семье: ключи {@code .one}, {@code .few}, {@code .many} (по-английски {@code .few} не
 * нужен). Шаблоны с {@code %s} и {@code %1$s}, как в файлах игры.
 */
public final class Lang {
    private static volatile Lang ru, en;

    private final boolean russian;
    private final Function<String, String> raw;

    public Lang(boolean russian, Function<String, String> raw) {
        this.russian = russian;
        this.raw = raw;
    }

    /** Русский из {@code ru_ru.json}. */
    public static Lang ru() {
        Lang l = ru;
        if (l == null) ru = l = load("ru_ru", true);
        return l;
    }

    /** Английский из {@code en_us.json}. */
    public static Lang en() {
        Lang l = en;
        if (l == null) en = l = load("en_us", false);
        return l;
    }

    /** По коду языка игры: {@code ru_ru} и прочие {@code ru*} русский, остальное английский. */
    public static Lang of(String code) {
        return code != null && code.toLowerCase(Locale.ROOT).startsWith("ru") ? ru() : en();
    }

    /**
     * Язык системы сервера (локаль JVM): для текстов без игрока, которые остаются в файлах мира и config (правила по
     * умолчанию, отчёт шага переезда). Русская система даёт русский, любая другая английский.
     */
    public static Lang server() {
        return of(Locale.getDefault().getLanguage());
    }

    private static Lang load(String code, boolean russian) {
        Map<String, String> map = new HashMap<>();
        try (InputStream in = Lang.class.getResourceAsStream("/assets/ryshelm/lang/" + code + ".json")) {
            if (in != null) {
                JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                for (var e : o.entrySet()) map.put(e.getKey(), e.getValue().getAsString());
            }
        } catch (Exception ignored) {
            // без файла ключи показываются как есть
        }
        return new Lang(russian, k -> map.getOrDefault(k, k));
    }

    public boolean russian() {
        return russian;
    }

    /** Шаблон без подстановки, ключ если строки нет. */
    public String raw(String key) {
        return raw.apply(key);
    }

    /** Текст с подстановкой. */
    public String t(String key, Object... args) {
        return format(raw(key), args);
    }

    /** Ключ формы для числа: {@code key.one}, {@code key.few}, {@code key.many}. */
    public String formKey(String key, long n) {
        return key + "." + form(russian, n);
    }

    /** «3 дня»: шаблон формы, первый аргумент число. Остальные аргументы идут после него. */
    public String n(String key, long n, Object... more) {
        Object[] args = new Object[more.length + 1];
        args[0] = n;
        System.arraycopy(more, 0, args, 1, more.length);
        return t(formKey(key, n), args);
    }

    /** Форма числа: {@code one}, {@code few}, {@code many}. */
    public static String form(boolean russian, long n) {
        long a = Math.abs(n);
        if (!russian) return a == 1 ? "one" : "many";
        long tens = a % 100, units = a % 10;
        if (tens >= 11 && tens <= 14) return "many";
        if (units == 1) return "one";
        if (units >= 2 && units <= 4) return "few";
        return "many";
    }

    public static String format(String template, Object... args) {
        if (args.length == 0 && template.indexOf('%') < 0) return template;
        try {
            return String.format(Locale.ROOT, template, args);
        } catch (IllegalFormatException e) {
            return template;
        }
    }

    // ---- слова логики ----

    /** Подпись срока: «10 мин», «1 ч», «2 дня», «навсегда». */
    public String term(long millis) {
        if (millis == Term.FOREVER) return t("ryshelm.term.forever");
        if (millis >= Term.DAY && millis % Term.DAY == 0) return n("ryshelm.term.days", millis / Term.DAY);
        if (millis >= Term.DAY) return n("ryshelm.term.days", Math.round(millis / (double) Term.DAY));
        if (millis >= Term.HOUR && millis % Term.HOUR == 0) return t("ryshelm.term.hours", millis / Term.HOUR);
        if (millis >= Term.HOUR) {
            long h = millis / Term.HOUR, m = (millis % Term.HOUR) / Term.MINUTE;
            return m == 0 ? t("ryshelm.term.hours", h) : t("ryshelm.term.hours_minutes", h, m);
        }
        if (millis >= Term.MINUTE) return t("ryshelm.term.minutes", millis / Term.MINUTE);
        return t("ryshelm.term.seconds", Math.max(1, millis / Term.SECOND));
    }

    /** «на 1 ч», «навсегда». */
    public String termOn(long millis) {
        return millis == Term.FOREVER ? t("ryshelm.term.forever") : t("ryshelm.term.on", term(millis));
    }

    /** «0:41», «3 ч», «2 дня». */
    public String termLeft(long millis) {
        if (millis <= 0) return t("ryshelm.term.seconds", 0);
        if (millis < Term.HOUR) {
            long s = millis / Term.SECOND;
            return s / 60 + ":" + String.format(Locale.ROOT, "%02d", s % 60);
        }
        return term(millis < Term.DAY ? (millis / Term.HOUR) * Term.HOUR : (millis / Term.DAY) * Term.DAY);
    }

    /** «предупреждение», «заглушить», «выгнать», «бан». */
    public String kind(Kind kind) {
        return t("ryshelm.kind." + kind.id());
    }

    /** «предупреждение», «заглушить на 1 ч». */
    public String step(Ladder.Step step) {
        return switch (step.kind()) {
            case WARN, KICK -> kind(step.kind());
            case MUTE, BAN -> t("ryshelm.step." + step.kind().id(), termOn(step.millis()));
        };
    }

    /** «один раз», «без паузы», «раз в 1 день». */
    public String kitRule(int cooldownSeconds) {
        if (cooldownSeconds == KitRule.ONCE) return t("ryshelm.kit.rule.once");
        if (cooldownSeconds <= 0) return t("ryshelm.kit.rule.none");
        return t("ryshelm.kit.rule.every", term(cooldownSeconds * 1000L));
    }
}
