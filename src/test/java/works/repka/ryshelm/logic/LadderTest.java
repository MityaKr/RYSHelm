package works.repka.ryshelm.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LadderTest {
    static final long NOW = 1_800_000_000_000L;

    @Test
    void defaultSteps() {
        Ladder l = Ladder.defaults();
        assertEquals(5, l.steps().size());
        assertEquals(new Ladder.Step(Kind.WARN, 0), l.steps().get(0));
        assertEquals(new Ladder.Step(Kind.MUTE, Term.HOUR), l.steps().get(1));
        assertEquals(new Ladder.Step(Kind.BAN, Term.FOREVER), l.steps().get(4));
        assertEquals(Ladder.DEFAULT, l.text());
    }

    @Test
    void cleanPlayerStartsWithWarning() {
        Ladder.Hint h = Ladder.defaults().next(List.of(), NOW);
        assertEquals(0, h.count());
        assertEquals(Kind.WARN, h.next().kind());
        assertEquals(Kind.MUTE, h.after().kind());
    }

    /** Макет 09: одно предупреждение три дня назад, следующий шаг заглушить на 1 ч, дальше бан на 1 день. */
    @Test
    void mockup09() {
        Ladder l = Ladder.defaults();
        List<Ladder.Past> past = List.of(new Ladder.Past(Kind.WARN, NOW - 3 * Term.DAY + Term.HOUR, false));
        Ladder.Hint h = l.next(past, NOW);
        assertEquals(1, h.count());
        assertEquals(1, h.warnings());
        assertEquals(new Ladder.Step(Kind.MUTE, Term.HOUR), h.next());
        assertEquals(new Ladder.Step(Kind.BAN, Term.DAY), h.after());
        assertEquals("У Grib_ok 1 предупреждение за 3 дня. Следующий шаг по правилам сервера: заглушить на 1 ч. Дальше по лестнице: бан на 1 день.",
            l.describe("Grib_ok", past, NOW));
    }

    @Test
    void revokedAndOldDoNotCount() {
        Ladder l = Ladder.defaults();
        List<Ladder.Past> past = List.of(
            new Ladder.Past(Kind.WARN, NOW - 40 * Term.DAY, false),
            new Ladder.Past(Kind.MUTE, NOW - Term.DAY, true));
        assertEquals(0, l.next(past, NOW).count());
        assertEquals(Kind.WARN, l.next(past, NOW).next().kind());
    }

    /** Grib_ok из сценария `ui`: предупреждение 3 дня назад и бан на 2 дня, выданный 9 дней назад. Истёкший бан не считается. */
    @Test
    void expiredBanDoesNotCount() {
        Ladder l = Ladder.defaults();
        List<Ladder.Past> past = List.of(
            new Ladder.Past(Kind.WARN, NOW - 3 * Term.DAY + Term.HOUR, false),
            new Ladder.Past(Kind.BAN, NOW - 9 * Term.DAY, false, NOW - 7 * Term.DAY));
        Ladder.Hint h = l.next(past, NOW);
        assertEquals(1, h.count());
        assertEquals(new Ladder.Step(Kind.MUTE, Term.HOUR), h.next());
        assertEquals("У Grib_ok 1 предупреждение за 3 дня. Следующий шаг по правилам сервера: заглушить на 1 ч. Дальше по лестнице: бан на 1 день.",
            l.describe("Grib_ok", past, NOW));
    }

    /** Действующее наказание считается и вне окна: мут на 60 дней, выданный 40 дней назад. Предупреждение 40 дней назад нет. */
    @Test
    void activeCountsOutsideWindow() {
        Ladder l = Ladder.defaults();
        List<Ladder.Past> past = List.of(
            new Ladder.Past(Kind.WARN, NOW - 40 * Term.DAY, false),
            new Ladder.Past(Kind.MUTE, NOW - 40 * Term.DAY, false, NOW + 20 * Term.DAY),
            new Ladder.Past(Kind.BAN, NOW - 5 * Term.DAY, false, 0));
        Ladder.Hint h = l.next(past, NOW);
        assertEquals(2, h.count());
        assertEquals(0, h.warnings());
        assertEquals(new Ladder.Step(Kind.BAN, Term.DAY), h.next());
    }

    @Test
    void lastStepRepeats() {
        Ladder l = Ladder.defaults();
        List<Ladder.Past> past = IntStream.range(0, 9).mapToObj(i -> new Ladder.Past(Kind.WARN, NOW - i * Term.HOUR, false)).toList();
        Ladder.Hint h = l.next(past, NOW);
        assertEquals(new Ladder.Step(Kind.BAN, Term.FOREVER), h.next());
        assertNull(h.after());
        assertEquals("У X 9 предупреждений за 1 день. Следующий шаг по правилам сервера: бан навсегда.", l.describe("X", past, NOW));
    }

    @Test
    void mixedSummary() {
        Ladder l = Ladder.defaults();
        List<Ladder.Past> past = List.of(new Ladder.Past(Kind.WARN, NOW - 2 * Term.DAY, false), new Ladder.Past(Kind.MUTE, NOW - Term.DAY, false));
        assertEquals("У X 1 предупреждение и 1 наказание за 2 дня. Следующий шаг по правилам сервера: бан на 1 день. Дальше по лестнице: бан на 7 дней.",
            l.describe("X", past, NOW));
    }

    @Test
    void cleanDescription() {
        assertEquals("Наказаний за 30 дней нет. Первый шаг по правилам сервера: предупреждение. Дальше по лестнице: заглушить на 1 ч.",
            Ladder.defaults().describe("X", List.of(), NOW));
    }

    @Test
    void customLadderAndWindow() {
        Ladder l = Ladder.parse("warn, warn, мут 30m, kick, бан 3d", 7);
        assertEquals(5, l.steps().size());
        assertEquals(7 * Term.DAY, l.window());
        assertEquals("warn, warn, mute 30m, kick, ban 3d", l.text());
        List<Ladder.Past> past = List.of(new Ladder.Past(Kind.WARN, NOW - 6 * Term.DAY, false), new Ladder.Past(Kind.WARN, NOW - 8 * Term.DAY, false));
        assertEquals(new Ladder.Step(Kind.WARN, 0), l.next(past, NOW).next());
    }

    @Test
    void badLadderFallsBack() {
        assertEquals(Ladder.DEFAULT, Ladder.parse("jump 5m", 30).text());
        assertEquals(Ladder.DEFAULT, Ladder.parse("mute", 30).text());
        assertThrows(IllegalArgumentException.class, () -> Ladder.steps(""));
    }
}
