package works.repka.ryshelm.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import works.repka.ryshelm.logic.Kind;
import works.repka.ryshelm.logic.Term;

/** Индекс наказаний (разбор логики 19.11, п. 3): добавление, снятие на месте, истечение срока, замена списка. */
class HelmDataTest {
    static final long NOW = 1_800_000_000_000L;

    static HelmData.Punishment p(HelmData d, UUID who, Kind kind, long created, long expires) {
        HelmData.Punishment x = new HelmData.Punishment();
        x.id = d.id();
        x.kind = kind;
        x.target = who;
        x.created = created;
        x.expires = expires;
        d.punishments.add(x);
        return x;
    }

    @Test
    void indexFollowsAppendsRevokesAndExpiry() {
        HelmData d = new HelmData();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertNull(d.active(a, Kind.MUTE, NOW));
        HelmData.Punishment m = p(d, a, Kind.MUTE, NOW - Term.MINUTE, NOW + Term.HOUR);
        p(d, b, Kind.WARN, NOW, 0);
        assertSame(m, d.active(a, Kind.MUTE, NOW));
        assertNull(d.active(b, Kind.MUTE, NOW));
        assertSame(m, d.punishment(m.id));
        m.revoked = NOW;
        assertNull(d.active(a, Kind.MUTE, NOW), "снятое на месте не действует");
        HelmData.Punishment ban = p(d, a, Kind.BAN, NOW, NOW + Term.DAY);
        assertSame(ban, d.active(a, Kind.BAN, NOW), "новое после построения индекса видно");
        assertNull(d.active(a, Kind.BAN, NOW + 2 * Term.DAY), "истёкшее не действует");
        assertEquals(2, d.history(a).size());
        assertSame(ban, d.of(a).get(0), "новые сверху");
        assertEquals(1, d.past(b).size());
    }

    @Test
    void moderationActive() {
        HelmData d = new HelmData();
        assertEquals(false, d.moderationActive());
        HelmData.Report r = new HelmData.Report();
        r.state = HelmData.Report.State.NEW;
        d.reports.add(r);
        assertEquals(true, d.moderationActive(), "открытая жалоба");
        r.state = HelmData.Report.State.TAKEN;
        assertEquals(true, d.moderationActive(), "взятая тоже открыта");
        r.state = HelmData.Report.State.CLOSED;
        assertEquals(false, d.moderationActive());
        d.moderators.put(UUID.randomUUID(), "Vasya_M");
        assertEquals(true, d.moderationActive(), "назначен модератор");
    }

    @Test
    void replacedListIsReindexed() {
        HelmData d = new HelmData();
        UUID a = UUID.randomUUID();
        p(d, a, Kind.MUTE, NOW, 0);
        assertEquals(1, d.history(a).size());
        d.punishments = new java.util.ArrayList<>();
        assertEquals(0, d.history(a).size());
    }
}
