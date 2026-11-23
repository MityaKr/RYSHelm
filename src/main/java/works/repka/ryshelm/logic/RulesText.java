package works.repka.ryshelm.logic;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Правила сервера из {@code config/ryshelm/rules.md} (план 5.7): нумерованные пункты, под пунктом строки пояснения.
 * Номера пунктов те же, что в жалобах и наказаниях. Версия правил это сумма текста: правка текста просит всех принять
 * правила заново.
 *
 * <pre>
 * # Правила сервера
 * 1. Не ломать и не брать чужое
 *    Чужая земля закрыта приватом, но и вне привата чужое не трогаем.
 * 2. Без читов и X-ray
 * </pre>
 */
public final class RulesText {
    private static final Pattern ITEM = Pattern.compile("^\\s*(\\d{1,3})[.)]\\s+(.+)$");

    public record Rule(int number, String title, String detail) {}

    private final String title;
    private final List<Rule> rules;
    private final String version;

    private RulesText(String title, List<Rule> rules, String version) {
        this.title = title;
        this.rules = List.copyOf(rules);
        this.version = version;
    }

    public static RulesText parse(String text) {
        String title = "";
        List<Rule> rules = new ArrayList<>();
        int number = -1;
        String head = null;
        StringBuilder detail = new StringBuilder();
        for (String raw : text.replace("\r", "").split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                if (title.isEmpty()) title = line.replaceFirst("^#+\\s*", "");
                continue;
            }
            Matcher m = ITEM.matcher(line);
            if (m.matches()) {
                if (head != null) rules.add(new Rule(number, head, detail.toString()));
                number = Integer.parseInt(m.group(1));
                head = m.group(2).strip();
                detail.setLength(0);
            } else if (head != null) {
                if (!detail.isEmpty()) detail.append(' ');
                detail.append(line.replaceFirst("^[-*]\\s+", ""));
            }
        }
        if (head != null) rules.add(new Rule(number, head, detail.toString()));
        return new RulesText(title, rules, version(text));
    }

    /** Версия: CRC32 текста без пробелов по краям строк, чтобы перевод строк Windows не менял её. */
    public static String version(String text) {
        StringBuilder b = new StringBuilder();
        for (String line : text.replace("\r", "").split("\n")) {
            String s = line.strip();
            if (!s.isEmpty()) b.append(s).append('\n');
        }
        CRC32 crc = new CRC32();
        crc.update(b.toString().getBytes(StandardCharsets.UTF_8));
        return Long.toHexString(crc.getValue());
    }

    public String title() {
        return title;
    }

    public List<Rule> rules() {
        return rules;
    }

    public String version() {
        return version;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** Пункт по номеру или null. */
    public Rule rule(int number) {
        for (Rule r : rules) if (r.number == number) return r;
        return null;
    }

    /** «п. 3 · Не оскорблять игроков» или пустая строка. */
    public String ref(int number) {
        Rule r = rule(number);
        return r == null ? "" : "п. " + number + " «" + r.title + "»";
    }

    /** То же на языке {@code lang}: «п. 3 «…»», «rule 3 "…"». */
    public String ref(Lang lang, int number) {
        Rule r = rule(number);
        return r == null ? "" : lang.t("ryshelm.ui.report.rule_ref", number, r.title);
    }

    /** Правила RepYS по умолчанию (макет 13): кладутся в config при первом запуске. */
    public static final String DEFAULT = """
        # Правила сервера
        1. Не ломать и не брать чужое
           Чужая земля закрыта приватом, но и вне привата чужое не трогаем.
        2. Без читов и X-ray
           Помощники из сборки можно: карта, рецепты, линза.
        3. Не оскорблять игроков
           Мат в общем чате: заглушение.
        4. Не строить машины, которые валят сервер
           Большие фермы согласуйте с модераторами.
        5. Не мешать новичкам
           Не звать к себе незнакомых новичков, не забирать стартовые вещи.
        6. Споры решают модераторы
           Жалоба: клик по игроку на карте или вкладка «Игроки».
        """;
}
