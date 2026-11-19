package works.repka.ryshelm.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.claims.ClaimsApi;
import works.repka.ryscore.perm.Perms;
import works.repka.ryscore.perm.Roles;
import works.repka.ryscore.team.TeamsApi;
import works.repka.ryshelm.HelmPerms;
import works.repka.ryshelm.data.HelmData;
import works.repka.ryshelm.data.Journal;
import works.repka.ryshelm.logic.Kind;
import works.repka.ryshelm.logic.Term;

/**
 * Данные вкладок окна «Сервер» в NBT. Игроку уходит только то, что ему можно: без права {@code ryshelm.players} только
 * игроки в сети и расстояние до них, без координат.
 */
final class Views {
    private Views() {}

    static CompoundTag build(ServerPlayer viewer, CompoundTag view) {
        String tab = view.getString("tab");
        CompoundTag out = new CompoundTag();
        out.putString("tab", tab);
        switch (tab) {
            case "players" -> {
                out.put("players", players(viewer, view.getString("who")));
                if (HelmPerms.PLAYERS.test(viewer)) out.putInt("known", Helm.data().players.size());
            }
            case "rules" -> out.put("rules", rules(viewer));
            case "reports" -> {
                if (HelmPerms.REPORTS.test(viewer)) out.put("reports", Reports.view(viewer, view.getLong("report")));
            }
            case "journal" -> {
                if (HelmPerms.JOURNAL.test(viewer)) out.put("journal", journal(view));
            }
            case "roles" -> {
                if (Roles.atLeast(viewer, Roles.MODERATOR)) out.put("roles", roles(viewer));
            }
            case "settings" -> {
                if (HelmPerms.ADMIN.test(viewer)) out.put("settings", settings(viewer.server));
            }
            default -> {}
        }
        if (view.hasUUID("card") && HelmPerms.PLAYERS.test(viewer)) {
            CompoundTag card = card(viewer, view.getUUID("card"));
            if (card != null) out.put("card", card);
        }
        if (view.hasUUID("punish") && Roles.atLeast(viewer, Roles.MODERATOR)) out.put("punish", punish(viewer, view.getUUID("punish")));
        return out;
    }

    // ---- игроки ----

    /** Сколько игроков вне сети уходит персоналу без поиска (разбор логики 19.11, п. 1): предел пакета NBT клиента 2 МиБ. */
    static final int OFFLINE_PAGE = 200;

    /**
     * Строки вкладки «Игроки». Персоналу: все в сети, вне сети последние {@link #OFFLINE_PAGE} по времени выхода, всегда
     * наказанные и те, на кого есть открытые жалобы; с поиском вне сети только совпавшие по нику.
     */
    static ListTag players(ServerPlayer viewer, String query) {
        MinecraftServer server = viewer.server;
        boolean staff = HelmPerms.PLAYERS.test(viewer);
        long now = System.currentTimeMillis();
        HelmData data = Helm.data();
        String q = query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT);
        // жалобы на игрока считаются один раз за сборку, а не перебором на каждую строку
        java.util.Map<UUID, Integer> openReports = new java.util.HashMap<>();
        if (staff) for (HelmData.Report rep : data.reports) if (rep.state != HelmData.Report.State.CLOSED) openReports.merge(rep.target, 1, Integer::sum);
        Set<UUID> ids = new LinkedHashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) ids.add(p.getUUID());
        if (staff) {
            List<HelmData.PlayerInfo> offline = new java.util.ArrayList<>();
            for (HelmData.PlayerInfo info : data.players.values()) {
                if (ids.contains(info.id)) continue;
                if (!q.isEmpty()) {
                    if (info.name != null && info.name.toLowerCase(java.util.Locale.ROOT).contains(q)) offline.add(info);
                    continue;
                }
                if (openReports.containsKey(info.id) || data.active(info.id, Kind.MUTE, now) != null || data.active(info.id, Kind.BAN, now) != null) ids.add(info.id);
                else offline.add(info);
            }
            offline.sort(Comparator.comparingLong((HelmData.PlayerInfo i) -> i.lastSeen).reversed());
            for (int i = 0; i < offline.size() && i < OFFLINE_PAGE; i++) ids.add(offline.get(i).id);
        }
        UUID myTeam = TeamsApi.get().teamOf(viewer.getUUID());
        ListTag list = new ListTag();
        for (UUID id : ids) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            HelmData.PlayerInfo info = data.players.get(id);
            CompoundTag r = new CompoundTag();
            r.putUUID("id", id);
            r.putString("name", p != null ? p.getGameProfile().getName() : info != null ? info.name : data.name(id));
            r.putString("role", Roles.of(server, id));
            UUID team = TeamsApi.get().teamOf(id);
            r.putString("team", team.equals(id) ? "" : TeamsApi.get().name(team).getString());
            r.putInt("color", TeamsApi.get().color(team));
            r.putBoolean("mine", team.equals(myTeam));
            r.putBoolean("online", p != null);
            if (p != null) {
                r.putString("dim", p.level().dimension().location().toString());
                if (p.level() == viewer.level() && p != viewer) r.putInt("dist", (int) Math.round(Math.sqrt(p.distanceToSqr(viewer))));
                if (staff) {
                    r.putInt("x", p.getBlockX());
                    r.putInt("z", p.getBlockZ());
                    r.putString("land", land(p));
                }
                r.putLong("since", info != null ? info.lastSeen : now);
                r.putBoolean("newbie", Gate.newbie(p));
                r.putBoolean("rulesPending", Gate.pending(id));
                if (Afk.afk(id)) r.putLong("afk", Afk.idle(id));
            } else if (info != null) {
                r.putLong("seen", info.lastSeen);
            }
            if (staff) {
                r.putBoolean("muted", data.active(id, Kind.MUTE, now) != null);
                r.putBoolean("banned", data.active(id, Kind.BAN, now) != null);
                r.putBoolean("frozen", Staff.frozen(id));
                int warns = 0;
                for (HelmData.Punishment x : data.history(id)) if (x.kind == Kind.WARN && x.revoked == 0 && now - x.created < 30 * Term.DAY) warns++;
                r.putInt("warns", warns);
                r.putInt("reports", openReports.getOrDefault(id, 0));
            }
            list.add(r);
        }
        return list;
    }

    /** Чья земля под игроком: «Репка» или пусто. */
    static String land(ServerPlayer p) {
        ClaimsApi claims = ClaimsApi.get();
        if (!claims.present()) return "";
        ChunkPos c = new ChunkPos(BlockPos.containing(p.position()));
        UUID owner = claims.ownerAt(p.level().dimension(), c.x, c.z);
        return owner == null ? "" : TeamsApi.get().name(owner).getString();
    }

    static @Nullable UUID landOwner(ServerPlayer p) {
        ClaimsApi claims = ClaimsApi.get();
        if (!claims.present()) return null;
        ChunkPos c = new ChunkPos(BlockPos.containing(p.position()));
        return claims.ownerAt(p.level().dimension(), c.x, c.z);
    }

    // ---- карточка ----

    static @Nullable CompoundTag card(ServerPlayer viewer, UUID id) {
        MinecraftServer server = viewer.server;
        HelmData data = Helm.data();
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        HelmData.PlayerInfo info = data.players.get(id);
        if (p == null && info == null) return null;
        long now = System.currentTimeMillis();
        CompoundTag c = new CompoundTag();
        c.putUUID("id", id);
        c.putString("name", p != null ? p.getGameProfile().getName() : info.name);
        c.putString("role", Roles.of(server, id));
        UUID team = TeamsApi.get().teamOf(id);
        c.putString("team", team.equals(id) ? "" : TeamsApi.get().name(team).getString());
        c.putBoolean("ownTeam", team.equals(id) || TeamsApi.get().members(team).size() <= 1);
        c.putBoolean("online", p != null);
        c.putLong("played", Helm.played(id));
        if (info != null) {
            c.putLong("first", info.firstSeen);
            c.putLong("seen", info.lastSeen);
            if (!info.rules.isEmpty()) c.putLong("rulesAt", info.rulesAt);
        }
        HelmData.Spot pos = p != null ? Helm.spot(p) : info.lastPos;
        if (pos != null) {
            c.putString("dim", pos.dim);
            c.putString("coords", pos.coords());
        }
        if (p != null) {
            UUID owner = landOwner(p);
            if (owner != null) {
                c.putString("land", TeamsApi.get().name(owner).getString());
                c.putBoolean("landOwn", owner.equals(team));
            }
            c.putBoolean("fly", Staff.flying(p));
            c.putBoolean("god", Staff.god(p));
        }
        c.putBoolean("frozen", Staff.frozen(id));
        HelmData.Punishment mute = data.active(id, Kind.MUTE, now);
        if (mute != null) c.putLong("mute", mute.id);
        HelmData.Punishment ban = data.active(id, Kind.BAN, now);
        if (ban != null) c.putLong("ban", ban.id);
        // история: наказания и жалобы на игрока, новые сверху
        List<CompoundTag> history = new ArrayList<>();
        for (HelmData.Punishment x : data.of(id)) {
            CompoundTag h = new CompoundTag();
            h.putString("kind", x.kind.id());
            h.putLong("id", x.id);
            h.putLong("t", x.created);
            String term = x.kind.timed() ? " " + (x.expires == 0 ? "навсегда" : Term.label(x.expires - x.created)) : "";
            h.putString("text", cap(x.kind.noun()) + term + (x.rule > 0 ? " · п. " + x.rule : "") + " · " + x.byName);
            h.putBoolean("active", x.active(now));
            h.putBoolean("revoked", x.revoked > 0);
            h.putBoolean("canRevoke", x.active(now) && (Roles.atLeast(viewer, Roles.OPERATOR) || viewer.getUUID().equals(x.by)));
            history.add(h);
        }
        for (HelmData.Report r : data.reports) {
            if (!id.equals(r.target)) continue;
            CompoundTag h = new CompoundTag();
            h.putString("kind", "report");
            h.putLong("id", r.id);
            h.putLong("t", r.time);
            h.putString("text", "Жалоба от " + r.fromName + " · " + Reports.stateLabel(r));
            history.add(h);
        }
        history.sort(Comparator.comparingLong((CompoundTag h) -> h.getLong("t")).reversed());
        ListTag hl = new ListTag();
        hl.addAll(history);
        c.put("history", hl);
        ListTag notes = new ListTag();
        for (int i = data.notes.size() - 1; i >= 0; i--) {
            HelmData.Note n = data.notes.get(i);
            if (!id.equals(n.target)) continue;
            CompoundTag t = new CompoundTag();
            t.putString("text", n.text);
            t.putString("by", n.byName);
            t.putLong("t", n.time);
            notes.add(t);
        }
        c.put("notes", notes);
        return c;
    }

    static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Окно наказания: лестница и потолки для этого модератора. */
    static CompoundTag punish(ServerPlayer viewer, UUID id) {
        HelmData data = Helm.data();
        boolean op = Roles.atLeast(viewer, Roles.OPERATOR), perm = HelmPerms.BAN.test(viewer);
        var caps = Helm.settings().caps();
        CompoundTag t = new CompoundTag();
        t.putString("ladder", Helm.settings().ladder().describe(data.name(id), data.past(id), System.currentTimeMillis()));
        var hint = Helm.settings().ladder().next(data.past(id), System.currentTimeMillis());
        t.putString("nextKind", hint.next().kind().id());
        t.putLong("nextTerm", hint.next().millis());
        t.putLong("muteMax", caps.max(op, perm, Kind.MUTE));
        t.putLong("banMax", caps.max(op, perm, Kind.BAN));
        for (Kind k : Kind.values()) t.putBoolean("can_" + k.id(), Punish.node(k, 1).test(viewer) || (k == Kind.BAN && perm));
        return t;
    }

    // ---- правила ----

    static CompoundTag rules(ServerPlayer viewer) {
        CompoundTag t = new CompoundTag();
        var rules = Helm.rules();
        t.putString("title", rules.title());
        t.putString("version", rules.version());
        t.putLong("date", Helm.rulesDate());
        t.putString("server", Sync.serverName(viewer.server));
        t.putBoolean("onJoin", Helm.settings().rulesOnJoin);
        ListTag items = new ListTag();
        for (var r : rules.rules()) {
            CompoundTag i = new CompoundTag();
            i.putInt("n", r.number());
            i.putString("title", r.title());
            i.putString("detail", r.detail());
            items.add(i);
        }
        t.put("items", items);
        HelmData.PlayerInfo info = Helm.data().players.get(viewer.getUUID());
        if (info != null && rules.version().equals(info.rules)) t.putLong("accepted", info.rulesAt);
        t.putBoolean("pending", Gate.pending(viewer.getUUID()));
        return t;
    }

    // ---- журнал ----

    static CompoundTag journal(CompoundTag view) {
        Journal j = Helm.journal();
        CompoundTag t = new CompoundTag();
        if (j == null) return t;
        String who = view.getString("who").strip().toLowerCase(Locale.ROOT);
        int days = view.contains("days") ? view.getInt("days") : 7;
        String cat = view.getString("cat");
        long from = days <= 0 ? 0 : System.currentTimeMillis() - days * Term.DAY;
        Predicate<Journal.Entry> base = e -> e.t >= from && (who.isEmpty() || e.an.toLowerCase(Locale.ROOT).contains(who) || e.pn.toLowerCase(Locale.ROOT).contains(who));
        int[] counts = j.counts(base);
        t.putIntArray("counts", counts);
        Predicate<Journal.Entry> filter = cat.isEmpty() ? base : base.and(e -> e.category().name().equalsIgnoreCase(cat));
        ListTag list = new ListTag();
        for (Journal.Entry e : j.query(filter, 300)) {
            CompoundTag r = new CompoundTag();
            r.putLong("t", e.t);
            r.putString("type", e.type);
            r.putString("a", e.an);
            r.putString("p", e.pn);
            r.putString("d", e.d);
            r.putString("n", e.n.replaceAll("#\\d+$", "").strip());
            list.add(r);
        }
        t.put("entries", list);
        t.putInt("keep", Helm.settings().journalDays);
        return t;
    }

    // ---- роли ----

    static CompoundTag roles(ServerPlayer viewer) {
        MinecraftServer server = viewer.server;
        CompoundTag t = new CompoundTag();
        t.putBoolean("edit", HelmPerms.ADMIN.test(viewer));
        t.putBoolean("luckperms", ModList.get().isLoaded("luckperms"));
        ListTag nodes = new ListTag();
        for (Perms.Node n : Perms.nodes()) {
            CompoundTag r = new CompoundTag();
            r.putString("id", n.id());
            // подпись переводит клиент: на выделенном сервере языков модов нет
            if (n.label().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc) r.putString("key", tc.getKey());
            r.putString("label", n.label().getString());
            r.putString("mod", n.modid());
            r.putString("def", n.defaultRole());
            r.putBoolean("player", Helm.cell(n, Roles.PLAYER));
            r.putBoolean("moderator", Helm.cell(n, Roles.MODERATOR));
            r.putBoolean("moderation", HelmPerms.MODERATION.contains(n));
            nodes.add(r);
        }
        t.put("nodes", nodes);
        ListTag mods = new ListTag();
        for (var e : Helm.data().moderators.entrySet()) {
            CompoundTag m = new CompoundTag();
            m.putUUID("id", e.getKey());
            m.putString("name", e.getValue());
            m.putBoolean("online", server.getPlayerList().getPlayer(e.getKey()) != null);
            mods.add(m);
        }
        t.put("moderators", mods);
        ListTag ops = new ListTag();
        Set<String> opNames = new LinkedHashSet<>();
        for (String name : server.getPlayerList().getOpNames()) opNames.add(name);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) if (Roles.atLeast(p, Roles.OPERATOR)) opNames.add(p.getGameProfile().getName());
        for (String name : opNames) ops.add(net.minecraft.nbt.StringTag.valueOf(name));
        t.put("operators", ops);
        ListTag candidates = new ListTag();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (Roles.atLeast(p, Roles.MODERATOR)) continue;
            CompoundTag c = new CompoundTag();
            c.putUUID("id", p.getUUID());
            c.putString("name", p.getGameProfile().getName());
            candidates.add(c);
        }
        t.put("candidates", candidates);
        var caps = Helm.settings().caps();
        t.putLong("muteMax", caps.muteMax());
        t.putLong("banMax", caps.banMax());
        return t;
    }

    // ---- настройки ----

    static CompoundTag settings(MinecraftServer server) {
        var s = Helm.settings();
        CompoundTag t = new CompoundTag();
        t.putBoolean("whitelist", Whitelist.on(server));
        ListTag wl = new ListTag();
        for (String n : Whitelist.names(server)) wl.add(net.minecraft.nbt.StringTag.valueOf(n));
        t.put("whitelistNames", wl);
        t.putString("serverName", s.serverName);
        t.putBoolean("rulesOnJoin", s.rulesOnJoin);
        t.putInt("newbieHours", s.newbieHours);
        t.putString("welcome", s.welcome);
        t.putString("ladder", s.ladder().text());
        t.putInt("ladderDays", s.ladderDays);
        t.putInt("journalDays", s.journalDays);
        t.putInt("reportCooldown", s.reportCooldown);
        t.putInt("afkMinutes", s.afkMinutes);
        t.putInt("idleKickMinutes", s.idleKickMinutes);
        t.putBoolean("antiSpam", s.antiSpam);
        t.putInt("spamMessages", s.spamMessages);
        t.putInt("spamSeconds", s.spamSeconds);
        t.putInt("spamMuteMinutes", s.spamMuteMinutes);
        t.putString("rulesFile", "config/ryshelm/rules.md");
        t.putString("rulesVersion", Helm.rules().version());
        t.putString("starterKit", s.starterKit);
        ListTag kits = new ListTag();
        for (Kits.Kit k : Kits.all()) {
            CompoundTag x = new CompoundTag();
            x.putString("name", k.name);
            x.putInt("items", k.items.size());
            x.putString("rule", works.repka.ryshelm.logic.KitRule.label(k.cooldown));
            kits.add(x);
        }
        t.put("kits", kits);
        t.putInt("rulesCount", Helm.rules().rules().size());
        return t;
    }
}
