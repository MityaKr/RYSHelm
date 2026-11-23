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
import works.repka.ryscore.ui.Pending;
import org.jetbrains.annotations.Nullable;
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
    /** Правки окна, которые ждут ответа сервера (ядро 1.15.3): оптимистичное значение, крутилка, откат. */
    public static final Pending PENDING = new Pending();
    /** Сервер отвечает на действия с номером запроса (RYSHelm 0.2.4 и новее); старый сервер не отвечает, ждать нечего. */
    public static boolean acks;
    private record Waiting(java.util.function.Consumer<String> done, long since) {}
    private static final java.util.Map<Integer, Waiting> DONE = new java.util.HashMap<>();

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
        acks = false;
        PENDING.clear();
        DONE.clear();
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
            case "ack" -> ack(t.getInt("rid"), t.contains("err") ? t.getString("err") : null);
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
        acks = t.getBoolean("acks");
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

    /**
     * Действие окна, которое ждёт ответа: место {@code key} сразу показывает {@code optimistic} (или крутилку), ответ
     * сервера снимает ожидание, ошибка (её текст сервер присылает уведомлением) или молчание откатывают к данным
     * сервера. {@code done} получает текст ошибки или {@code null}. Старый сервер без ответов: просто отправить.
     */
    public static void ask(String key, @Nullable Object optimistic, String action, CompoundTag args, @Nullable java.util.function.Consumer<String> done) {
        if (acks) {
            int rid = PENDING.start(key, optimistic);
            args.putInt("rid", rid);
            if (done != null) DONE.put(rid, new Waiting(done, System.currentTimeMillis()));
        }
        HelmNet.action(action, args);
    }

    public static void ask(String key, @Nullable Object optimistic, String action, CompoundTag args) {
        ask(key, optimistic, action, args, null);
    }

    static void ack(int rid, @Nullable String err) {
        PENDING.done(rid);
        Waiting w = DONE.remove(rid);
        if (w != null) w.done.accept(err);
    }

    /** Раз в кадр открытого окна: правки без ответа дольше 6 с откатываются с сообщением. */
    static void expire() {
        long now = System.currentTimeMillis();
        if (!DONE.isEmpty()) DONE.entrySet().removeIf(e -> {
            if (now - e.getValue().since < Pending.TIMEOUT) return false;
            e.getValue().done.accept(Tr.tr("ui.no_answer"));
            return true;
        });
        if (!PENDING.any() || PENDING.expire(now).isEmpty()) return;
        Notice.row(Notice.HELM, "error").icon(Icons.Shape.WARN).title(Tr.tr("ui.no_answer.notice")).bad().show();
    }

    public static void onTarget(String action, java.util.UUID target) {
        CompoundTag a = new CompoundTag();
        a.putUUID("target", target);
        HelmNet.action(action, a);
    }
}
