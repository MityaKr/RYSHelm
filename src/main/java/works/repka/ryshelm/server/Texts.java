package works.repka.ryshelm.server;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import works.repka.ryshelm.logic.Lang;

/**
 * Тексты сервера игроку. Канал мода необязательный, на сервер может зайти игрок без мода, поэтому:
 * <ul>
 * <li>строки (уведомления, ответы окну и команд) сервер собирает сам на языке, который игрок назвал игре
 * ({@link ServerPlayer#getLanguage()}), из файлов перевода в jar мода; консоль получает английский;</li>
 * <li>готовые сообщения игры (экран отключения, чат, заголовок сундука, строка в Tab) идут
 * {@link Component#translatableWithFallback}: клиент с модом берёт строку из своих файлов, клиент без мода видит запасной
 * текст на языке игрока, а не ключ.</li>
 * </ul>
 * Журнал персонала и логи сервера остаются по-русски, как были.
 */
public final class Texts {
    private Texts() {}

    public static Lang lang(@Nullable ServerPlayer p) {
        return p == null ? Lang.en() : Lang.of(p.getLanguage());
    }

    public static Lang lang(CommandSourceStack s) {
        return lang(s.getPlayer());
    }

    /** Строка по ключу {@code ryshelm.<key>} на языке игрока. */
    public static String t(@Nullable ServerPlayer p, String key, Object... args) {
        return lang(p).t("ryshelm." + key, args);
    }

    /** «3 дня» на языке игрока: ключ {@code ryshelm.<key>.one/.few/.many}. */
    public static String n(@Nullable ServerPlayer p, String key, long n, Object... more) {
        return lang(p).n("ryshelm." + key, n, more);
    }

    /** Сообщение игры: ключ и запасной текст на языке игрока (его видит клиент без мода). */
    public static MutableComponent c(@Nullable ServerPlayer p, String key, Object... args) {
        String k = "ryshelm." + key;
        return Component.translatableWithFallback(k, lang(p).raw(k), args);
    }
}
