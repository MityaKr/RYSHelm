package works.repka.ryshelm.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Решение вопроса 7: модератор глушит до 1 дня, банит до 7 дней, навсегда только оператор. */
class CapsTest {
    final Caps caps = Caps.defaults();

    @Test
    void moderatorMute() {
        assertNull(caps.check(false, false, Kind.MUTE, 10 * Term.MINUTE));
        assertNull(caps.check(false, false, Kind.MUTE, Term.DAY));
        assertEquals("Модератору заглушить до 1 день", caps.check(false, false, Kind.MUTE, Term.DAY + Term.MINUTE));
        assertNotNull(caps.check(false, false, Kind.MUTE, Term.FOREVER));
    }

    @Test
    void moderatorBan() {
        assertNull(caps.check(false, false, Kind.BAN, Term.DAY));
        assertNull(caps.check(false, false, Kind.BAN, 7 * Term.DAY));
        assertNotNull(caps.check(false, false, Kind.BAN, 8 * Term.DAY));
        assertEquals("Бан навсегда даёт только оператор", caps.check(false, false, Kind.BAN, Term.FOREVER));
    }

    @Test
    void permanentBanRightLiftsBanCap() {
        assertNull(caps.check(false, true, Kind.BAN, Term.FOREVER));
        assertNull(caps.check(false, true, Kind.BAN, 30 * Term.DAY));
        assertNotNull(caps.check(false, true, Kind.MUTE, 2 * Term.DAY));
    }

    @Test
    void operatorHasNoCaps() {
        assertNull(caps.check(true, false, Kind.MUTE, Term.FOREVER));
        assertNull(caps.check(true, false, Kind.BAN, Term.FOREVER));
        assertEquals(Term.FOREVER, caps.max(true, false, Kind.BAN));
    }

    @Test
    void untimedAlwaysPass() {
        assertNull(caps.check(false, false, Kind.WARN, 0));
        assertNull(caps.check(false, false, Kind.KICK, 0));
    }

    @Test
    void maxForLabel() {
        assertEquals(Term.DAY, caps.max(false, false, Kind.MUTE));
        assertEquals(7 * Term.DAY, caps.max(false, false, Kind.BAN));
        Caps custom = new Caps(Term.HOUR, 3 * Term.DAY);
        assertEquals("Модератору бан до 3 дня", custom.check(false, false, Kind.BAN, 4 * Term.DAY));
    }
}
