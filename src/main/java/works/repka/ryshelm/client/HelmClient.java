package works.repka.ryshelm.client;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import works.repka.ryscore.action.Actions;
import works.repka.ryscore.perm.Roles;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Notice;
import works.repka.ryshelm.network.HelmNet;

/**
 * Состояние RYSHelm на клиенте: роль и права с сервера, флаги (полёт, бессмертие, наблюдение, чат персонала), число
 * жалоб, правила. Без мода на сервере всё пусто: роль решает уровень оператора, окно показывает только «Правила».
 */
public final class HelmClient {
    public static boolean present;
    public static String role = Roles.PLAYER;
    public static final Set<String> PERMS = new HashSet<>();
    public static String server = "";
    public static boolean fly, god, spectating, staffChat, frozen, rulesPending, newbie, ftbEssentials;
    public static long mutedUntil;
    public static int reports;
    /** Наборы: имя и сколько ждать, мс (-1 больше нельзя). */
    public static final java.util.Map<String, Long> KITS = new java.util.LinkedHashMap<>();
    private static boolean rulesShown;
    /** Автотест: роль и права не берутся из приветствия (снимок «глазами игрока», макет 14). */
    public static boolean pinned;
    /** Последние уведомления с сервера (id): для автотестов и двух клиентов. */
    public static final java.util.List<String> RECENT = new java.util.ArrayList<>();

    private HelmClient() {}

    public static boolean can(String node) {
        return PERMS.contains(node);
    }

    public static boolean staff() {
        return Roles.rank(role) >= Roles.rank(Roles.MODERATOR);
    }

    public static void reset() {
        present = false;
        role = Roles.PLAYER;
        PERMS.clear();
        server = "";
        fly = god = spectating = staffChat = frozen = rulesPending = newbie = ftbEssentials = false;
        mutedUntil = 0;
        reports = 0;
        KITS.clear();
        rulesShown = false;
        Roles.setClient(null);
        Roles.setModerationActive(false);
    }

    public static void accept(CompoundTag t) {
        Minecraft.getInstance().execute(() -> handle(t));
    }

    static void handle(CompoundTag t) {
        switch (t.getString("type")) {
            case "hello" -> hello(t);
            case "notice" -> notice(t);
            case "view" -> View.accept(t);
            case "open" -> ServerScreen.open(null);
            default -> {}
        }
    }

    static void hello(CompoundTag t) {
        present = true;
        if (!pinned) {
            role = t.getString("role");
            PERMS.clear();
            for (Tag x : t.getList("perms", Tag.TAG_STRING)) PERMS.add(x.getAsString());
        }
        server = t.getString("server");
        fly = t.getBoolean("fly");
        god = t.getBoolean("god");
        spectating = t.getBoolean("spectating");
        staffChat = t.getBoolean("staffChat");
        frozen = t.getBoolean("frozen");
        mutedUntil = t.getLong("mutedUntil");
        reports = t.getInt("reports");
        newbie = t.getBoolean("newbie");
        ftbEssentials = t.getBoolean("ftbEssentials");
        KITS.clear();
        for (Tag x : t.getList("kits", Tag.TAG_COMPOUND)) KITS.put(((CompoundTag) x).getString("name"), ((CompoundTag) x).getLong("left"));
        boolean pending = t.getBoolean("rulesPending");
        Roles.setClient(role);
        // ядро 1.15: кольцо «Модерация» у оператора видно, только пока идёт модерация
        Roles.setModerationActive(t.getBoolean("moderation"));
        Actions.changed();
        if (pending && !rulesShown) {
            rulesShown = true;
            rulesPending = true;
            RulesScreen.request();
        }
        if (!pending) rulesShown = false;
        rulesPending = pending;
    }

    static void notice(CompoundTag t) {
        RECENT.add(t.getString("id"));
        if (RECENT.size() > 50) RECENT.remove(0);
        String kind = t.getString("kind");
        Notice n = "card".equals(kind) ? Notice.card(Notice.HELM, t.getString("id")) : Notice.row(Notice.HELM, t.getString("id"));
        n.icon(shape(t.getString("icon"))).title(t.getString("title"));
        if (!t.getString("line").isEmpty()) n.lines(t.getString("line"));
        if (t.getBoolean("bad")) n.bad();
        switch (t.getString("id")) {
            case "report_new", "reports" -> n.key(ClientEntry::keyName).action(() -> ServerScreen.open("reports"));
            case "rules" -> n.key(ClientEntry::keyName).action(RulesScreen::request);
            default -> {}
        }
        n.show();
    }

    static Icons.Shape shape(String name) {
        try {
            return Icons.Shape.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Icons.Shape.WARN;
        }
    }

    public static void action(String action) {
        HelmNet.action(action, new CompoundTag());
    }

    public static void action(String action, CompoundTag args) {
        HelmNet.action(action, args);
    }

    public static void onTarget(String action, java.util.UUID target) {
        CompoundTag a = new CompoundTag();
        a.putUUID("target", target);
        HelmNet.action(action, a);
    }
}
