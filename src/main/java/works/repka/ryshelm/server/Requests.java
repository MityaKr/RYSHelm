package works.repka.ryshelm.server;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.perm.Perms;
import works.repka.ryscore.perm.Roles;
import works.repka.ryshelm.HelmPerms;
import works.repka.ryshelm.data.HelmData;
import works.repka.ryshelm.logic.Kind;
import works.repka.ryshelm.logic.Term;

/** Действия из окна и колеса. Каждое проверяет право здесь: клиент только просит. */
public final class Requests {
    private Requests() {}

    public static void handle(ServerPlayer p, String action, CompoundTag a) {
        if (!Helm.running()) return;
        MinecraftServer server = p.server;
        try {
            String err = switch (action) {
                case "view" -> {
                    Sync.watch(p, a);
                    yield null;
                }
                case "close" -> {
                    Sync.unwatch(p);
                    yield null;
                }
                case "punish" -> punish(p, a);
                case "revoke" -> Punish.revoke(server, p, a.getLong("id"));
                case "note" -> note(p, a);
                case "tp" -> need(p, HelmPerms.TP, () -> online(server, a, t -> Staff.tp(p, t)));
                case "tphere" -> need(p, HelmPerms.TP, () -> online(server, a, t -> Staff.tphere(p, t)));
                case "tplast" -> need(p, HelmPerms.TP, () -> Staff.tpLast(p, a.getUUID("target")));
                case "tp_spot" -> need(p, HelmPerms.TP, () -> tpSpot(p, a));
                case "spectate" -> need(p, HelmPerms.SPECTATE, () -> online(server, a, t -> Staff.spectate(p, t)));
                case "spectate_return" -> Staff.unspectate(p);
                case "freeze" -> need(p, HelmPerms.FREEZE, () -> online(server, a, t -> ok(p, Staff.freeze(p, t))));
                case "invsee" -> need(p, HelmPerms.INVSEE, () -> online(server, a, t -> {
                    Staff.invsee(p, t);
                    return null;
                }));
                case "ender" -> {
                    if (!a.hasUUID("target") || a.getUUID("target").equals(p.getUUID())) yield need(p, HelmPerms.ENDER, () -> {
                        Staff.enderChest(p, p);
                        return null;
                    });
                    yield need(p, HelmPerms.ENDER_OTHER, () -> online(server, a, t -> {
                        Staff.enderChest(p, t);
                        return null;
                    }));
                }
                case "trash" -> need(p, HelmPerms.TRASH, () -> {
                    Staff.trash(p);
                    return null;
                });
                case "kickme" -> need(p, HelmPerms.KICKME, () -> {
                    p.connection.disconnect(Component.literal("Вы вышли с сервера"));
                    return null;
                });
                case "move" -> need(p, HelmPerms.MOVE, () -> Staff.move(p, a.getUUID("target"), Helm.data().name(a.getUUID("target"))));
                case "cheat" -> need(p, HelmPerms.CHEAT, () -> cheat(p, a));
                case "moderator" -> need(p, HelmPerms.ADMIN, () -> moderator(p, a.getUUID("target"), a.getString("name"), a.getBoolean("on")));
                case "perm" -> need(p, HelmPerms.ADMIN, () -> perm(p, a));
                case "caps" -> need(p, HelmPerms.ADMIN, () -> caps(p, a));
                case "settings" -> need(p, HelmPerms.ADMIN, () -> settings(p, a));
                case "report" -> need(p, HelmPerms.REPORT, () -> Reports.create(p, a.getUUID("target"), a.getInt("rule"), a.getString("text")));
                case "report_take" -> need(p, HelmPerms.REPORTS, () -> Reports.take(p, a.getLong("id")));
                case "report_close" -> need(p, HelmPerms.REPORTS, () -> Reports.close(p, a.getLong("id"), a.getString("outcome")));
                case "rules_accept" -> Gate.accept(p);
                case "rules_quit" -> {
                    p.connection.disconnect(Component.literal("Правила сервера не приняты"));
                    yield null;
                }
                case "staffchat" -> need(p, HelmPerms.STAFF_CHAT, () -> {
                    Chat.toggleStaffChat(p);
                    return null;
                });
                default -> null;
            };
            if (err != null) Sync.notice(p, "error", "row", "WARN", err, "", true);
        } catch (RuntimeException e) {
            works.repka.ryshelm.RYSHelm.LOG.error("[ryshelm] действие {} от {} упало", action, p.getGameProfile().getName(), e);
            Sync.notice(p, "error", "row", "WARN", "Не получилось: " + e.getMessage(), "", true);
        }
    }

    interface Body {
        @Nullable String run();
    }

    interface OnTarget {
        @Nullable String run(ServerPlayer target);
    }

    static @Nullable String need(ServerPlayer p, Perms.Node node, Body body) {
        if (!node.test(p)) return "Нет права: " + node.id();
        return body.run();
    }

    static @Nullable String online(MinecraftServer server, CompoundTag a, OnTarget body) {
        if (!a.hasUUID("target")) return "Игрок не выбран";
        ServerPlayer t = server.getPlayerList().getPlayer(a.getUUID("target"));
        if (t == null) return Helm.data().name(a.getUUID("target")) + " не в сети";
        return body.run(t);
    }

    static @Nullable String ok(ServerPlayer p, String text) {
        Sync.notice(p, "done", "row", "INFO", text, "", false);
        return null;
    }

    /** «Телепорт туда» из жалобы: место, где стоял нарушитель. */
    static @Nullable String tpSpot(ServerPlayer p, CompoundTag a) {
        net.minecraft.server.level.ServerLevel level = Staff.level(p.server, a.getString("dim"));
        if (level == null) return "Измерение не загружено: " + a.getString("dim");
        var r = works.repka.ryscore.travel.TravelApi.get().teleport(p, works.repka.ryscore.travel.TravelApi.Trip
            .to(works.repka.ryscore.travel.TravelApi.Spot.of(level, a.getDouble("x"), a.getDouble("y"), a.getDouble("z")))
            .reason(works.repka.ryscore.travel.TravelApi.Reason.STAFF).skipCooldown().skipWarmup().skipClaims().build());
        if (r.status() == works.repka.ryscore.travel.TravelApi.Status.DENIED) return r.message().getString();
        Helm.log("tp", p, null, "", "", "@" + a.getString("dim") + " " + HelmData.Spot.of(a.getString("dim"), a.getDouble("x"), a.getDouble("y"), a.getDouble("z")).coords());
        return null;
    }

    static @Nullable String punish(ServerPlayer p, CompoundTag a) {
        Kind kind = Kind.parse(a.getString("kind"));
        if (kind == null || !a.hasUUID("target")) return "Не понял наказание";
        UUID target = a.getUUID("target");
        long millis = kind.timed() ? a.getLong("term") : 0;
        if (kind.timed() && millis <= 0) return "Нужен срок";
        String err = Punish.give(p.server, p, target, Helm.data().name(target), kind, millis, a.getInt("rule"), a.getString("reason"), a.getBoolean("shown"));
        if (err == null) {
            String what = switch (kind) {
                case WARN -> "предупреждён";
                case MUTE -> "заглушён " + Term.onLabel(millis);
                case KICK -> "выгнан";
                case BAN -> "забанен " + Term.onLabel(millis);
            };
            ok(p, Helm.data().name(target) + " " + what);
        }
        return err;
    }

    static @Nullable String note(ServerPlayer p, CompoundTag a) {
        if (!HelmPerms.NOTE.test(p)) return "Нет права: " + HelmPerms.NOTE.id();
        String text = a.getString("text").strip();
        if (text.isEmpty() || !a.hasUUID("target")) return "Пустая заметка";
        if (text.length() > 500) text = text.substring(0, 500);
        HelmData.Note n = new HelmData.Note();
        n.id = Helm.data().id();
        n.target = a.getUUID("target");
        n.by = p.getUUID();
        n.byName = p.getGameProfile().getName();
        n.text = text;
        n.time = System.currentTimeMillis();
        Helm.data().notes.add(n);
        Helm.dirty();
        Helm.log("note", p, n.target, Helm.data().name(n.target), "", text.length() > 60 ? text.substring(0, 60) + "…" : text);
        Sync.staffChanged();
        return null;
    }

    static @Nullable String cheat(ServerPlayer p, CompoundTag a) {
        ServerPlayer t = a.hasUUID("target") ? p.server.getPlayerList().getPlayer(a.getUUID("target")) : p;
        if (t == null) return "Игрок не в сети";
        String done = switch (a.getString("what")) {
            case "heal" -> Staff.heal(p, t);
            case "feed" -> Staff.feed(p, t);
            case "extinguish" -> Staff.extinguish(p, t);
            case "fly" -> Staff.fly(p, t);
            case "god" -> Staff.god(p, t);
            case "speed" -> Staff.speed(p, t, a.contains("value") ? a.getFloat("value") : 1f);
            default -> null;
        };
        if (done == null) return "Не понял действие";
        if (t != p) ok(p, done);
        return null;
    }

    /** Назначить или снять модератора (окно «Роли», команда). */
    public static @Nullable String moderator(@Nullable ServerPlayer actor, UUID target, String name, boolean on) {
        MinecraftServer server = Helm.server();
        if (server == null) return "Сервер не готов";
        HelmData data = Helm.data();
        String n = name == null || name.isBlank() ? data.name(target) : name;
        if (on) {
            if (Roles.opLevel(server, target, null) >= Roles.OP_LEVEL) return n + " уже оператор";
            if (data.moderators.putIfAbsent(target, n) != null) return n + " уже модератор";
            data.player(target, n);
        } else if (data.moderators.remove(target) == null) {
            return n + " не модератор";
        }
        Helm.dirty();
        Helm.log(on ? "moderator_add" : "moderator_remove", actor, target, n, "", "");
        Sync.helloAll(server);
        return null;
    }

    static @Nullable String perm(ServerPlayer p, CompoundTag a) {
        Perms.Node node = Perms.get(a.getString("node"));
        String role = a.getString("role");
        if (node == null) return "Нет такого права";
        if (!Roles.PLAYER.equals(role) && !Roles.MODERATOR.equals(role)) return "Столбец оператора не меняется";
        boolean value = a.getBoolean("value");
        Helm.setCell(node, role, value);
        Helm.log("perm", p, null, "", node.id(), (Roles.MODERATOR.equals(role) ? "модератор" : "игрок") + ": " + (value ? "да" : "нет"));
        Sync.helloAll(p.server);
        return null;
    }

    static @Nullable String caps(ServerPlayer p, CompoundTag a) {
        var s = Helm.settings();
        if (a.contains("mute")) s.moderatorMuteMax = Term.code(Math.max(Term.MINUTE, a.getLong("mute")));
        if (a.contains("ban")) s.moderatorBanMax = Term.code(Math.max(Term.MINUTE, a.getLong("ban")));
        Helm.saveSettings();
        Helm.log("caps", p, null, "", "", "заглушить до " + Term.label(s.caps().muteMax()) + " · бан до " + Term.label(s.caps().banMax()));
        Sync.staffChanged();
        return null;
    }

    static @Nullable String settings(ServerPlayer p, CompoundTag a) {
        var s = Helm.settings();
        StringBuilder what = new StringBuilder();
        if (a.contains("rulesOnJoin") && s.rulesOnJoin != a.getBoolean("rulesOnJoin")) {
            s.rulesOnJoin = a.getBoolean("rulesOnJoin");
            what.append("правила при входе ").append(s.rulesOnJoin ? "включены" : "выключены");
        }
        if (a.contains("newbieHours") && s.newbieHours != a.getInt("newbieHours")) {
            s.newbieHours = a.getInt("newbieHours");
            sep(what).append("новичок ").append(s.newbieHours == 0 ? "выключен" : Term.plural(s.newbieHours, "час", "часа", "часов"));
        }
        if (a.contains("welcome") && !s.welcome.equals(a.getString("welcome"))) {
            s.welcome = a.getString("welcome").strip();
            sep(what).append("приветствие");
        }
        if (a.contains("serverName") && !s.serverName.equals(a.getString("serverName"))) {
            s.serverName = a.getString("serverName").strip();
            sep(what).append("имя сервера");
        }
        if (a.contains("ladder")) {
            try {
                var steps = works.repka.ryshelm.logic.Ladder.steps(a.getString("ladder"));
                String text = new works.repka.ryshelm.logic.Ladder(steps, Term.DAY).text();
                if (!text.equals(s.ladder().text())) {
                    s.ladder = text;
                    sep(what).append("лестница: ").append(text);
                }
            } catch (IllegalArgumentException e) {
                return "Лестница не понята: " + e.getMessage();
            }
        }
        if (a.contains("ladderDays") && s.ladderDays != a.getInt("ladderDays")) {
            s.ladderDays = a.getInt("ladderDays");
            sep(what).append("лестница считает ").append(Term.plural(s.ladderDays, "день", "дня", "дней"));
        }
        if (a.contains("journalDays") && s.journalDays != a.getInt("journalDays")) {
            s.journalDays = a.getInt("journalDays");
            sep(what).append("журнал хранит ").append(Term.plural(s.journalDays, "день", "дня", "дней"));
        }
        if (a.contains("reportCooldown") && s.reportCooldown != a.getInt("reportCooldown")) {
            s.reportCooldown = a.getInt("reportCooldown");
            sep(what).append("пауза жалоб ").append(s.reportCooldown).append(" с");
        }
        if (what.isEmpty()) return null;
        Helm.saveSettings();
        Helm.log("settings", p, null, "", what.toString(), "версия правил " + Helm.rules().version());
        Gate.settingsChanged(p.server);
        Sync.helloAll(p.server);
        return null;
    }

    private static StringBuilder sep(StringBuilder b) {
        if (!b.isEmpty()) b.append(", ");
        return b;
    }
}
