package works.repka.ryshelm.logic;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Ведро токенов на действия игрока (разбор логики 19.11, п. 4; как в RYSTeams): {@code burst} действий подряд, дальше
 * {@code ratePerSecond} в секунду. Лишние действия сервер молча отбрасывает.
 */
public final class Throttle {
    private static final class Bucket {
        double tokens;
        long last;
    }

    private final int burst;
    private final double rate;
    private final Map<UUID, Bucket> buckets = new HashMap<>();

    public Throttle(int burst, double ratePerSecond) {
        this.burst = burst;
        this.rate = ratePerSecond;
    }

    public boolean allow(UUID player, long now) {
        Bucket b = buckets.get(player);
        if (b == null) {
            b = new Bucket();
            b.tokens = burst;
            b.last = now;
            buckets.put(player, b);
        }
        b.tokens = Math.min(burst, b.tokens + Math.max(0, now - b.last) * rate / 1000.0);
        b.last = now;
        if (b.tokens < 1) return false;
        b.tokens -= 1;
        return true;
    }

    public void forget(UUID player) {
        buckets.remove(player);
    }

    public void clear() {
        buckets.clear();
    }
}
