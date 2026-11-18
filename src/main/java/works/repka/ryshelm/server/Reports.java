package works.repka.ryshelm.server;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import works.repka.ryshelm.HelmPerms;
import works.repka.ryshelm.data.HelmData;
import works.repka.ryshelm.logic.Term;

/**
 * Жалобы (план 5.5, макет 10): кто, на кого, пункт правил, текст, где стоят оба, чья там земля, последние строки чата
 * нарушителя. Модераторам в сети уведомление, если никого нет, жалоба ждёт. Автор узнаёт только, что жалоба рассмотрена.
 */
public final class Reports {
    private Reports() {}

    public static int open() {
        int n = 0;
        for (HelmData.Report r : Helm.data().reports) if (r.state == HelmData.Report.State.NEW) n++;
        return n;
    }

    public static int openAgainst(UUID id) {
        int n = 0;
        for (HelmData.Report r : Helm.data().reports) if (r.state != HelmData.Report.State.CLOSED && id.equals(r.target)) n++;
        return n;
    }

    static String stateLabel(HelmData.Report r) {
        return switch (r.state) {
            case NEW -> "новая";
            case TAKEN -> "взял " + r.takenByName;
            case CLOSED -> "punished".equals(r.outcome) ? "наказан" : "не подтвердилось";
        };
    }

    public static @Nullable String create(ServerPlayer from, UUID target, int rule, String text) {
        MinecraftServer server = from.server;
        HelmData data = Helm.data();
        if (from.getUUID().equals(target)) return "На себя пожаловаться нельзя";
        HelmData.PlayerInfo me = data.player(from.getUUID(), from.getGameProfile().getName());
        long now = System.currentTimeMillis();
        long pause = Helm.settings().reportCooldown * Term.SECOND;
        if (now - me.lastReport < pause) return "Жалоба не чаще раза в " + Term.label(pause) + ", ещё " + Term.left(pause - (now - me.lastReport));
        String body = text == null ? "" : text.strip();
        if (body.isEmpty() && rule <= 0) return "Напишите, что случилось";
        if (body.length() > 600) body = body.substring(0, 600);
        ServerPlayer t = server.getPlayerList().getPlayer(target);
        HelmData.PlayerInfo info = data.players.get(target);
        if (t == null && info == null) return "Нет такого игрока";
        HelmData.Report r = new HelmData.Report();
        r.id = data.id();
        r.from = from.getUUID();
        r.fromName = from.getGameProfile().getName();
        r.target = target;
        r.targetName = t != null ? t.getGameProfile().getName() : info.name;
        r.rule = Math.max(0, rule);
        r.text = body;
        r.time = now;
        r.fromPos = Helm.spot(from);
        r.targetPos = t != null ? Helm.spot(t) : info.lastPos;
        if (t != null) r.land = Views.land(t);
        r.chat.addAll(Chat.last(target, Helm.settings().reportChatLines));
        data.reports.add(r);
        me.lastReport = now;
        Helm.dirty();
        Helm.log("report", from.getUUID(), r.fromName, target, r.targetName, "", (r.rule > 0 ? "п. " + r.rule + " · " : "") + "новая#" + r.id);
        Sync.staffNotice(server, HelmPerms.REPORTS, "report_new", "card", "FLAG", "Новая жалоба",
            r.fromName + " → " + r.targetName + (r.rule > 0 ? " · п. " + r.rule : ""));
        Sync.notice(from, "report_sent", "row", "FLAG", "Жалоба отправлена", "ответ придёт уведомлением", false);
        Sync.staffChanged();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) if (HelmPerms.REPORTS.test(p)) Sync.hello(p);
        return null;
    }

    public static @Nullable String take(ServerPlayer mod, long id) {
        HelmData.Report r = Helm.data().report(id);
        if (r == null) return "Нет такой жалобы";
        if (r.state == HelmData.Report.State.CLOSED) return "Жалоба уже закрыта";
        r.state = HelmData.Report.State.TAKEN;
        r.takenBy = mod.getUUID();
        r.takenByName = mod.getGameProfile().getName();
        Helm.dirty();
        Helm.log("report_take", mod, r.target, r.targetName, "", "от " + r.fromName);
        Sync.staffChanged();
        for (ServerPlayer p : mod.server.getPlayerList().getPlayers()) if (HelmPerms.REPORTS.test(p)) Sync.hello(p);
        return null;
    }

    /** @param outcome {@code punished} или {@code rejected} */
    public static @Nullable String close(ServerPlayer mod, long id, String outcome) {
        HelmData.Report r = Helm.data().report(id);
        if (r == null) return "Нет такой жалобы";
        if (r.state == HelmData.Report.State.CLOSED) return "Жалоба уже закрыта";
        r.state = HelmData.Report.State.CLOSED;
        r.outcome = "punished".equals(outcome) ? "punished" : "rejected";
        r.closedAt = System.currentTimeMillis();
        if (r.takenBy == null) {
            r.takenBy = mod.getUUID();
            r.takenByName = mod.getGameProfile().getName();
        }
        Helm.dirty();
        Helm.log("report_close", mod, r.target, r.targetName, "", stateLabel(r) + " · от " + r.fromName);
        Helm.mail(r.from, "report|Жалоба на " + r.targetName + " от " + Sync.date(r.time));
        Sync.staffChanged();
        for (ServerPlayer p : mod.server.getPlayerList().getPlayers()) if (HelmPerms.REPORTS.test(p)) Sync.hello(p);
        return null;
    }

    /** Вкладка «Жалобы»: список и выбранная жалоба. */
    static CompoundTag view(ServerPlayer viewer, long selected) {
        HelmData data = Helm.data();
        CompoundTag t = new CompoundTag();
        ListTag list = new ListTag();
        int closed = 0;
        HelmData.Report pick = null;
        for (int i = data.reports.size() - 1; i >= 0; i--) {
            HelmData.Report r = data.reports.get(i);
            if (r.state == HelmData.Report.State.CLOSED && ++closed > 100) continue;
            CompoundTag x = new CompoundTag();
            x.putLong("id", r.id);
            x.putString("from", r.fromName);
            x.putString("target", r.targetName);
            x.putInt("rule", r.rule);
            x.putString("text", r.text);
            x.putLong("t", r.time);
            x.putString("state", r.state.name().toLowerCase());
            x.putString("by", r.takenByName);
            x.putString("label", stateLabel(r));
            list.add(x);
            if (r.id == selected) pick = r;
        }
        t.put("list", list);
        if (pick == null) {
            for (int i = data.reports.size() - 1; i >= 0 && pick == null; i--) if (data.reports.get(i).state != HelmData.Report.State.CLOSED) pick = data.reports.get(i);
        }
        if (pick != null) t.put("detail", detail(viewer, pick));
        return t;
    }

    static CompoundTag detail(ServerPlayer viewer, HelmData.Report r) {
        CompoundTag d = new CompoundTag();
        d.putLong("id", r.id);
        d.putUUID("fromId", r.from);
        d.putUUID("targetId", r.target);
        d.putString("from", r.fromName);
        d.putString("target", r.targetName);
        d.putInt("rule", r.rule);
        d.putString("ruleTitle", r.rule > 0 && Helm.rules().rule(r.rule) != null ? Helm.rules().rule(r.rule).title() : "");
        d.putString("text", r.text);
        d.putLong("t", r.time);
        d.putString("state", r.state.name().toLowerCase());
        d.putString("by", r.takenByName);
        d.putBoolean("mine", viewer.getUUID().equals(r.takenBy));
        d.putString("land", r.land);
        if (r.fromPos != null) spot(d, "fromPos", r.fromPos);
        if (r.targetPos != null) spot(d, "targetPos", r.targetPos);
        ListTag chat = new ListTag();
        for (HelmData.ChatLine l : r.chat) {
            CompoundTag c = new CompoundTag();
            c.putLong("t", l.time());
            c.putString("text", l.text());
            chat.add(c);
        }
        d.put("chat", chat);
        ListTag past = new ListTag();
        for (HelmData.Punishment p : Helm.data().of(r.target)) {
            CompoundTag x = new CompoundTag();
            x.putString("kind", p.kind.id());
            x.putLong("t", p.created);
            x.putString("text", Views.cap(p.kind.noun()) + (p.kind.timed() ? " " + (p.expires == 0 ? "навсегда" : Term.label(p.expires - p.created)) : "") + (p.rule > 0 ? " · п. " + p.rule : ""));
            past.add(x);
            if (past.size() >= 6) break;
        }
        for (HelmData.Report o : Helm.data().reports) {
            if (o == r || !r.target.equals(o.target) || o.state == HelmData.Report.State.CLOSED || past.size() >= 8) continue;
            CompoundTag x = new CompoundTag();
            x.putString("kind", "report");
            x.putLong("t", o.time);
            x.putString("text", "ещё жалоба · " + o.fromName);
            past.add(x);
        }
        d.put("past", past);
        return d;
    }

    private static void spot(CompoundTag d, String key, HelmData.Spot s) {
        CompoundTag t = new CompoundTag();
        t.putString("dim", s.dim);
        t.putDouble("x", s.x);
        t.putDouble("y", s.y);
        t.putDouble("z", s.z);
        t.putString("coords", s.coords());
        d.put(key, t);
    }
}
