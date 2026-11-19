package works.repka.ryshelm.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.perm.Perms;
import works.repka.ryscore.perm.Roles;
import works.repka.ryshelm.RYSHelm;
import works.repka.ryshelm.data.HelmData;
import works.repka.ryshelm.data.HelmSettings;
import works.repka.ryshelm.data.Journal;
import works.repka.ryshelm.logic.RulesText;

/**
 * Сердце RYSHelm на сервере: данные, настройки, правила, журнал, роли и таблица прав. Всё зовётся из главного потока
 * сервера. Права проверяются здесь и в {@link Requests}, клиенту не верим.
 */
public final class Helm {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static @Nullable MinecraftServer server;
    private static HelmData data = new HelmData();
    private static HelmSettings settings = new HelmSettings();
    private static RulesText rules = RulesText.parse(RulesText.DEFAULT);
    private static long rulesDate;
    private static @Nullable Journal journal;
    private static boolean dirty;
    private static int ticks;

    private Helm() {}

    public static boolean running() {
        return server != null;
    }

    public static @Nullable MinecraftServer server() {
        return server;
    }

    public static HelmData data() {
        return data;
    }

    public static HelmSettings settings() {
        return settings;
    }

    public static RulesText rules() {
        return rules;
    }

    public static long rulesDate() {
        return rulesDate;
    }

    public static void dirty() {
        dirty = true;
    }

    // ---- пути ----

    static Path worldDir(MinecraftServer s) {
        return s.getWorldPath(LevelResource.ROOT).normalize();
    }

    static Path dataFile(MinecraftServer s) {
        return worldDir(s).resolve("ryshelm").resolve("helm.json");
    }

    static Path settingsFile(MinecraftServer s) {
        return worldDir(s).resolve("serverconfig").resolve("ryshelm-server.json");
    }

    public static Path rulesFile() {
        return FMLPaths.CONFIGDIR.get().resolve("ryshelm").resolve("rules.md");
    }

    // ---- жизнь сервера ----

    public static void start(MinecraftServer s) {
        server = s;
        ticks = 0;
        data = readJson(dataFile(s), HelmData.class, new HelmData());
        if (data.moderators == null) data = new HelmData();
        settings = readJson(settingsFile(s), HelmSettings.class, new HelmSettings()).sane();
        writeJson(settingsFile(s), settings);
        loadRules();
        Kits.load(s);
        journal = new Journal(worldDir(s).resolve("ryshelm"), settings.journalDays, ZoneId.systemDefault());
        try {
            int n = journal.load(System.currentTimeMillis());
            RYSHelm.LOG.info("[ryshelm] журнал: {} записей за {} дней", n, settings.journalDays);
        } catch (IOException e) {
            RYSHelm.LOG.error("[ryshelm] журнал не прочитан", e);
        }
        Staff.restoreAfterCrash(s);
        RYSHelm.LOG.info("[ryshelm] запущен: модераторов {}, наказаний {}, жалоб {}, правила {} ({} пунктов){}", data.moderators.size(),
            data.punishments.size(), data.reports.size(), rules.version(), rules.rules().size(), settings.rulesOnJoin ? ", при входе" : "");
    }

    public static void stop() {
        if (server == null) return;
        save();
        Staff.clear();
        Chat.clear();
        server = null;
        journal = null;
    }

    public static void tick() {
        if (server == null) return;
        ticks++;
        Staff.tick(server);
        if (ticks % 20 == 0) Sync.tick(server);
        if (dirty && ticks % 200 == 0) save();
    }

    public static void save() {
        if (server == null || !dirty) return;
        dirty = false;
        writeJson(dataFile(server), data);
    }

    /** Правила перечитываются при запуске и командой; нет файла: кладутся правила по умолчанию. */
    public static void loadRules() {
        Path f = rulesFile();
        try {
            if (!Files.exists(f)) {
                Files.createDirectories(f.getParent());
                Files.writeString(f, RulesText.DEFAULT, StandardCharsets.UTF_8);
            }
            rules = RulesText.parse(Files.readString(f, StandardCharsets.UTF_8));
            rulesDate = Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            RYSHelm.LOG.error("[ryshelm] правила не прочитаны: {}", f, e);
            rules = RulesText.parse(RulesText.DEFAULT);
        }
    }

    public static void saveSettings() {
        if (server == null) return;
        settings.sane();
        writeJson(settingsFile(server), settings);
        if (journal != null) journal.retention(settings.journalDays);
    }

    static <T> T readJson(Path file, Class<T> type, T fallback) {
        return readJson(file, (java.lang.reflect.Type) type, fallback);
    }

    static <T> T readJson(Path file, java.lang.reflect.Type type, T fallback) {
        if (!Files.exists(file)) return fallback;
        try {
            T t = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
            return t == null ? fallback : t;
        } catch (Exception e) {
            // битый файл не теряем: копия рядом, работаем с чистого листа
            RYSHelm.LOG.error("[ryshelm] файл не прочитан, копия .broken рядом: {}", file, e);
            try {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
            return fallback;
        }
    }

    static void writeJson(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(value), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            RYSHelm.LOG.error("[ryshelm] не записан {}", file, e);
        }
    }

    // ---- роли и права ----

    /** Поставщик ролей ядра: модератор из списка, если он не оператор игры. */
    public static @Nullable String roleOf(MinecraftServer s, UUID id) {
        if (server == null || !data.moderators.containsKey(id)) return null;
        return Roles.opLevel(s, id, null) >= Roles.OP_LEVEL ? null : Roles.MODERATOR;
    }

    /** Таблица ролей ядра: оператор может всё, остальное по таблице, без записи решает роль узла. */
    public static @Nullable Boolean decide(MinecraftServer s, UUID id, Perms.Node node) {
        String role = Roles.of(s, id);
        if (Roles.OPERATOR.equals(role)) return true;
        if (server == null) return null;
        Map<String, Boolean> row = data.table.get(node.id());
        return row == null ? null : row.get(role);
    }

    /** Значение ячейки таблицы для окна: запись или умолчание узла. */
    public static boolean cell(Perms.Node node, String role) {
        if (Roles.OPERATOR.equals(role)) return true;
        Map<String, Boolean> row = data.table.get(node.id());
        Boolean b = row == null ? null : row.get(role);
        return b != null ? b : Roles.rank(role) >= Roles.rank(node.defaultRole());
    }

    public static void setCell(Perms.Node node, String role, boolean value) {
        boolean byDefault = Roles.rank(role) >= Roles.rank(node.defaultRole());
        Map<String, Boolean> row = data.table.computeIfAbsent(node.id(), k -> new java.util.LinkedHashMap<>());
        if (value == byDefault) row.remove(role);
        else row.put(role, value);
        if (row.isEmpty()) data.table.remove(node.id());
        dirty();
    }

    /** Проверка игрока или консоли (консоль и командный блок с уровнем 2 могут всё). */
    public static boolean can(net.minecraft.commands.CommandSourceStack source, Perms.Node node) {
        ServerPlayer p = source.getPlayer();
        return p != null ? node.test(p) : source.hasPermission(Roles.OP_LEVEL);
    }

    public static boolean operator(ServerPlayer p) {
        return Roles.atLeast(p, Roles.OPERATOR);
    }

    public static boolean staff(ServerPlayer p) {
        return Roles.atLeast(p, Roles.MODERATOR);
    }

    // ---- журнал ----

    public static void log(String type, @Nullable UUID actor, String actorName, @Nullable UUID target, String targetName, String detail, String note) {
        Journal j = journal;
        if (j == null) return;
        try {
            j.add(Journal.entry(type, actor, actorName, target, targetName, detail, note));
        } catch (IOException e) {
            RYSHelm.LOG.error("[ryshelm] запись журнала не легла: {} {}", type, targetName, e);
        }
        Sync.journalChanged();
    }

    public static void log(String type, @Nullable ServerPlayer actor, @Nullable UUID target, String targetName, String detail, String note) {
        log(type, actor == null ? null : actor.getUUID(), actor == null ? "консоль" : actor.getGameProfile().getName(), target, targetName, detail, note);
    }

    public static @Nullable Journal journal() {
        return journal;
    }

    // ---- игроки ----

    public static long played(ServerPlayer p) {
        return p.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) * 50L;
    }

    /** Наиграно: у игрока в сети по статистике игры, у остальных на момент выхода. */
    public static long played(UUID id) {
        ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(id);
        if (p != null) return played(p);
        HelmData.PlayerInfo info = data.players.get(id);
        return info == null ? 0 : info.played;
    }

    public static HelmData.Spot spot(ServerPlayer p) {
        HelmData.Spot s = HelmData.Spot.of(p.level().dimension().location().toString(), p.getX(), p.getY(), p.getZ());
        s.yRot = p.getYRot();
        s.xRot = p.getXRot();
        return s;
    }

    public static void login(ServerPlayer p) {
        long now = System.currentTimeMillis();
        HelmData.PlayerInfo info = data.player(p.getUUID(), p.getGameProfile().getName());
        boolean first = info.firstSeen == 0;
        if (first) info.firstSeen = now;
        info.lastSeen = now;
        if (data.moderators.containsKey(p.getUUID())) data.moderators.put(p.getUUID(), p.getGameProfile().getName());
        dirty();
        // прошлый бан закончился: событие в журнал (макет 12, «вошёл, прошлый бан закончился 12.11»)
        HelmData.Punishment lastBan = null;
        for (HelmData.Punishment x : data.of(p.getUUID())) {
            if (x.kind == works.repka.ryshelm.logic.Kind.BAN) {
                lastBan = x;
                break;
            }
        }
        if (lastBan != null && !lastBan.active(now) && lastBan.expires != 0 && now - lastBan.expires < 30L * 86_400_000L
            && info.lastSeen > 0 && !hasJoinBanned(p.getUUID(), lastBan.id)) {
            boolean noted = data.notes.stream().anyMatch(n -> p.getUUID().equals(n.target));
            log("join_banned", null, p.getGameProfile().getName(), p.getUUID(), p.getGameProfile().getName(),
                Sync.date(lastBan.revoked > 0 ? lastBan.revoked : lastBan.expires), (noted ? "есть заметка" : "") + "#" + lastBan.id);
        }
        Staff.login(p, info);
        if (first) Kits.firstJoin(p);
        Sync.hello(p);
        Gate.login(p, info);
        List<String> mail = data.mail.remove(p.getUUID());
        if (mail != null) for (String m : mail) Sync.mail(p, m);
        if (!settings.welcome.isBlank()) Sync.notice(p, "welcome", "row", "INFO", settings.welcome, "", false);
        if (works.repka.ryshelm.HelmPerms.REPORTS.test(p)) {
            long open = data.reports.stream().filter(r -> r.state == HelmData.Report.State.NEW).count();
            if (open > 0) Sync.notice(p, "reports", "card", "FLAG", "Жалобы ждут: " + open, "Окно «Сервер», вкладка «Жалобы»", false);
        }
        Sync.broadcastRoster();
    }

    private static boolean hasJoinBanned(UUID id, long banId) {
        Journal j = journal;
        if (j == null) return false;
        String tag = "#" + banId;
        return !j.query(e -> "join_banned".equals(e.type) && id.equals(e.p) && e.n.endsWith(tag), 1).isEmpty();
    }

    public static void logout(ServerPlayer p) {
        HelmData.PlayerInfo info = data.player(p.getUUID(), p.getGameProfile().getName());
        info.lastSeen = System.currentTimeMillis();
        info.played = played(p);
        info.lastPos = spot(p);
        dirty();
        Staff.logout(p);
        Chat.logout(p);
        Gate.logout(p);
        Sync.logout(p);
        Sync.broadcastRoster();
    }

    /** Отложенное уведомление игроку: сейчас, если он в сети, иначе при входе. */
    public static void mail(UUID id, String text) {
        ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(id);
        if (p != null) {
            Sync.mail(p, text);
            return;
        }
        data.mail.computeIfAbsent(id, k -> new ArrayList<>()).add(text);
        dirty();
    }
}
