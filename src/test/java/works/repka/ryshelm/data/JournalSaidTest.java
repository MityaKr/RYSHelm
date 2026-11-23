package works.repka.ryshelm.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import works.repka.ryshelm.logic.Lang;
import works.repka.ryshelm.logic.Said;
import works.repka.ryshelm.logic.Term;

/** Журнал ключами (0.2.9): новая запись на диске по-русски и частями, показ на языке читателя, старые записи как были. */
class JournalSaidTest {
    static final ZoneId Z = ZoneId.of("Europe/Chisinau");

    @Test
    void keyedEntryRendersInReaderLanguage(@TempDir Path dir) throws Exception {
        Journal j = new Journal(dir, 90, Z);
        UUID mod = UUID.randomUUID(), grib = UUID.randomUUID();
        Said note = Said.join(" · ", List.of(Said.of("msg.rule_short", 3), Said.lit("мат в чате")));
        Journal.Entry e = Journal.entry("mute", mod, "Vasya_M", grib, "Grib_ok", Said.of("log.text", Said.termOn(Term.HOUR)), note);
        assertEquals("на 1 ч", e.d);
        assertEquals("п. 3 · мат в чате", e.n);
        j.add(e);
        Journal.Entry report = Journal.entry("report", grib, "Grib_ok", mod, "Vasya_M", Said.NONE,
            Said.join(" · ", List.of(Said.of("msg.rule_short", 2), Said.of("msg.report.state.new"))).then(Said.lit("#41")));
        assertEquals("п. 2 · новая#41", report.n);
        j.add(report);
        // целиком литеральный текст ложится только строкой
        Journal.Entry fly = Journal.entry("fly", mod, "Vasya_M", null, "", Said.lit(Journal.D_ON), Said.NONE);
        assertNull(fly.dk);
        j.add(fly);
        j.close();

        Journal again = new Journal(dir, 90, Z);
        again.load(System.currentTimeMillis());
        List<Journal.Entry> all = again.query(x -> true, 10);
        Journal.Entry mute = all.stream().filter(x -> x.type.equals("mute")).findFirst().orElseThrow();
        assertNotNull(mute.dk);
        assertEquals("for 1 h", mute.detail(Lang.en()));
        assertEquals("rule 3 · мат в чате", mute.note(Lang.en()));
        assertEquals("на 1 ч", mute.detail(Lang.ru()));
        Journal.Entry r = all.stream().filter(x -> x.type.equals("report")).findFirst().orElseThrow();
        assertEquals("rule 2 · new", r.note(Lang.en()));
        assertTrue(r.n.endsWith("#41"), "номер жалобы остаётся в русской строке для поиска");
        assertEquals(Journal.D_ON, all.stream().filter(x -> x.type.equals("fly")).findFirst().orElseThrow().detail(Lang.en()));
    }

    @Test
    void oldEntriesReadAsBefore(@TempDir Path dir) throws Exception {
        String old = "{\"t\":" + System.currentTimeMillis() + ",\"type\":\"invsee\",\"an\":\"Vasya_M\",\"pn\":\"Grib_ok\",\"d\":\"\",\"n\":\"только смотрел#12\"}\n";
        Files.writeString(dir.resolve(Journal.fileName(java.time.YearMonth.now(Z))), old, StandardCharsets.UTF_8);
        Journal j = new Journal(dir, 90, Z);
        assertEquals(1, j.load(System.currentTimeMillis()));
        Journal.Entry e = j.query(x -> true, 1).get(0);
        assertNull(e.nk);
        assertEquals("только смотрел", e.note(Lang.en()));
    }

    @Test
    void logicWordsInArguments() {
        Said s = Said.of("log.caps", Said.term(Term.HOUR), Said.term(7 * Term.DAY));
        assertEquals("mute up to 1 h · ban up to 7 days", s.render(Lang.en()));
        assertEquals("заглушить до 1 ч · бан до 7 дней", s.render(Lang.ru()));
        Said kit = Said.of("log.text", Said.count("ui.set.kits.items", 3)).then(Said.lit(" · ")).then(Said.of("log.text", Said.kitRule(86_400)));
        assertEquals("3 items · every 1 day", kit.render(Lang.en()));
        assertEquals("3 предмета · раз в 1 день", kit.render(Lang.ru()));
        Said set = Said.join(", ", List.of(Said.of("log.set.newbie", Said.count("msg.hours", 2)), Said.of("log.set.anti_spam.on")));
        assertEquals("newcomer 2 hours, anti-spam on", set.render(Lang.en()));
        assertEquals("новичок 2 часа, антиспам включён", set.render(Lang.ru()));
    }
}
