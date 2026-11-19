package works.repka.ryshelm.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalTest {
    static final ZoneId Z = ZoneId.of("Europe/Chisinau");

    static long at(int y, int m, int d) {
        return LocalDateTime.of(y, m, d, 12, 0).atZone(Z).toInstant().toEpochMilli();
    }

    @Test
    void writesMonthFilesAndReadsBack(@TempDir Path dir) throws Exception {
        Journal j = new Journal(dir, 90, Z);
        UUID mod = UUID.randomUUID(), grib = UUID.randomUUID();
        Journal.Entry e1 = Journal.entry("mute", mod, "Vasya_M", grib, "Grib_ok", "на 1 ч", "п. 3 · мат");
        e1.t = at(2026, 10, 30);
        j.add(e1);
        Journal.Entry e2 = Journal.entry("invsee", mod, "Vasya_M", grib, "Grib_ok", "", "только смотрел");
        e2.t = at(2026, 11, 19);
        j.add(e2);
        j.flush();
        assertTrue(Files.exists(dir.resolve("journal-2026-10.jsonl")));
        assertTrue(Files.exists(dir.resolve("journal-2026-11.jsonl")));

        j.close();
        Journal again = new Journal(dir, 90, Z);
        assertEquals(2, again.load(at(2026, 11, 19)));
        var list = again.query(e -> true, 10);
        assertEquals("invsee", list.get(0).type);
        assertEquals("Grib_ok", list.get(1).pn);
        int[] c = again.counts(e -> true);
        assertEquals(2, c[0]);
        assertEquals(1, c[Journal.Category.PUNISH.ordinal() + 1]);
        assertEquals(1, c[Journal.Category.WATCH.ordinal() + 1]);
    }

    @Test
    void retentionDropsOldMonthsAndSkipsBrokenLines(@TempDir Path dir) throws Exception {
        Journal j = new Journal(dir, 30, Z);
        Journal.Entry old = Journal.entry("kick", null, "консоль", null, "X", "", "");
        old.t = at(2026, 6, 1);
        j.add(old);
        Journal.Entry fresh = Journal.entry("warn", null, "консоль", null, "Y", "", "");
        fresh.t = at(2026, 11, 10);
        j.add(fresh);
        j.close();
        Files.writeString(dir.resolve("journal-2026-11.jsonl"), "{битая строка\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        Journal again = new Journal(dir, 30, Z);
        assertEquals(1, again.load(at(2026, 11, 19)));
        assertFalse(Files.exists(dir.resolve("journal-2026-06.jsonl")));
        assertEquals("Y", again.query(e -> true, 5).get(0).pn);
    }

    @Test
    void bufferedUntilFlush(@TempDir Path dir) throws Exception {
        Journal j = new Journal(dir, 90, Z);
        Journal.Entry e = Journal.entry("warn", null, "консоль", null, "X", "", "");
        e.t = at(2026, 11, 19);
        j.add(e);
        assertEquals(1, j.query(x -> true, 5).size(), "в памяти сразу");
        j.flush();
        assertTrue(Files.readString(dir.resolve("journal-2026-11.jsonl"), StandardCharsets.UTF_8).contains("\"warn\""));
        Journal.Entry next = Journal.entry("kick", null, "консоль", null, "Y", "", "");
        next.t = at(2026, 12, 1);
        j.add(next);
        j.close();
        assertTrue(Files.exists(dir.resolve("journal-2026-12.jsonl")), "новый месяц в новом файле");
    }

    @Test
    void categories() {
        assertEquals(Journal.Category.PUNISH, Journal.category("ban"));
        assertEquals(Journal.Category.WATCH, Journal.category("spectate"));
        assertEquals(Journal.Category.WATCH, Journal.category("fly"));
        assertEquals(Journal.Category.ROLES, Journal.category("moderator_add"));
        assertEquals(Journal.Category.REPORTS, Journal.category("report_close"));
        assertEquals(Journal.Category.OTHER, Journal.category("что-то"));
    }
}
