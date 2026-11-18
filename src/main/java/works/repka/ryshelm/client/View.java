package works.repka.ryshelm.client;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * Данные открытого окна с сервера: последняя присланная вкладка, карточка игрока, подсказка наказания. Окно говорит
 * серверу, что смотрит ({@link #request}), сервер шлёт заново при изменениях.
 */
public final class View {
    public static CompoundTag players = new CompoundTag(), rules = new CompoundTag(), reports = new CompoundTag(), journal = new CompoundTag(),
        roles = new CompoundTag(), settings = new CompoundTag();
    public static @Nullable CompoundTag card, punish;
    /** Растёт с каждым ответом: окно перечитывает свои списки. */
    public static int version;

    private View() {}

    static void accept(CompoundTag t) {
        String tab = t.getString("tab");
        switch (tab) {
            case "players" -> players = t;
            case "rules" -> rules = t.getCompound("rules");
            case "reports" -> reports = t.getCompound("reports");
            case "journal" -> journal = t.getCompound("journal");
            case "roles" -> roles = t.getCompound("roles");
            case "settings" -> settings = t.getCompound("settings");
            default -> {}
        }
        card = t.contains("card") ? t.getCompound("card") : null;
        punish = t.contains("punish") ? t.getCompound("punish") : null;
        version++;
    }

    /** Подписаться на вкладку и, если нужно, на карточку игрока, жалобу, окно наказания и фильтр журнала. */
    static void request(String tab, @Nullable UUID card, @Nullable UUID punish, long report, String who, int days, String cat) {
        CompoundTag a = new CompoundTag();
        a.putString("tab", tab);
        if (card != null) a.putUUID("card", card);
        if (punish != null) a.putUUID("punish", punish);
        if (report > 0) a.putLong("report", report);
        a.putString("who", who);
        a.putInt("days", days);
        a.putString("cat", cat);
        HelmClient.action("view", a);
    }

    static void close() {
        HelmClient.action("close");
    }

    public static void reset() {
        players = new CompoundTag();
        rules = new CompoundTag();
        reports = new CompoundTag();
        journal = new CompoundTag();
        roles = new CompoundTag();
        settings = new CompoundTag();
        card = punish = null;
    }
}
