package works.repka.ryshelm.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import works.repka.ryshelm.logic.Kind;
import works.repka.ryshelm.logic.Lang;

/**
 * Строка журнала словами (0.2.12, замечание проверки 23.11: «Dev kit_create check1123» в {@code /rys admin journal}):
 * у каждого вида записи глагол в обоих языках, ни один вид не показывается ключом, старые записи читаются.
 */
class JournalLineTest {
    private static final Path MAIN = Path.of(System.getProperty("ryshelm.root", ".")).resolve("src/main/java/works/repka/ryshelm");

    /** У каждого вида из {@link Journal#TYPES} есть глагол по-русски и по-английски. */
    @Test
    void everyTypeHasVerbInBothLanguages() {
        for (String type : Journal.TYPES) {
            for (Lang lang : List.of(Lang.ru(), Lang.en())) {
                String key = JournalLine.verbKey(type);
                assertNotEquals(key, lang.raw(key), "нет глагола " + key + (lang.russian() ? " в ru_ru" : " в en_us"));
            }
        }
        assertEquals(Journal.TYPES.size(), new HashSet<>(Journal.TYPES).size(), "вид записи повторяется");
    }

    /** Ни одна строка ни на одном языке не содержит голого вида записи. */
    @Test
    void noTypeShownAsKey() {
        for (String type : Journal.TYPES) {
            for (Lang lang : List.of(Lang.ru(), Lang.en())) {
                for (String p : List.of("", "Grib_ok")) {
                    String line = JournalLine.text(lang, type, "Vasya_M", p, "");
                    assertFalse(line.contains(type) && (type.contains("_") || lang.russian()), "голый вид записи: " + line);
                    assertFalse(line.contains("ryshelm."), "ключ вместо текста: " + line);
                    assertFalse(line.contains("%"), "подстановка осталась: " + line);
                    assertTrue(line.contains("Vasya_M") || type.equals("join_banned"), "нет того, кто сделал: " + line);
                }
            }
        }
    }

    /** Каждый вид, который код пишет в журнал, есть в {@link Journal#TYPES} (и попадает в проверки выше). */
    @Test
    void everyLoggedTypeIsKnown() throws IOException {
        Set<String> logged = new TreeSet<>();
        for (Kind k : Kind.values()) logged.add(k.id());
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (!line.contains("log(") || line.contains("LOG.")) continue;
                    for (String lit : literalsOf(line)) if (lit.matches("[a-z][a-z_]*")) logged.add(lit);
                }
            }
        }
        List<String> unknown = new ArrayList<>(logged);
        unknown.removeAll(Journal.TYPES);
        assertTrue(unknown.isEmpty(), "виды записей без глагола в Journal.TYPES: " + unknown);
        assertTrue(logged.contains("kit_create") && logged.contains("moderator_add") && logged.contains("heal"), "поиск видов в коде сломался: " + logged);
    }

    private static List<String> literalsOf(String line) {
        List<String> out = new ArrayList<>();
        int i = line.indexOf("//");
        // чтение полей пакета (a.getString("dim")) не вид записи
        String s = (i < 0 ? line : line.substring(0, i)).replaceAll("\\.get\\w*\\(\"[^\"]*\"\\)", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(s);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @Test
    void linesInBothLanguages() {
        assertEquals("Dev собрал набор check1123", JournalLine.text(Lang.ru(), "kit_create", "Dev", "", "check1123"));
        assertEquals("Dev created kit check1123", JournalLine.text(Lang.en(), "kit_create", "Dev", "", "check1123"));
        assertEquals("Vasya_M заглушил Grib_ok на 1 ч", JournalLine.text(Lang.ru(), "mute", "Vasya_M", "Grib_ok", "на 1 ч"));
        assertEquals("Vasya_M muted Grib_ok for 1 h", JournalLine.text(Lang.en(), "mute", "Vasya_M", "Grib_ok", "for 1 h"));
        assertEquals("Vasya_M changed settings: newcomer 2 hours", JournalLine.text(Lang.en(), "settings", "Vasya_M", "", "newcomer 2 hours"));
    }

    /** Неизвестный вид (запись будущей версии) словами, а не ключом. */
    @Test
    void unknownTypeAsWords() {
        assertEquals("Vasya_M door open Grib_ok", JournalLine.text(Lang.en(), "door_open", "Vasya_M", "Grib_ok", ""));
        assertEquals("door open", JournalLine.verb(Lang.ru(), "door_open"));
    }

    /** Старые записи (русская строка с диска, без частей) читаются словами на языке читателя. */
    @Test
    void oldEntriesRead(@TempDir Path dir) throws Exception {
        long now = System.currentTimeMillis();
        String old = "{\"t\":" + now + ",\"type\":\"fly\",\"an\":\"Vasya_M\",\"pn\":\"Grib_ok\",\"d\":\"включил\",\"n\":\"\"}\n"
            + "{\"t\":" + now + ",\"type\":\"kit_create\",\"an\":\"консоль\",\"pn\":\"\",\"d\":\"start\",\"n\":\"2 предмета\"}\n"
            + "{\"t\":" + now + ",\"type\":\"whitelist\",\"an\":\"Vasya_M\",\"pn\":\"Grib_ok\",\"d\":\"добавил\",\"n\":\"\"}\n";
        Files.writeString(dir.resolve(Journal.fileName(java.time.YearMonth.now(ZoneId.of("Europe/Chisinau")))), old, StandardCharsets.UTF_8);
        Journal j = new Journal(dir, 90, ZoneId.of("Europe/Chisinau"));
        assertEquals(3, j.load(now));
        List<String> en = new ArrayList<>(), ru = new ArrayList<>();
        for (Journal.Entry e : j.query(x -> true, 10)) {
            en.add(JournalLine.text(Lang.en(), e.type, e.an, e.pn, e.detail(Lang.en())));
            ru.add(JournalLine.text(Lang.ru(), e.type, e.an, e.pn, e.detail(Lang.ru())));
        }
        assertEquals(List.of("Vasya_M added Grib_ok to the whitelist", "консоль created kit start", "Vasya_M turned on flight for Grib_ok"), en);
        assertEquals(List.of("Vasya_M добавил Grib_ok в белый список", "консоль собрал набор start", "Vasya_M включил игроку Grib_ok полёт"), ru);
    }
}
