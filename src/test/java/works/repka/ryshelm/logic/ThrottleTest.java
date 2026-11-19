package works.repka.ryshelm.logic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ThrottleTest {
    @Test
    void burstThenRate() {
        Throttle t = new Throttle(3, 2);
        UUID p = UUID.randomUUID(), q = UUID.randomUUID();
        for (int i = 0; i < 3; i++) assertTrue(t.allow(p, 1000));
        assertFalse(t.allow(p, 1000), "ведро пусто");
        assertTrue(t.allow(q, 1000), "у другого игрока своё ведро");
        assertFalse(t.allow(p, 1400), "0,8 токена ещё мало");
        assertTrue(t.allow(p, 1500), "через полсекунды один токен");
        assertTrue(t.allow(p, 10_000) && t.allow(p, 10_000) && t.allow(p, 10_000), "копится не больше ведра");
        assertFalse(t.allow(p, 10_000));
    }
}
