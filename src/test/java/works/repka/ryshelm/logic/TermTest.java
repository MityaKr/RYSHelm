package works.repka.ryshelm.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TermTest {
    @Test
    void parsesUnits() {
        assertEquals(10 * Term.MINUTE, Term.parse("10m"));
        assertEquals(Term.HOUR, Term.parse("1h"));
        assertEquals(Term.DAY, Term.parse("1d"));
        assertEquals(7 * Term.DAY, Term.parse("7d"));
        assertEquals(2 * Term.WEEK, Term.parse("2w"));
        assertEquals(30 * Term.SECOND, Term.parse("30s"));
        assertEquals(Term.DAY + 12 * Term.HOUR, Term.parse("1d12h"));
        assertEquals(90 * Term.MINUTE, Term.parse("1h30m"));
    }

    @Test
    void parsesRussianAndForever() {
        assertEquals(10 * Term.MINUTE, Term.parse("10мин"));
        assertEquals(Term.HOUR, Term.parse("1ч"));
        assertEquals(3 * Term.DAY, Term.parse("3д"));
        assertEquals(Term.FOREVER, Term.parse("perm"));
        assertEquals(Term.FOREVER, Term.parse("навсегда"));
        assertEquals(Term.FOREVER, Term.parse(" FOREVER "));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> Term.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Term.parse("0m"));
        assertThrows(IllegalArgumentException.class, () -> Term.parse("10"));
        assertThrows(IllegalArgumentException.class, () -> Term.parse("h"));
        assertThrows(IllegalArgumentException.class, () -> Term.parse("5x"));
        assertEquals(Term.HOUR, Term.parse("bad", Term.HOUR));
    }

    @Test
    void codeRoundTrips() {
        for (long t : new long[] {10 * Term.MINUTE, Term.HOUR, Term.DAY, 7 * Term.DAY, 90 * Term.MINUTE, Term.FOREVER, 45 * Term.SECOND}) {
            assertEquals(t, Term.parse(Term.code(t)), Term.code(t));
        }
        assertEquals("7d", Term.code(7 * Term.DAY));
        assertEquals("perm", Term.code(Term.FOREVER));
    }

    @Test
    void labelsInRussian() {
        assertEquals("10 мин", Lang.ru().term(10 * Term.MINUTE));
        assertEquals("1 ч", Lang.ru().term(Term.HOUR));
        assertEquals("1 ч 30 мин", Lang.ru().term(90 * Term.MINUTE));
        assertEquals("1 день", Lang.ru().term(Term.DAY));
        assertEquals("2 дня", Lang.ru().term(2 * Term.DAY));
        assertEquals("7 дней", Lang.ru().term(7 * Term.DAY));
        assertEquals("21 день", Lang.ru().term(21 * Term.DAY));
        assertEquals("11 дней", Lang.ru().term(11 * Term.DAY));
        assertEquals("навсегда", Lang.ru().term(Term.FOREVER));
        assertEquals("на 1 ч", Lang.ru().termOn(Term.HOUR));
        assertEquals("навсегда", Lang.ru().termOn(Term.FOREVER));
    }

    @Test
    void leftClock() {
        assertEquals("0:41", Lang.ru().termLeft(41 * Term.SECOND));
        assertEquals("12:05", Lang.ru().termLeft(12 * Term.MINUTE + 5 * Term.SECOND));
        assertEquals("3 ч", Lang.ru().termLeft(3 * Term.HOUR + 20 * Term.MINUTE));
        assertEquals("2 дня", Lang.ru().termLeft(2 * Term.DAY + 5 * Term.HOUR));
    }
}
