package works.repka.ryshelm.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpamGuardTest {
    final UUID p = UUID.randomUUID();

    @Test
    void tooFast() {
        SpamGuard g = new SpamGuard();
        for (int i = 0; i < 5; i++) assertEquals(SpamGuard.Verdict.OK, g.check(p, "строка " + i, 1000 + i * 100, 5, 8));
        assertEquals(SpamGuard.Verdict.TOO_FAST, g.check(p, "ещё", 1600, 5, 8));
        assertEquals(SpamGuard.Verdict.OK, g.check(p, "потом", 10_000, 5, 8));
    }

    @Test
    void repeatsIgnoreCaseAndSpaces() {
        SpamGuard g = new SpamGuard();
        assertEquals(SpamGuard.Verdict.OK, g.check(p, "Продам алмазы", 0, 5, 8));
        assertEquals(SpamGuard.Verdict.OK, g.check(p, "продам  алмазы", 9_000, 5, 8));
        assertEquals(SpamGuard.Verdict.REPEAT, g.check(p, "ПРОДАМ алмазы ", 18_000, 5, 8));
        assertEquals(SpamGuard.Verdict.OK, g.check(p, "продам алмазы", 45_000, 5, 8));
    }

    @Test
    void threeDenialsMakeStrike() {
        SpamGuard g = new SpamGuard();
        for (int i = 0; i < 2; i++) g.check(p, "a" + i, 0, 2, 8);
        assertEquals(SpamGuard.Verdict.TOO_FAST, g.check(p, "x", 100, 2, 8));
        assertEquals(SpamGuard.Verdict.TOO_FAST, g.check(p, "y", 200, 2, 8));
        assertEquals(SpamGuard.Verdict.STRIKE, g.check(p, "z", 300, 2, 8));
        assertEquals(SpamGuard.Verdict.TOO_FAST, g.check(p, "w", 400, 2, 8));
    }

    @Test
    void playersApart() {
        SpamGuard g = new SpamGuard();
        UUID q = UUID.randomUUID();
        for (int i = 0; i < 3; i++) g.check(p, "s" + i, i, 3, 8);
        assertEquals(SpamGuard.Verdict.OK, g.check(q, "моя первая", 10, 3, 8));
    }
}
