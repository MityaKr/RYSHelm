package works.repka.ryshelm.data;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import works.repka.ryshelm.logic.Lang;

/**
 * Строка записи журнала на языке читателя (0.2.12): одна для окна «Сервер» (макет 12) и для {@code /rys admin journal}.
 * Записи на диске по-русски и не меняются: известные слова поля {@code d} переводятся здесь. Тип записи без перевода
 * (запись будущей версии или чужая) показывается словами, а не ключом с подчёркиваниями.
 */
public final class JournalLine {
    private static final Pattern SLOT = Pattern.compile("%(\\d)\\$s");

    private JournalLine() {}

    /** Ключ глагола записи: {@code ryshelm.journal.verb.<тип>}. */
    public static String verbKey(String type) {
        return "ryshelm.journal.verb." + type;
    }

    /** Глагол записи («заглушил», «собрал набор»); у неизвестного типа его слова без подчёркиваний. */
    public static String verb(Lang lang, String type) {
        String key = verbKey(type);
        String s = lang.raw(key);
        return s == null || s.isEmpty() || s.equals(key) ? type.replace('_', ' ').strip() : s;
    }

    /**
     * Строка частями: {@code {текст, "b"}} жирным, {@code {текст, ""}} обычным. {@code a} кто, {@code p} кого,
     * {@code d} продолжение строки уже на языке читателя (у старых записей русская строка с диска).
     */
    public static List<String[]> parts(Lang lang, String type, String a, String p, String d) {
        List<String[]> out = new ArrayList<>();
        switch (type) {
            case "report" -> segs(out, tr(lang, "journal.line.report"), a, p, d, false);
            case "join_banned" -> segs(out, tr(lang, "journal.line.join_banned"), a, p, d, false);
            case "fly", "god" -> {
                String verb = tr(lang, "journal.toggle." + (d.equals(Journal.D_ON) ? "on" : d.equals(Journal.D_OFF) ? "off" : "switch"));
                String what = tr(lang, "journal.toggle." + type);
                segs(out, Lang.format(tr(lang, p.isEmpty() ? "journal.line.toggle.self" : "journal.line.toggle.other"), "%1$s", "%2$s", verb, what), a, p, d, false);
            }
            case "kit_create", "kit_delete" -> segs(out, tr(lang, "journal.line." + type), a, p, d, true);
            case "kit_give" -> segs(out, tr(lang, "journal.line.kit_give"), a, p, d, false);
            case "whitelist" -> {
                String k = switch (d) {
                    case Journal.WL_ON -> "on";
                    case Journal.WL_OFF -> "off";
                    case Journal.WL_ADD -> "add";
                    case Journal.WL_REMOVE -> "remove";
                    default -> "other";
                };
                segs(out, tr(lang, "journal.line.whitelist." + k), a, p, d, false);
            }
            case "settings", "perm", "caps", "afk_kick" -> {
                out.add(new String[] {a, "b"});
                out.add(new String[] {" " + verb(lang, type) + (d.isEmpty() ? "" : " " + d), ""});
            }
            default -> {
                out.add(new String[] {a, "b"});
                out.add(new String[] {" " + verb(lang, type) + (p.isEmpty() ? "" : " "), ""});
                if (!p.isEmpty()) out.add(new String[] {p, "b"});
                if (!d.isEmpty()) out.add(new String[] {" " + d, ""});
            }
        }
        return out;
    }

    /** Строка одним текстом, для чата и консоли. */
    public static String text(Lang lang, String type, String a, String p, String d) {
        StringBuilder b = new StringBuilder();
        for (String[] seg : parts(lang, type, a, p, d)) b.append(seg[0]);
        return b.toString().replaceAll("\\s{2,}", " ").strip();
    }

    /**
     * Строка по шаблону: {@code %1$s} кто (жирным), {@code %2$s} кого (жирным), {@code %3$s} продолжение (жирным, если
     * {@code boldDetail}).
     */
    static void segs(List<String[]> out, String template, String a, String p, String d, boolean boldDetail) {
        Matcher m = SLOT.matcher(template);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) out.add(new String[] {template.substring(last, m.start()), ""});
            switch (m.group(1)) {
                case "1" -> out.add(new String[] {a, "b"});
                case "2" -> out.add(new String[] {p, "b"});
                default -> out.add(new String[] {d, boldDetail ? "b" : ""});
            }
            last = m.end();
        }
        if (last < template.length()) out.add(new String[] {template.substring(last), ""});
    }

    private static String tr(Lang lang, String key) {
        return lang.t("ryshelm." + key);
    }
}
