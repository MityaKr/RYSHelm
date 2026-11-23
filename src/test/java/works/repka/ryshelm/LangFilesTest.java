package works.repka.ryshelm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import works.repka.ryshelm.logic.Kind;
import works.repka.ryshelm.logic.Ladder;
import works.repka.ryshelm.logic.Lang;
import works.repka.ryshelm.logic.Term;

/**
 * Двуязычие (0.2.8): файлы перевода полные и одинаковые по ключам, в коде окна нет зашитого русского, каждый ключ из
 * кода есть в файлах, русские слова логики совпадают с прежними слово в слово.
 */
class LangFilesTest {
    private static final Path ROOT = Path.of(System.getProperty("ryshelm.root", "."));
    private static final Path LANG = ROOT.resolve("src/main/resources/assets/ryshelm/lang");
    private static final Path MAIN = ROOT.resolve("src/main/java/works/repka/ryshelm");

    private static Map<String, String> load(String code) throws IOException {
        JsonObject o = JsonParser.parseString(Files.readString(LANG.resolve(code + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, String> out = new TreeMap<>();
        for (var e : o.entrySet()) out.put(e.getKey(), e.getValue().getAsString());
        return out;
    }

    @Test
    void sameKeys() throws IOException {
        var ru = load("ru_ru");
        var en = load("en_us");
        var onlyRu = new TreeSet<>(ru.keySet());
        onlyRu.removeAll(en.keySet());
        var onlyEn = new TreeSet<>(en.keySet());
        onlyEn.removeAll(ru.keySet());
        assertTrue(onlyRu.isEmpty(), "нет в en_us.json: " + onlyRu);
        assertTrue(onlyEn.isEmpty(), "нет в ru_ru.json: " + onlyEn);
        for (var e : en.entrySet()) assertTrue(!e.getValue().isBlank(), "пустая строка: " + e.getKey());
    }

    /** У склоняемого ключа все три формы, подстановок в переводах столько же. */
    @Test
    void formsAndPlaceholders() throws IOException {
        var ru = load("ru_ru");
        var en = load("en_us");
        for (String k : ru.keySet()) {
            if (k.endsWith(".one") || k.endsWith(".few") || k.endsWith(".many")) {
                String base = k.substring(0, k.lastIndexOf('.'));
                for (String f : List.of(".one", ".few", ".many")) assertTrue(ru.containsKey(base + f), "нет формы " + base + f);
            }
            assertEquals(placeholders(ru.get(k)), placeholders(en.get(k)), "подстановки расходятся: " + k);
        }
    }

    private static TreeSet<String> placeholders(String s) {
        TreeSet<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("%(?:(\\d+)\\$)?s").matcher(s);
        int i = 0;
        while (m.find()) out.add(m.group(1) != null ? m.group(1) : Integer.toString(++i));
        return out;
    }

    /** В клиентском коде строки с кириллицей только в комментариях (и слова журнала с диска в {@code data.Journal}). */
    @Test
    void noRussianInClientCode() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN.resolve("client"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String lit : literals(Files.readString(f, StandardCharsets.UTF_8))) {
                    if (lit.codePoints().anyMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC)) found.add(f.getFileName() + ": " + lit);
                }
            }
        }
        assertTrue(found.isEmpty(), "зашитый русский текст: " + found);
    }

    /** Строковые и символьные литералы файла без комментариев. */
    static List<String> literals(String src) {
        List<String> out = new ArrayList<>();
        int i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (c == '"' && src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                out.add(src.substring(i + 3, end < 0 ? n : end));
                i = end < 0 ? n : end + 3;
            } else if (c == '"' || c == '\'') {
                StringBuilder b = new StringBuilder();
                i++;
                while (i < n && src.charAt(i) != c) {
                    if (src.charAt(i) == '\\' && i + 1 < n) b.append(src.charAt(i++));
                    b.append(src.charAt(i++));
                }
                out.add(b.toString());
                i++;
            } else {
                i++;
            }
        }
        return out;
    }

    /** Каждый ключ, который код просит целиком (не собирая из частей), есть в файлах. */
    @Test
    void usedKeysExist() throws IOException {
        var ru = load("ru_ru");
        Pattern shortKey = Pattern.compile("((?<![.\\w])(?:tr|tx|n)|Tr\\.tr|Tr\\.n|Texts\\.t|Texts\\.c|Texts\\.n|Said\\.of|Said\\.count)\\((?:[\\w.()]+,\\s*)?\"([a-z0-9_.]+)\"\\s*[,)]");
        Pattern fullKey = Pattern.compile("(?:\\.t|\\.n|\\.raw|translatable|translatableWithFallback)\\(\"(ryshelm\\.[a-z0-9_.]+)\"\\s*[,)]");
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                for (Pattern p : List.of(shortKey, fullKey)) {
                    Matcher m = p.matcher(src);
                    while (m.find()) {
                        String k = m.group(p == shortKey ? 2 : 1);
                        // ClientEntry.tr берёт ключи действий колеса, Lang внутри себя пишет ключи целиком
                        boolean action = p == shortKey && m.group(1).equals("tr") && f.getFileName().toString().equals("ClientEntry.java");
                        if (p == shortKey && !k.startsWith("ryshelm.")) k = (action ? "ryshelm.action." : "ryshelm.") + k;
                        if (!ru.containsKey(k) && !ru.containsKey(k + ".one")) missing.add(f.getFileName() + ": " + k);
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "ключей нет в файлах: " + missing);
    }

    /** Русские сроки, наказания и паузы из файла: прежние слова журнала (до 0.2.9 они были зашиты в логике). */
    @Test
    void russianWords() {
        Lang ru = Lang.ru();
        assertEquals("0 с", ru.termLeft(0));
        assertEquals("30 с", ru.term(30 * Term.SECOND));
        assertEquals("59 мин", ru.term(59 * Term.MINUTE));
        assertEquals("6 ч", ru.term(6 * Term.HOUR));
        assertEquals("22 дня", ru.term(22 * Term.DAY));
        assertEquals("2 дня", ru.term(36 * Term.HOUR));
        assertEquals("365 дней", ru.term(365 * Term.DAY));
        assertEquals("на 7 дней", ru.termOn(7 * Term.DAY));
        assertEquals("предупреждение", ru.kind(Kind.WARN));
        assertEquals("заглушить", ru.kind(Kind.MUTE));
        assertEquals("выгнать", ru.kind(Kind.KICK));
        assertEquals("бан", ru.kind(Kind.BAN));
        assertEquals(List.of("предупреждение", "заглушить на 1 ч", "бан на 1 день", "бан на 7 дней", "бан навсегда", "заглушить на 10 мин", "выгнать"),
            Ladder.steps(Ladder.DEFAULT + ", mute 10m, kick").stream().map(ru::step).toList());
        assertEquals("раз в 30 мин", ru.kitRule(1800));
    }

    @Test
    void english() {
        Lang en = Lang.en();
        assertEquals("1 day", en.term(Term.DAY));
        assertEquals("7 days", en.term(7 * Term.DAY));
        assertEquals("for 1 h", en.termOn(Term.HOUR));
        assertEquals("mute for 1 h", en.step(new Ladder.Step(Kind.MUTE, Term.HOUR)));
        assertEquals("ban forever", en.step(new Ladder.Step(Kind.BAN, Term.FOREVER)));
        assertEquals("every 1 day", en.kitRule(86_400));
        assertEquals("No punishments in 30 days. First step by server rules: warning. After that: mute for 1 h.",
            Ladder.defaults().describe(en, "Grib_ok", List.of(), System.currentTimeMillis()));
        assertEquals(en, Lang.of("en_gb"));
        assertEquals(Lang.ru(), Lang.of("ru_ru"));
    }
}
