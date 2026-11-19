package works.repka.ryshelm.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class KitRuleTest {
    static final long NOW = 1_800_000_000_000L;

    @Test
    void neverTakenIsReady() {
        assertEquals(0, KitRule.left(KitRule.ONCE, 0, NOW));
        assertEquals(0, KitRule.left(86400, 0, NOW));
    }

    @Test
    void onceMeansNever() {
        assertEquals(Long.MAX_VALUE, KitRule.left(KitRule.ONCE, NOW - Term.WEEK, NOW));
    }

    @Test
    void cooldownCounts() {
        assertEquals(Term.HOUR, KitRule.left(86400, NOW - 23 * Term.HOUR, NOW));
        assertEquals(0, KitRule.left(86400, NOW - Term.DAY, NOW));
        assertEquals(0, KitRule.left(0, NOW - 1, NOW));
    }

    @Test
    void parseAndLabel() {
        assertEquals(KitRule.ONCE, KitRule.parse("once"));
        assertEquals(KitRule.ONCE, KitRule.parse("perm"));
        assertEquals(0, KitRule.parse("0"));
        assertEquals(86400, KitRule.parse("1d"));
        assertEquals(1800, KitRule.parse("30m"));
        assertEquals("один раз", KitRule.label(KitRule.ONCE));
        assertEquals("без паузы", KitRule.label(0));
        assertEquals("раз в 1 день", KitRule.label(86400));
        assertThrows(IllegalArgumentException.class, () -> KitRule.parse("скоро"));
    }
}
