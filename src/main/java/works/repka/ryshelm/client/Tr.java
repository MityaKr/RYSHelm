package works.repka.ryshelm.client;

import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import works.repka.ryshelm.logic.Lang;

/** Тексты окна на языке игры: ключи {@code ryshelm.*}, склонения {@code .one/.few/.many} как в семье. */
final class Tr {
    private Tr() {}

    static Lang lang() {
        String code = Minecraft.getInstance().getLanguageManager().getSelected();
        return new Lang(code != null && code.startsWith("ru"), k -> Language.getInstance().getOrDefault(k));
    }

    /** Текст по ключу {@code ryshelm.<key>}. */
    static String tr(String key, Object... args) {
        return lang().t("ryshelm." + key, args);
    }

    /** «3 дня»: число и слово по ключу {@code ryshelm.<key>.one/.few/.many}. */
    static String n(String key, long n, Object... more) {
        return lang().n("ryshelm." + key, n, more);
    }
}
