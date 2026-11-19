package works.repka.ryshelm.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import works.repka.ryshelm.data.HelmData;
import works.repka.ryshelm.logic.Term;

/** Файлы данных (разбор логики 19.11, п. 2): без отступов, игроки отдельно, старый формат читается, срок жалоб. */
class HelmFilesTest {
    static final long NOW = 1_800_000_000_000L;

    @Test
    void playersSeparateAndCompact(@TempDir Path world) throws Exception {
        HelmData d = new HelmData();
        UUID a = UUID.randomUUID();
        d.player(a, "Grib_ok").played = 42;
        d.moderators.put(a, "Grib_ok");
        Helm.writeData(world, d);
        String main = Files.readString(world.resolve("ryshelm/helm.json"), StandardCharsets.UTF_8);
        String players = Files.readString(world.resolve("ryshelm/players.json"), StandardCharsets.UTF_8);
        assertFalse(main.contains("\"players\""), "игроки не в helm.json");
        assertFalse(main.contains("\n"), "без отступов");
        assertTrue(players.contains("Grib_ok"));
        HelmData back = Helm.readData(world);
        assertEquals(42, back.players.get(a).played);
        assertEquals("Grib_ok", back.moderators.get(a));
    }

    @Test
    void oldFileWithPlayersInside(@TempDir Path world) throws Exception {
        UUID a = UUID.randomUUID();
        Files.createDirectories(world.resolve("ryshelm"));
        Files.writeString(world.resolve("ryshelm/helm.json"), "{\"v\":1,\"moderators\":{},\"players\":{\"" + a + "\":{\"name\":\"Lena_K\",\"played\":7}}}", StandardCharsets.UTF_8);
        HelmData d = Helm.readData(world);
        assertEquals("Lena_K", d.players.get(a).name);
        Helm.writeData(world, d);
        assertEquals(7, Helm.readData(world).players.get(a).played);
    }

    @Test
    void pruneClosedReports() {
        HelmData d = new HelmData();
        HelmData.Report open = report(d, HelmData.Report.State.NEW, NOW - 400 * Term.DAY);
        HelmData.Report old = report(d, HelmData.Report.State.CLOSED, NOW - 100 * Term.DAY);
        HelmData.Report week = report(d, HelmData.Report.State.CLOSED, NOW - 10 * Term.DAY);
        HelmData.Report fresh = report(d, HelmData.Report.State.CLOSED, NOW - Term.DAY);
        assertEquals(2, d.prune(NOW, 90 * Term.DAY, 7 * Term.DAY));
        assertTrue(d.reports.contains(open), "открытая остаётся");
        assertFalse(d.reports.contains(old));
        assertTrue(week.chat.isEmpty(), "чат закрытой старше недели чистится");
        assertEquals(1, fresh.chat.size());
        assertEquals(0, d.prune(NOW, 90 * Term.DAY, 7 * Term.DAY), "повтор ничего не меняет");
    }

    static HelmData.Report report(HelmData d, HelmData.Report.State state, long closed) {
        HelmData.Report r = new HelmData.Report();
        r.id = d.id();
        r.state = state;
        r.time = closed;
        r.closedAt = state == HelmData.Report.State.CLOSED ? closed : 0;
        r.chat.add(new HelmData.ChatLine(closed, "строка"));
        d.reports.add(r);
        return r;
    }
}
