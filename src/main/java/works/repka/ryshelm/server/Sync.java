package works.repka.ryshelm.server;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import works.repka.ryscore.perm.Perms;
import works.repka.ryscore.perm.Roles;
import works.repka.ryshelm.HelmPerms;
import works.repka.ryshelm.data.HelmData;
import works.repka.ryshelm.network.HelmNet;

/**
 * Что уходит клиентам: приветствие с ролью и правами ({@code hello}), уведомления, данные открытого окна. Окно
 * подписывается на вкладку ({@code view}), сервер шлёт её заново, когда что-то поменялось, и раз в 3 секунды (места игроков).
 */
public final class Sync {
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM").withZone(ZONE);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZONE);

    /** Открытые окна: игрок → что он смотрит. */
    private static final Map<UUID, CompoundTag> WATCHING = new HashMap<>();
    private static boolean changed, journalChanged;
    private static int seconds;

    private Sync() {}

    public static String date(long millis) {
        return DATE.format(Instant.ofEpochMilli(millis));
    }

    public static String dateTime(long millis) {
        return DATE_TIME.format(Instant.ofEpochMilli(millis));
    }

    /** «minecraft:overworld 1 402 · 58 · −418» для журнала: измерение переводит клиент. */
    public static String where(ServerPlayer p) {
        return "@" + p.level().dimension().location() + " " + Helm.spot(p).coords();
    }

    // ---- приветствие ----

    /** Роль, права и состояние игрока: при входе и после каждой правки ролей. */
    public static void hello(ServerPlayer p) {
        CompoundTag t = new CompoundTag();
        t.putString("type", "hello");
        t.putString("role", Roles.of(p));
        ListTag perms = new ListTag();
        for (Perms.Node n : Perms.nodes()) if (n.test(p)) perms.add(StringTag.valueOf(n.id()));
        t.put("perms", perms);
        t.putString("server", serverName(p.server));
        t.putBoolean("fly", Staff.flying(p));
        t.putBoolean("god", Staff.god(p));
        t.putBoolean("spectating", Staff.spectating(p.getUUID()));
        t.putBoolean("staffChat", Chat.staffChatOn(p.getUUID()));
        t.putBoolean("frozen", Staff.frozen(p.getUUID()));
        HelmData.Punishment mute = Punish.muted(p.getUUID());
        if (mute != null) t.putLong("mutedUntil", mute.expires == 0 ? Long.MAX_VALUE : mute.expires);
        t.putInt("reports", HelmPerms.REPORTS.test(p) ? Reports.open() : 0);
        t.putBoolean("moderation", Helm.data().moderationActive());
        t.putBoolean("rulesPending", Gate.pending(p.getUUID()));
        t.putBoolean("newbie", Gate.newbie(p));
        t.putBoolean("ftbEssentials", Commands.ftbEssentials());
        // 0.2.4: сервер отвечает на действия с номером запроса (ack), окно может ждать ответа
        t.putBoolean("acks", true);
        if (HelmPerms.KIT.test(p)) {
            ListTag kits = new ListTag();
            for (Kits.Kit k : Kits.all()) {
                CompoundTag x = new CompoundTag();
                x.putString("name", k.name);
                long left = Kits.left(p, k);
                x.putLong("left", left == Long.MAX_VALUE ? -1 : left);
                kits.add(x);
            }
            t.put("kits", kits);
        }
        HelmNet.send(p, t);
    }

    public static String serverName(MinecraftServer server) {
        String name = Helm.settings().serverName;
        if (name != null && !name.isBlank()) return name.strip();
        String motd = server.getMotd() == null ? "" : server.getMotd().replaceAll("§.", "").split("\n")[0].strip();
        if (motd.isEmpty() || motd.equals("A Minecraft Server")) motd = server.getWorldData().getLevelName();
        return motd.length() > 24 ? motd.substring(0, 24) : motd;
    }

    /** Всем в сети заново: роли или таблица прав поменялись. */
    public static void helloAll(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) hello(p);
        changed = true;
    }

    // ---- уведомления ----

    /**
     * Уведомление семьи на клиенте ({@code Notice.HELM}).
     * @param kind {@code card} или {@code row}
     * @param icon имя значка {@code Icons.Shape}
     */
    public static void notice(ServerPlayer p, String id, String kind, String icon, String title, String line, boolean bad) {
        CompoundTag t = new CompoundTag();
        t.putString("type", "notice");
        t.putString("id", id);
        t.putString("kind", kind);
        t.putString("icon", icon);
        t.putString("title", title);
        t.putString("line", line);
        t.putBoolean("bad", bad);
        HelmNet.send(p, t);
    }

    /** Отложенное уведомление ({@link Helm#mail}): {@code вид|текст}. */
    static void mail(ServerPlayer p, String mail) {
        int bar = mail.indexOf('|');
        String kind = bar < 0 ? "info" : mail.substring(0, bar), text = bar < 0 ? mail : mail.substring(bar + 1);
        switch (kind) {
            case "warn" -> notice(p, "warn", "card", "WARN", Texts.t(p, "msg.mail.warn"), text, true);
            case "mute" -> {
                notice(p, "mute", "card", "MUTE", Texts.t(p, "msg.mail.mute"), text, true);
                hello(p);
            }
            case "unmute" -> {
                notice(p, "mute", "row", "MUTE", Texts.t(p, "msg.mail.unmute"), "", false);
                hello(p);
            }
            case "report" -> notice(p, "report_done", "row", "FLAG", Texts.t(p, "msg.mail.report"), text, false);
            default -> notice(p, "info", "row", "INFO", text, "", false);
        }
    }

    /** Всем модераторам с правом в сети. */
    public static void staffNotice(MinecraftServer server, Perms.Node node, String id, String kind, String icon, String title, String line) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) if (node.test(p)) notice(p, id, kind, icon, title, line, false);
    }

    // ---- окно ----

    /** Окно открыто или сменило вкладку: прислать сразу и дальше слать изменения. */
    static void watch(ServerPlayer p, CompoundTag view) {
        WATCHING.put(p.getUUID(), view.copy());
        send(p, view);
    }

    /** Действие игрока с окна выполнено: его вкладку заново сейчас, не дожидаясь рассылки раз в секунду. */
    static void refresh(ServerPlayer p) {
        CompoundTag view = WATCHING.get(p.getUUID());
        if (view != null) send(p, view);
    }

    /** Ответ на действие с номером запроса {@code rid}: {@code err == null} значит сделано. */
    static void ack(ServerPlayer p, int rid, @org.jetbrains.annotations.Nullable String err) {
        CompoundTag t = new CompoundTag();
        t.putString("type", "ack");
        t.putInt("rid", rid);
        if (err != null) t.putString("err", err);
        HelmNet.send(p, t);
    }

    static void unwatch(ServerPlayer p) {
        WATCHING.remove(p.getUUID());
    }

    static void logout(ServerPlayer p) {
        WATCHING.remove(p.getUUID());
    }

    private static void send(ServerPlayer p, CompoundTag view) {
        CompoundTag t = Views.build(p, view);
        t.putString("type", "view");
        HelmNet.send(p, t);
    }

    /** Наказания, роли, жалобы поменялись: открытым окнам заново. */
    public static void staffChanged() {
        changed = true;
    }

    public static void journalChanged() {
        journalChanged = true;
    }

    public static void broadcastRoster() {
        changed = true;
    }

    static void tick(MinecraftServer server) {
        seconds++;
        boolean periodic = seconds % 3 == 0;
        if (!changed && !journalChanged && !periodic) return;
        Iterator<Map.Entry<UUID, CompoundTag>> it = WATCHING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, CompoundTag> e = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) {
                it.remove();
                continue;
            }
            String tab = e.getValue().getString("tab");
            boolean live = tab.equals("players") || e.getValue().contains("card");
            if (changed || (journalChanged && tab.equals("journal")) || (periodic && live)) send(p, e.getValue());
        }
        changed = false;
        journalChanged = false;
    }
}
