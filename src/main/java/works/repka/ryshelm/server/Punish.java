package works.repka.ryshelm.server;

import com.mojang.authlib.GameProfile;
import java.util.Date;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.UserBanList;
import net.minecraft.server.players.UserBanListEntry;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.perm.Perms;
import works.repka.ryscore.perm.Roles;
import works.repka.ryshelm.HelmPerms;
import works.repka.ryshelm.data.HelmData;
import works.repka.ryshelm.logic.Kind;
import works.repka.ryshelm.logic.Lang;
import works.repka.ryshelm.logic.Term;

/**
 * Наказания (план 5.4): предупреждение, заглушить, выгнать, бан со сроком. Бан ложится и в список игры
 * {@code banned-players.json} с полем {@code expires}: его видит {@code /pardon}, он переживает удаление мода, игра сама
 * снимает просроченный. Снять наказание может тот, кто дал, и оператор.
 */
public final class Punish {
    private Punish() {}

    /** Узел права на вид наказания. */
    public static Perms.Node node(Kind kind, long millis) {
        return switch (kind) {
            case WARN -> HelmPerms.WARN;
            case MUTE -> HelmPerms.MUTE;
            case KICK -> HelmPerms.KICK;
            case BAN -> millis == Term.FOREVER ? HelmPerms.BAN : HelmPerms.BAN_TEMP;
        };
    }

    /**
     * Выдать наказание.
     * @param actor  модератор или null (консоль)
     * @param millis срок для заглушить и бана, {@link Term#FOREVER} навсегда
     * @return null при успехе, иначе причина отказа для персонала
     */
    public static @Nullable String give(MinecraftServer server, @Nullable ServerPlayer actor, UUID target, String targetName, Kind kind, long millis,
                                        int rule, String reason, boolean shown) {
        String why = allowed(server, actor, target, kind, millis);
        if (why != null) return why;
        ServerPlayer online = server.getPlayerList().getPlayer(target);
        if (kind == Kind.KICK && online == null) return Texts.t(actor, "msg.offline", targetName);
        long now = System.currentTimeMillis();
        HelmData data = Helm.data();
        HelmData.Punishment p = new HelmData.Punishment();
        p.id = data.id();
        p.kind = kind;
        p.target = target;
        p.targetName = targetName;
        p.by = actor == null ? null : actor.getUUID();
        p.byName = actor == null ? "консоль" : actor.getGameProfile().getName();
        p.rule = Math.max(0, rule);
        p.reason = reason == null ? "" : reason.strip();
        p.shown = shown;
        p.created = now;
        p.expires = kind.timed() && millis != Term.FOREVER ? now + millis : 0;
        // повторное заглушение заменяет действующее: в истории остаются оба, снятым помечается старое
        if (kind.timed()) {
            HelmData.Punishment old = data.active(target, kind, now);
            if (old != null) {
                old.revoked = now;
                old.revokedBy = p.byName;
            }
        }
        data.punishments.add(p);
        Helm.dirty();
        String term = kind.timed() ? Term.onLabel(millis) : "";
        Helm.log(kind.id(), actor, target, targetName, term, note(p));
        // игроку на его языке; вне сети на языке модератора: отложенное уведомление хранится готовой строкой
        Lang lang = Texts.lang(online != null ? online : actor);
        String told = told(lang, p);
        switch (kind) {
            case WARN -> Helm.mail(target, "warn|" + told);
            case MUTE -> Helm.mail(target, "mute|" + told);
            case KICK -> online.connection.disconnect(Texts.c(online, "msg.kicked").append(shown && !p.reason.isEmpty() ? ": " + p.reason : "")
                .append(p.rule > 0 ? "\n" + Helm.rules().ref(lang, p.rule) : ""));
            case BAN -> {
                UserBanList bans = server.getPlayerList().getBans();
                GameProfile gp = new GameProfile(target, targetName);
                bans.add(new UserBanListEntry(gp, new Date(now), p.byName, p.expires == 0 ? null : new Date(p.expires), shown ? banReason(lang, p) : lang.t("ryshelm.msg.ban")));
                if (online != null) online.connection.disconnect(banText(online, p));
            }
        }
        Sync.staffChanged();
        return null;
    }

    /** Кто может выдать: право на вид, потолок срока, нельзя наказать себя и равного или старшего по роли. */
    public static @Nullable String allowed(MinecraftServer server, @Nullable ServerPlayer actor, UUID target, Kind kind, long millis) {
        if (actor == null) return null;
        if (actor.getUUID().equals(target)) return Texts.t(actor, "msg.punish.self");
        boolean operator = Roles.atLeast(actor, Roles.OPERATOR);
        if (!node(kind, millis).test(actor)) return Texts.t(actor, "msg.no_perm", node(kind, millis).id());
        if (!operator && Roles.rank(Roles.of(server, target)) >= Roles.rank(Roles.of(actor))) return Texts.t(actor, "msg.punish.staff");
        return Helm.settings().caps().check(Texts.lang(actor), operator, HelmPerms.BAN.test(actor), kind, millis);
    }

    /** «п. 3 · мат в общем чате» для журнала. */
    static String note(HelmData.Punishment p) {
        String r = p.rule > 0 ? "п. " + p.rule : "";
        if (!p.reason.isEmpty()) r = r.isEmpty() ? p.reason : r + " · " + p.reason;
        return r;
    }

    /** Что видит игрок: срок, пункт правил, причина (если «Игрок видит причину»). */
    static String told(Lang lang, HelmData.Punishment p) {
        StringBuilder b = new StringBuilder();
        if (p.kind.timed()) b.append(until(lang, p));
        if (p.shown) {
            if (p.rule > 0) {
                if (!b.isEmpty()) b.append(" · ");
                b.append(Helm.rules().ref(lang, p.rule));
            }
            if (!p.reason.isEmpty()) {
                if (!b.isEmpty()) b.append(" · ");
                b.append(p.reason);
            }
        }
        return b.toString();
    }

    /** «навсегда» или «до 23.11 14:05». */
    static String until(Lang lang, HelmData.Punishment p) {
        return p.expires == 0 ? lang.t("ryshelm.term.forever") : lang.t("ryshelm.msg.until", Sync.dateTime(p.expires));
    }

    static String banReason(Lang lang, HelmData.Punishment p) {
        String r = told(lang, p);
        return r.isEmpty() ? lang.t("ryshelm.msg.ban") : r;
    }

    /** Экран отключения забаненного: ключ с запасным текстом, его поймёт и клиент без мода. */
    static Component banText(ServerPlayer target, HelmData.Punishment p) {
        Lang lang = Texts.lang(target);
        var c = Texts.c(target, "msg.banned", until(lang, p));
        if (p.shown && !p.reason.isEmpty()) c.append("\n").append(Texts.c(target, "msg.banned.reason", p.reason));
        if (p.shown && p.rule > 0) c.append("\n" + Helm.rules().ref(lang, p.rule));
        return c;
    }

    /** Снять наказание по номеру: тот, кто дал, или оператор. */
    public static @Nullable String revoke(MinecraftServer server, @Nullable ServerPlayer actor, long id) {
        HelmData.Punishment p = Helm.data().punishment(id);
        if (p == null) return Texts.t(actor, "msg.punish.no_such");
        if (!p.active(System.currentTimeMillis())) return Texts.t(actor, "msg.punish.inactive");
        if (actor != null && !Roles.atLeast(actor, Roles.OPERATOR) && !actor.getUUID().equals(p.by)) return Texts.t(actor, "msg.punish.revoke_who");
        p.revoked = System.currentTimeMillis();
        p.revokedBy = actor == null ? "консоль" : actor.getGameProfile().getName();
        Helm.dirty();
        if (p.kind == Kind.BAN) server.getPlayerList().getBans().remove(new GameProfile(p.target, p.targetName));
        if (p.kind == Kind.MUTE) Helm.mail(p.target, "unmute|");
        Helm.log(p.kind == Kind.BAN ? "unban" : "unmute", actor, p.target, p.targetName, "", "#" + p.id);
        Sync.staffChanged();
        return null;
    }

    /** Снять действующее наказание вида у игрока. */
    public static @Nullable String lift(MinecraftServer server, @Nullable ServerPlayer actor, UUID target, Kind kind) {
        HelmData.Punishment p = Helm.data().active(target, kind, System.currentTimeMillis());
        if (p == null) {
            // бан, данный командой игры или другим модом, снимается и так: список игры главный
            if (kind == Kind.BAN) {
                GameProfile gp = new GameProfile(target, Helm.data().name(target));
                if (server.getPlayerList().getBans().isBanned(gp)) {
                    if (actor != null && !HelmPerms.BAN_TEMP.test(actor)) return Texts.t(actor, "msg.punish.no_unban");
                    server.getPlayerList().getBans().remove(gp);
                    Helm.log("unban", actor, target, gp.getName(), "", "бан из списка игры");
                    return null;
                }
            }
            return Texts.t(actor, "msg.punish.none_active");
        }
        return revoke(server, actor, p.id);
    }

    /** Действующее заглушение игрока или null. */
    public static @Nullable HelmData.Punishment muted(UUID id) {
        return Helm.data().active(id, Kind.MUTE, System.currentTimeMillis());
    }
}
