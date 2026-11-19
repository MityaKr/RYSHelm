package works.repka.ryshelm.dev;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import works.repka.ryscore.perm.Perms;
import works.repka.ryscore.perm.Roles;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryshelm.RYSHelm;
import works.repka.ryshelm.client.HelmClient;
import works.repka.ryshelm.client.ServerScreen;
import works.repka.ryshelm.data.HelmData;
import works.repka.ryshelm.data.Journal;
import works.repka.ryshelm.logic.Kind;
import works.repka.ryshelm.logic.Term;
import works.repka.ryshelm.network.HelmNet;
import works.repka.ryshelm.server.Gate;
import works.repka.ryshelm.server.Helm;
import works.repka.ryshelm.server.Punish;
import works.repka.ryshelm.server.Reports;

/**
 * Проверка в игре: новый плоский мир, данные как в макетах 08–14, снимки окна «Сервер» и проверки логики на встроенном
 * сервере. Включается свойством {@code ryshelm.autotest} ({@code gradlew runAutotest}), в jar не входит. Снимки в
 * {@code run/screenshots/helm_*.png}, вердикт строкой {@code [autotest] PASS}.
 */
@EventBusSubscriber(modid = RYSHelm.MODID, value = Dist.CLIENT)
public final class HelmAutoTest {
    private record Step(String name, BooleanSupplier ready, Runnable action, int pause) {}

    private static final String MODE = System.getProperty("ryshelm.autotest");
    private static final List<Step> STEPS = new ArrayList<>();
    static final UUID GRIB = id("Grib_ok"), VASYA = id("Vasya_M"), KOT = id("Kot_Boris"), LENA = id("Lena_K"), TOR = id("Tor4ok"), SUNNY = id("Sunny_2010"), BARSIK = id("Barsik");
    private static boolean built;
    private static int index, wait, shots, failures, waited;

    private HelmAutoTest() {}

    static UUID id(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes());
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post e) {
        if (MODE == null || MODE.equals("migrate")) return;
        if (!built) {
            built = true;
            build();
        }
        if (wait > 0) {
            wait--;
            return;
        }
        if (index >= STEPS.size()) return;
        Step s = STEPS.get(index);
        if (!s.ready.getAsBoolean()) {
            if (++waited > 2400) {
                RYSHelm.LOG.error("[autotest] FAILED: не дождались шага {}", s.name);
                mc().stop();
            }
            return;
        }
        waited = 0;
        try {
            s.action.run();
        } catch (Throwable t) {
            failures++;
            RYSHelm.LOG.error("[autotest] шаг {} упал", s.name, t);
        }
        wait = s.pause;
        index++;
    }

    static Minecraft mc() {
        return Minecraft.getInstance();
    }

    static MinecraftServer server() {
        return mc().getSingleplayerServer();
    }

    static ServerPlayer player() {
        return server().getPlayerList().getPlayers().get(0);
    }

    static void step(String name, Runnable action, int pause) {
        STEPS.add(new Step(name, () -> true, action, pause));
    }

    static void await(String name, BooleanSupplier ready) {
        STEPS.add(new Step(name, ready, () -> {}, 2));
    }

    /** Снимок с именем по номеру макета: helm_08_players.png рядом с img/08_server_players.png. */
    static void shot(String name) {
        step("clean", () -> mc().gui.getChat().clearMessages(false), 2);
        step("shot " + name, () -> {
            shots++;
            Screenshot.grab(mc().gameDirectory, "helm_" + name + ".png", mc().getMainRenderTarget(), m -> {});
        }, 6);
    }

    static void check(String what, boolean ok) {
        if (!ok) failures++;
        RYSHelm.LOG.info("[autotest] {} {}", ok ? "OK  " : "FAIL", what);
    }

    /** Код на встроенном сервере и ожидание, пока он выполнится. */
    static void server(String name, Runnable r) {
        boolean[] done = {false};
        STEPS.add(new Step(name, () -> true, () -> server().execute(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                failures++;
                RYSHelm.LOG.error("[autotest] {} упал на сервере", name, t);
            }
            done[0] = true;
        }), 0));
        await(name + " готово", () -> done[0]);
    }

    static void act(String action, CompoundTag args) {
        HelmNet.action(action, args);
    }

    static CompoundTag target(UUID id) {
        CompoundTag a = new CompoundTag();
        a.putUUID("target", id);
        return a;
    }

    private static void build() {
        step("title", () -> {
            mc().options.pauseOnLostFocus = false;
            mc().options.onboardAccessibility = false;
            mc().options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0);
            if (!(mc().screen instanceof TitleScreen)) mc().setScreen(new TitleScreen());
            ThemeManager.select("ryscore:slate");
        }, 30);
        step("world", () -> {
            String world = "ryshelm_autotest_" + Long.toHexString(System.currentTimeMillis());
            LevelSettings settings = new LevelSettings(world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            mc().createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(42L, false, false),
                r -> r.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc().screen);
        }, 10);
        await("в мире", () -> mc().player != null && mc().level != null && mc().screen == null && HelmClient.present);
        step("роль", () -> {
            check("хозяин мира с читами оператор", "operator".equals(HelmClient.role));
            check("права пришли: ryshelm.admin", HelmClient.can("ryshelm.admin"));
            check("узлов семьи не меньше 23", Perms.nodes().size() >= 23);
        }, 2);
        server("данные макетов", HelmAutoTest::seed);
        logic();
        window();
        if (works.repka.ryshelm.dev.HelmAutoTestA3.ON) HelmAutoTestA3.build();
        step("итог", () -> {
            RYSHelm.LOG.info("[autotest] снимков {}", shots);
            RYSHelm.LOG.info(failures == 0 ? "[autotest] PASS" : "[autotest] FAILED: {}", failures);
            mc().stop();
        }, 1);
    }

    /** Игроки, модератор, наказания, заметки, жалобы и журнал как в макетах 08–12. */
    static void seed() {
        MinecraftServer s = server();
        HelmData d = Helm.data();
        long now = System.currentTimeMillis();
        String[][] people = {{"Grib_ok", "42"}, {"Vasya_M", "180"}, {"Kot_Boris", "20"}, {"Lena_K", "60"}, {"Tor4ok", "15"}, {"Sunny_2010", "5"}, {"Barsik", "-4320"}};
        for (String[] p : people) {
            HelmData.PlayerInfo info = d.player(id(p[0]), p[0]);
            long m = Long.parseLong(p[1]);
            info.firstSeen = now - 17 * Term.DAY;
            info.lastSeen = now - Math.abs(m) * Term.MINUTE;
            info.played = (31 + m % 7) * Term.HOUR;
            info.lastPos = HelmData.Spot.of("minecraft:overworld", 1402 + m, 58, -418 - m);
        }
        d.moderators.put(VASYA, "Vasya_M");
        HelmData.Punishment warn = punishment(d, Kind.WARN, GRIB, "Grib_ok", VASYA, "Vasya_M", 1, "мат в общем чате", now - 3 * Term.DAY + Term.HOUR, 0);
        HelmData.Punishment ban = punishment(d, Kind.BAN, GRIB, "Grib_ok", null, "MityaYS", 2, "ломал склад", now - 9 * Term.DAY, now - 7 * Term.DAY);
        d.punishments.add(ban);
        d.punishments.add(warn);
        HelmData.Note note = new HelmData.Note();
        note.id = d.id();
        note.target = GRIB;
        note.by = VASYA;
        note.byName = "Vasya_M";
        note.text = "Ставит буры у чужих участков, смотреть журнал земли";
        note.time = now - 3 * Term.DAY;
        d.notes.add(note);
        report(d, LENA, "Lena_K", GRIB, "Grib_ok", 1, "Сломал забор и вскрыл сундук у нас на складе, пока нас не было. Склад на нашей земле, но с краю была дыра в привате.", now - 40 * Term.MINUTE, HelmData.Report.State.NEW);
        report(d, KOT, "Kot_Boris", GRIB, "Grib_ok", 3, "Оскорбляет в общем чате", now - 53 * Term.MINUTE, HelmData.Report.State.NEW);
        HelmData.Report taken = report(d, SUNNY, "Sunny_2010", BARSIK, "Barsik", 5, "Зовёт к себе и убивает", now - 3 * Term.HOUR, HelmData.Report.State.TAKEN);
        taken.takenBy = VASYA;
        taken.takenByName = "Vasya_M";
        HelmData.Report closed = report(d, player().getUUID(), "Dev", KOT, "Kot_Boris", 3, "", now - Term.DAY, HelmData.Report.State.CLOSED);
        closed.outcome = "punished";
        Helm.dirty();
        Journal j = Helm.journal();
        try {
            entry(j, "mute", VASYA, "Vasya_M", GRIB, "Grib_ok", "на 1 ч", "п. 3 · мат в общем чате", now - 20 * Term.MINUTE);
            entry(j, "spectate", VASYA, "Vasya_M", GRIB, "Grib_ok", "", "4 мин · вернулся на место", now - 22 * Term.MINUTE);
            entry(j, "invsee", VASYA, "Vasya_M", GRIB, "Grib_ok", "", "только смотрел", now - 26 * Term.MINUTE);
            entry(j, "report", LENA, "Lena_K", GRIB, "Grib_ok", "", "п. 1 · новая", now - 27 * Term.MINUTE);
            entry(j, "join_banned", GRIB, "Grib_ok", GRIB, "Grib_ok", "12.11", "есть заметка", now - 2 * Term.HOUR);
            entry(j, "fly", null, "MityaYS", null, "", "включил", "себе", now - 3 * Term.HOUR);
            entry(j, "moderator_add", null, "MityaYS", VASYA, "Vasya_M", "", "", now - Term.DAY);
            entry(j, "settings", null, "MityaYS", null, "", "правила при входе включены", "версия правил 2", now - Term.DAY - Term.HOUR);
            entry(j, "kick", VASYA, "Vasya_M", SUNNY, "Sunny_2010", "", "п. 5 · звал новичков в ловушку", now - Term.DAY - 2 * Term.HOUR);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        works.repka.ryshelm.server.Sync.helloAll(s);
    }

    static HelmData.Punishment punishment(HelmData d, Kind kind, UUID target, String name, UUID by, String byName, int rule, String reason, long created, long expires) {
        HelmData.Punishment p = new HelmData.Punishment();
        p.id = d.id();
        p.kind = kind;
        p.target = target;
        p.targetName = name;
        p.by = by;
        p.byName = byName;
        p.rule = rule;
        p.reason = reason;
        p.created = created;
        p.expires = expires;
        return p;
    }

    static HelmData.Report report(HelmData d, UUID from, String fromName, UUID target, String targetName, int rule, String text, long time, HelmData.Report.State state) {
        HelmData.Report r = new HelmData.Report();
        r.id = d.id();
        r.from = from;
        r.fromName = fromName;
        r.target = target;
        r.targetName = targetName;
        r.rule = rule;
        r.text = text;
        r.time = time;
        r.state = state;
        r.fromPos = HelmData.Spot.of("minecraft:overworld", 1232, 65, -360);
        r.targetPos = HelmData.Spot.of("minecraft:overworld", 1262, 66, -384);
        r.land = "Репка · База";
        long t0 = time - 6 * Term.MINUTE;
        r.chat.add(new HelmData.ChatLine(t0, "у репки опять никого"));
        r.chat.add(new HelmData.ChatLine(t0 + 3 * Term.MINUTE, "забор у них из дуба, ломается быстро"));
        r.chat.add(new HelmData.ChatLine(t0 + 4 * Term.MINUTE, "сундук открыл, там алмазы лол"));
        r.chat.add(new HelmData.ChatLine(t0 + 5 * Term.MINUTE, "ну и пусть жалуются"));
        d.reports.add(r);
        return r;
    }

    static void entry(Journal j, String type, UUID a, String an, UUID p, String pn, String d, String n, long t) throws Exception {
        Journal.Entry e = Journal.entry(type, a, an, p, pn, d, n);
        e.t = t;
        j.add(e);
    }

    /** Логика сервера: роли, таблица прав, потолки, наказания, бан в списке игры, снятие, журнал, жалоба. */
    private static void logic() {
        server("роли и таблица", () -> {
            MinecraftServer s = server();
            check("Vasya_M модератор по списку", Roles.MODERATOR.equals(Roles.of(s, VASYA)));
            check("Lena_K игрок", Roles.PLAYER.equals(Roles.of(s, LENA)));
            Perms.Node warn = Perms.get("ryshelm.warn"), ban = Perms.get("ryshelm.ban"), report = Perms.get("ryshelm.report");
            check("модератор предупреждает", warn.test(s, VASYA));
            check("модератор не банит навсегда", !ban.test(s, VASYA));
            check("игрок жалуется", report.test(s, LENA));
            check("игрок не предупреждает", !warn.test(s, LENA));
            Helm.setCell(warn, Roles.PLAYER, true);
            check("таблица: игроку дали предупреждать", warn.test(s, LENA));
            Helm.setCell(warn, Roles.PLAYER, false);
            check("таблица: вернули умолчание", !warn.test(s, LENA) && !Helm.data().table.containsKey("ryshelm.warn"));
            check("оператор может всё", ban.test(player()));
        });
        server("наказания", () -> {
            MinecraftServer s = server();
            long now = System.currentTimeMillis();
            String err = Punish.give(s, player(), TOR, "Tor4ok", Kind.MUTE, Term.HOUR, 3, "проверка", true);
            check("заглушить на 1 ч: " + err, err == null && Punish.muted(TOR) != null);
            HelmData.Punishment m = Punish.muted(TOR);
            check("срок заглушения около часа", m != null && Math.abs(m.expires - now - Term.HOUR) < 5000);
            err = Punish.give(s, player(), BARSIK, "Barsik", Kind.BAN, 2 * Term.DAY, 2, "проверка", true);
            GameProfile gp = new GameProfile(BARSIK, "Barsik");
            check("бан лёг в список игры: " + err, err == null && s.getPlayerList().getBans().isBanned(gp));
            var entry = s.getPlayerList().getBans().get(gp);
            check("у бана в списке игры есть срок", entry != null && entry.getExpires() != null);
            check("снять бан", Punish.lift(s, player(), BARSIK, Kind.BAN) == null && !s.getPlayerList().getBans().isBanned(gp));
            check("снять заглушение", Punish.lift(s, player(), TOR, Kind.MUTE) == null && Punish.muted(TOR) == null);
            check("себя наказать нельзя", Punish.give(s, player(), player().getUUID(), "Dev", Kind.WARN, 0, 0, "", true) != null);
            var caps = Helm.settings().caps();
            check("потолок модератора: бан 8 дней нельзя", caps.check(false, false, Kind.BAN, 8 * Term.DAY) != null);
            int journal = Helm.journal().size();
            check("журнал пишет наказания и снятия (" + journal + ")", journal >= 13);
            String text = Helm.settings().ladder().describe("Grib_ok", Helm.data().past(GRIB), System.currentTimeMillis());
            check("лестница для Grib_ok: " + text, text.contains("заглушить на 1 ч"));
        });
        server("жалоба и пауза", () -> {
            String err = Reports.create(player(), SUNNY, 5, "проверка жалобы");
            check("жалоба создана: " + err, err == null);
            check("вторая сразу не проходит", Reports.create(player(), SUNNY, 5, "ещё") != null);
            HelmData.Report last = Helm.data().reports.get(Helm.data().reports.size() - 1);
            last.state = HelmData.Report.State.CLOSED;
            last.outcome = "rejected";
            Helm.data().player(player().getUUID(), "").lastReport = 0;
        });
    }

    /** Окно «Сервер»: снимки рядом с макетами 08, 09, 11, 12. */
    private static void window() {
        step("окно: игроки", () -> {
            ServerScreen.selected = GRIB;
            ServerScreen.open("players");
        }, 30);
        step("фильтр «все»", () -> works.repka.ryshelm.dev.HelmAutoTestA3.filter("all"), 30);
        shot("08_players");
        step("наказание", () -> {
            if (mc().screen instanceof ServerScreen s) works.repka.ryshelm.dev.HelmAutoTestA3.punish(s, Kind.MUTE, GRIB, "Grib_ok");
        }, 30);
        shot("09_punish");
        step("закрыть наказание", () -> {
            if (mc().screen instanceof ServerScreen s) works.repka.ryshelm.dev.HelmAutoTestA3.closePunish(s);
        }, 5);
        step("роли", () -> ServerScreen.open("roles"), 30);
        shot("11_roles");
        step("журнал", () -> ServerScreen.open("journal"), 30);
        shot("12_journal");
        step("журнал пришёл", () -> check("в журнале есть записи", works.repka.ryshelm.client.View.journal.getList("entries", 10).size() >= 9), 2);
        settings();
    }

    /**
     * Вкладка «Настройки» (отзыв автора 19.11): длинное имя сервера в шапке, лупа в поиске, плашки белого списка; правка
     * ждёт ответа сервера и снимается им; места без ответа показывают крутилки и откатываются через 6 с.
     */
    private static void settings() {
        server("настройки: имя и белый список", () -> {
            MinecraftServer s = server();
            Helm.settings().serverName = "RepYS Test Server";
            Helm.saveSettings();
            s.getPlayerList().setUsingWhiteList(true);
            var wl = s.getPlayerList().getWhiteList();
            wl.add(new net.minecraft.server.players.UserWhiteListEntry(player().getGameProfile()));
            wl.add(new net.minecraft.server.players.UserWhiteListEntry(new GameProfile(VASYA, "Vasya_M")));
            wl.add(new net.minecraft.server.players.UserWhiteListEntry(new GameProfile(LENA, "Lena_K")));
            works.repka.ryshelm.server.Sync.helloAll(s);
        });
        step("настройки", () -> ServerScreen.open("settings"), 30);
        step("имя и белый список пришли", () -> {
            check("имя сервера в шапке", "RepYS Test Server".equals(HelmClient.server));
            check("белый список включён", works.repka.ryshelm.client.View.settings.getBoolean("whitelist"));
            check("сервер отвечает на действия", HelmClient.acks);
        }, 2);
        shot("15_settings");
        step("антиспам кнопкой", () -> {
            CompoundTag a = new CompoundTag();
            a.putBoolean("antiSpam", true);
            HelmClient.ask("s.antiSpam", true, "settings", a);
            check("антиспам сразу показан включённым", HelmClient.PENDING.value("s.antiSpam", false));
        }, 0);
        await("ответ сервера на антиспам", () -> !HelmClient.PENDING.busy("s.antiSpam"));
        step("антиспам пришёл", () -> check("антиспам включён данными сервера", works.repka.ryshelm.client.View.settings.getBoolean("antiSpam")), 2);
        step("счётчик дважды до ответа", () -> {
            int base = ladderBase = works.repka.ryshelm.client.View.settings.getInt("ladderDays");
            for (int i = 1; i <= 2; i++) {
                CompoundTag a = new CompoundTag();
                a.putInt("ladderDays", base + 5 * i);
                HelmClient.ask("s.ladderDays", base + 5 * i, "settings", a);
            }
            check("счётчик показывает второе нажатие", HelmClient.PENDING.value("s.ladderDays", base) == base + 10);
        }, 0);
        await("ответ на счётчик", () -> !HelmClient.PENDING.busy("s.ladderDays"));
        step("счётчик пришёл", () -> check("лестница считает на 10 дней больше", works.repka.ryshelm.client.View.settings.getInt("ladderDays") == ladderBase + 10), 2);
        step("ошибка откатывает", () -> {
            CompoundTag a = new CompoundTag();
            a.putString("ladder", "warn, ???");
            HelmClient.ask("s.ladder", "warn, ???", "settings", a);
        }, 0);
        await("ответ на лестницу", () -> !HelmClient.PENDING.busy("s.ladder"));
        step("лестница прежняя", () -> check("непонятая лестница не сохранилась", !works.repka.ryshelm.client.View.settings.getString("ladder").contains("???")), 2);
        step("места ждут без ответа", () -> {
            long since = System.currentTimeMillis() - 1000;
            HelmClient.PENDING.start("s.rulesOnJoin", true, since);
            HelmClient.PENDING.start("s.newbieHours", 3, since);
            HelmClient.PENDING.start("s.welcome", "x", since);
            HelmClient.PENDING.start("wl.rm.Vasya_M", null, since);
        }, 10);
        shot("15_settings_busy");
        await("откат через 6 с", () -> !HelmClient.PENDING.any());
        step("откат", () -> check("без ответа места сняты через 6 с", !HelmClient.PENDING.any()), 2);
    }

    private static int ladderBase;

    static List<HelmData.Report> reportsOf(String from) {
        List<HelmData.Report> out = new ArrayList<>();
        for (HelmData.Report r : Helm.data().reports) if (r.fromName.equals(from)) out.add(r);
        return out;
    }

    static boolean gatePending() {
        return Gate.pending(player().getUUID());
    }
}
