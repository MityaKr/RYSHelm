package works.repka.ryshelm.logic;

/**
 * Пауза набора без игры (план 9, «Наборы»): {@code -1} один раз за всю игру, {@code 0} без паузы, иначе секунды между
 * получениями.
 */
public final class KitRule {
    public static final int ONCE = -1;

    private KitRule() {}

    /**
     * Сколько ждать до следующего получения, мс.
     * @param lastUsed когда игрок брал набор, 0 не брал
     * @return 0 можно сейчас, {@link Long#MAX_VALUE} больше никогда
     */
    public static long left(int cooldownSeconds, long lastUsed, long now) {
        if (lastUsed <= 0) return 0;
        if (cooldownSeconds == ONCE) return Long.MAX_VALUE;
        if (cooldownSeconds <= 0) return 0;
        long until = lastUsed + cooldownSeconds * 1000L;
        return Math.max(0, until - now);
    }

    /** «один раз», «без паузы», «раз в 1 день». */
    public static String label(int cooldownSeconds) {
        if (cooldownSeconds == ONCE) return "один раз";
        if (cooldownSeconds <= 0) return "без паузы";
        return "раз в " + Term.label(cooldownSeconds * 1000L);
    }

    /** Пауза из строки команды: {@code once}, {@code 0}, {@code 1d}, {@code 30m}. */
    public static int parse(String text) {
        String s = text.strip().toLowerCase(java.util.Locale.ROOT);
        if (s.equals("once") || s.equals("один") || s.equals("-1")) return ONCE;
        if (s.equals("0") || s.equals("none")) return 0;
        long ms = Term.parse(s);
        if (ms == Term.FOREVER) return ONCE;
        return (int) Math.min(Integer.MAX_VALUE, ms / 1000L);
    }
}
