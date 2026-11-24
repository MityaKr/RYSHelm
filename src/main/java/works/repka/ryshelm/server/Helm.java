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
import works.repka.ryshelm.logic.Lang;
import works.repka.ryshelm.logic.RulesText;
import works.repka.ryshelm.logic.Said;

/**
 * Сердце RYSHelm на сервере: данные, настройки, правила, журнал, роли и таблица прав. Всё зовётся из главного потока
 * сервера. Права проверяются здесь и в {@link Requests}, клиенту не верим.
 */
public final class Helm {
    /** Файлы, которые правят руками (настройки, наборы): с отступами. */
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** Данные сервера: без отступов, игроки отдельным файлом (разбор логики 19.11, п. 2). */
    static final Gson DATA_GSON = new GsonBuilder().disableHtmlEscaping().addSerializationExclusionStrategy(new com.google.gson.ExclusionStrategy() {
        @Override
        public boolean shouldSkipField(com.google.gson.FieldAttributes f) {
            return f.getDeclaringClass() == HelmData.class && f.getName().equals("players");
        }

        @Override
        public boolean shouldSkipClass(Class<?> c) {
            return false;
        }
    }).create();
    static final Gson PLAIN = new GsonBuilder().disableHtmlEscaping().create();
    private static final java.lang.reflect.Type PLAYERS_TYPE = new com.google.gson.reflect.TypeToken<java.util.LinkedHashMap<UUID, HelmData.PlayerInfo>>() {}.getType();
    /** Запись на диск в своём потоке: главный поток только собирает строку. */
    private static @Nullable java.util.concurrent.ExecutorService writer;

    private static @Nullable MinecraftServer server;
    private static HelmData data = new HelmData();
    private static HelmSettings settings = new HelmSettings();
    private static RulesText rules = RulesText.parse(RulesText.defaults(Lang.server().russian()));
    private static long rulesDate;
    private static @Nullable Journal journal;
    private static boolean dirty, dirtyPlayers;
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

    /** Поменялись данные сервера (и, может быть, игроков): запишутся оба файла. */
    public static void dirty() {
        dirty = true;
        dirtyPlayers = true;
    }

    /** Поменялись только сведения об игроках (вход, выход): пишется только {@code players.json}. */
    public static void dirtyPlayers() {
        dirtyPlayers = true;
    }

    // ---- пути ----

    static Path worldDir(MinecraftServer s) {
        return s.getWorldPath(LevelResource.ROOT).normalize();
    }

    static Path dataFile(MinecraftServer s) {
        return worldDir(s).resolve("ryshelm").resolve("helm.json");
    }

    static Path playersFile(Path world) {
        return world.resolve("ryshelm").resolve("players.json");
    }

    /** Данные мира: {@code helm.json} и {@code players.json}; старый {@code helm.json} с игроками внутри тоже читается. */
    static HelmData readData(Path world) {
        HelmData d = readJson(world.resolve("ryshelm").resolve("helm.json"), HelmData.class, new HelmData());
        if (d.moderators == null) d = new HelmData();
        java.util.LinkedHashMap<UUID, HelmData.PlayerInfo> players = readJson(playersFile(world), PLAYERS_TYPE, null);
        if (players != null) d.players = players;
        if (d.players == null) d.players = new java.util.LinkedHashMap<>();
        return d;
    }

    /** Записать оба файла сейчас, в этом потоке (шаг переезда без запущенного мода). */
    static void writeData(Path world, HelmData d) {
        writeText(world.resolve("ryshelm").resolve("helm.json"), DATA_GSON.toJson(d));
        writeText(playersFile(world), PLAIN.toJson(d.players, PLAYERS_TYPE));
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
        data = readData(worldDir(s));
        // роли ядра считались без списка модераторов этого мира
        Roles.invalidateAll();
        writer = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ryshelm-save");
            t.setDaemon(true);
            return t;
        });
        settings = readJson(settingsFile(s), HelmSettings.class, new HelmSettings()).sane();
        writeJson(settingsFile(s), settings);
        loadRules();
        Kits.load(s);
        journal = new Journal(worldDir(s).resolve("ryshelm"), settings.journalDays, ZoneId.systemDefault());
        try {
            int n = journal.load(System.currentTimeMillis());
            RYSHelm.LOG.info("[ryshelm] journal: {} entries over {} days", n, settings.journalDays);
        } catch (IOException e) {
            RYSHelm.LOG.error("[ryshelm] journal not read", e);
        }
        prune();
        Staff.restoreAfterCrash(s);
        RYSHelm.LOG.info("[ryshelm] started: {} moderators, {} punishments, {} reports, rules {} ({} items){}", data.moderators.size(),
            data.punishments.size(), data.reports.size(), rules.version(), rules.rules().size(), settings.rulesOnJoin ? ", on join" : "");
    }

    public static void stop() {
        if (server == null) return;
        save();
        if (journal != null) {
            try {
                journal.close();
            } catch (IOException e) {
                RYSHelm.LOG.error("[ryshelm] journal not closed", e);
            }
        }
        // сервер останавливается: дождаться записи на диск
        java.util.concurrent.ExecutorService w = writer;
        writer = null;
        if (w != null) {
            w.shutdown();
            try {
                if (!w.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)) RYSHelm.LOG.error("[ryshelm] data write did not finish in 15 s");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Staff.clear();
        Chat.clear();
        Afk.clear();
        Requests.clear();
        server = null;
        journal = null;
    }

    public static void tick() {
        if (server == null) return;
        ticks++;
        Staff.tick(server);
        Requests.tick(server);
        Sync.flushHello(server);
        if (ticks % 20 == 0) {
            flushJournal();
            Afk.tick(server);
            Sync.tick(server);
        }
        if ((dirty || dirtyPlayers) && ticks % 200 == 0) save();
        if (ticks % 72000 == 0) prune();
        if (ticks % 1200 == 0) Chat.prune(System.currentTimeMillis());
    }

    /** Собрать строки в главном потоке (данные не меняются под записью) и отдать их потоку записи. */
    public static void save() {
        if (server == null || (!dirty && !dirtyPlayers)) return;
        Path world = worldDir(server);
        String main = dirty ? DATA_GSON.toJson(data) : null;
        String players = dirtyPlayers ? PLAIN.toJson(data.players, PLAYERS_TYPE) : null;
        dirty = false;
        dirtyPlayers = false;
        Runnable job = () -> {
            if (main != null) writeText(world.resolve("ryshelm").resolve("helm.json"), main);
            if (players != null) writeText(playersFile(world), players);
        };
        java.util.concurrent.ExecutorService w = writer;
        if (w != null && !w.isShutdown()) w.execute(job);
        else job.run();
    }

    /** Записать сейчас и дождаться диска: шаг переезда ставит отметку только после записи. */
    public static void flush() {
        save();
        java.util.concurrent.ExecutorService w = writer;
        if (w == null || w.isShutdown()) return;
        try {
            w.submit(() -> {}).get(15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            RYSHelm.LOG.error("[ryshelm] gave up waiting for the data write", e);
        }
    }

    /** Закрытые жалобы старше срока уходят, их чат чистится через неделю (разбор логики 19.11, п. 2). */
    static void prune() {
        int n = data.prune(System.currentTimeMillis(), settings.reportDays * works.repka.ryshelm.logic.Term.DAY, 7 * works.repka.ryshelm.logic.Term.DAY);
        if (n > 0) {
            dirty();
            RYSHelm.LOG.info("[ryshelm] old reports and chat lines removed: {}", n);
        }
    }

    /** Правила перечитываются при запуске и командой; нет файла: кладутся правила по умолчанию. */
    public static void loadRules() {
        Path f = rulesFile();
        try {
            if (!Files.exists(f)) {
                Files.createDirectories(f.getParent());
                Files.writeString(f, RulesText.defaults(Lang.server().russian()), StandardCharsets.UTF_8);
            }
            rules = RulesText.parse(Files.readString(f, StandardCharsets.UTF_8));
            rulesDate = Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            RYSHelm.LOG.error("[ryshelm] rules not read: {}", f, e);
            rules = RulesText.parse(RulesText.defaults(Lang.server().russian()));
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
            RYSHelm.LOG.error("[ryshelm] file not read, a .broken copy is next to it: {}", file, e);
            try {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
            return fallback;
        }
    }

    static void writeJson(Path file, Object value) {
        writeText(file, GSON.toJson(value));
    }

    static void writeText(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            RYSHelm.LOG.error("[ryshelm] not written: {}", file, e);
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

    /** Имя в журнале за консоль и за сам сервер: хранится как раньше, при показе переводит {@link Texts#who}. */
    public static final String CONSOLE = "консоль", SERVER = "сервер";

    public static void log(String type, @Nullable UUID actor, String actorName, @Nullable UUID target, String targetName, Said detail, Said note) {
        add(type, targetName, Journal.entry(type, actor, actorName, target, targetName, detail, note));
    }

    public static void log(String type, @Nullable UUID actor, String actorName, @Nullable UUID target, String targetName, String detail, String note) {
        add(type, targetName, Journal.entry(type, actor, actorName, target, targetName, detail, note));
    }

    public static void log(String type, @Nullable ServerPlayer actor, @Nullable UUID target, String targetName, Said detail, Said note) {
        log(type, actor == null ? null : actor.getUUID(), actor == null ? CONSOLE : actor.getGameProfile().getName(), target, targetName, detail, note);
    }

    public static void log(String type, @Nullable ServerPlayer actor, @Nullable UUID target, String targetName, String detail, String note) {
        log(type, actor == null ? null : actor.getUUID(), actor == null ? CONSOLE : actor.getGameProfile().getName(), target, targetName, detail, note);
    }

    private static void add(String type, String targetName, Journal.Entry entry) {
        Journal j = journal;
        if (j == null) return;
        try {
            j.add(entry);
        } catch (IOException e) {
            RYSHelm.LOG.error("[ryshelm] journal entry not written: {} {}", type, targetName, e);
        }
        Sync.journalChanged();
    }

    static void flushJournal() {
        Journal j = journal;
        if (j == null) return;
        try {
            j.flush();
        } catch (IOException e) {
            RYSHelm.LOG.error("[ryshelm] journal not flushed to disk", e);
        }
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
        if (data.moderators.containsKey(p.getUUID()) && !p.getGameProfile().getName().equals(data.moderators.get(p.getUUID()))) {
            data.moderators.put(p.getUUID(), p.getGameProfile().getName());
            dirty();
        }
        dirtyPlayers();
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
                Said.lit(Sync.date(lastBan.revoked > 0 ? lastBan.revoked : lastBan.expires)), (noted ? Said.of("log.has_note") : Said.NONE).then(Said.lit("#" + lastBan.id)));
        }
        Chat.login(p);
        Staff.login(p, info);
        if (first) Kits.firstJoin(p);
        Sync.hello(p);
        Gate.login(p, info);
        List<String> mail = data.mail.remove(p.getUUID());
        if (mail != null) for (String m : mail) Sync.mail(p, m);
        if (!settings.welcome.isBlank()) Sync.notice(p, "welcome", "row", "INFO", settings.welcome, "", false);
        if (works.repka.ryshelm.HelmPerms.REPORTS.test(p)) {
            long open = data.reports.stream().filter(r -> r.state == HelmData.Report.State.NEW).count();
            if (open > 0) Sync.notice(p, "reports", "card", "FLAG", Texts.t(p, "msg.reports_waiting", open), Texts.t(p, "msg.reports_waiting.line"), false);
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
        dirtyPlayers();
        Staff.logout(p);
        Chat.logout(p);
        Afk.logout(p);
        Requests.logout(p);
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
