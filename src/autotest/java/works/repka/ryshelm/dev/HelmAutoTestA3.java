package works.repka.ryshelm.dev;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import works.repka.ryshelm.client.HelmClient;
import works.repka.ryshelm.client.RulesScreen;
import works.repka.ryshelm.client.ServerScreen;
import works.repka.ryshelm.client.View;
import works.repka.ryshelm.data.HelmData;
import works.repka.ryshelm.logic.Kind;
import works.repka.ryshelm.server.Gate;
import works.repka.ryshelm.server.Helm;
import works.repka.ryshelm.server.Sync;

import static works.repka.ryshelm.dev.HelmAutoTest.*;

/**
 * Сценарий этапа А3 в одном клиенте: вкладка «Жалобы» (снимок рядом с макетом 10), правила при входе (13), окно глазами
 * игрока с формой жалобы (14), чат персонала, новичок. Два настоящих клиента: {@code DuoTest}.
 */
final class HelmAutoTestA3 {
    static final boolean ON = true;

    private HelmAutoTestA3() {}

    static void filter(String f) {
        ServerScreen.filter = f;
    }

    static void punish(ServerScreen s, Kind kind, UUID target, String name) {
        s.openPunish(kind, target, name);
    }

    static void closePunish(ServerScreen s) {
        s.closePunish();
    }

    static void build() {
        // жалобы
        step("жалобы", () -> {
            for (HelmData.Report r : HelmAutoTest.reportsOf("Lena_K")) ServerScreen.report = r.id;
            ServerScreen.open("reports");
        }, 30);
        shot("10_reports");
        step("жалобы пришли", () -> {
            ListTag list = View.reports.getList("list", 10);
            check("во вкладке жалоб есть новые", list.size() >= 3);
            check("карточка жалобы с чатом", View.reports.getCompound("detail").getList("chat", 10).size() == 4);
        }, 2);
        step("взять жалобу", () -> {
            CompoundTag a = new CompoundTag();
            a.putLong("id", ServerScreen.report);
            act("report_take", a);
        }, 20);
        server("жалоба взята", () -> {
            HelmData.Report r = Helm.data().report(ServerScreen.report);
            check("жалоба взята оператором", r != null && r.state == HelmData.Report.State.TAKEN);
        });
        // правила при входе
        server("включить правила", () -> {
            Helm.settings().rulesOnJoin = true;
            Helm.saveSettings();
            Helm.data().player(player().getUUID(), "").rules = "";
            Gate.settingsChanged(server());
            Sync.helloAll(server());
        });
        await("окно правил открылось", () -> mc().screen instanceof RulesScreen && View.rules.getList("items", 10).size() > 0);
        step("пауза", () -> check("правила не приняты: сервер держит игрока", gatePending()), 20);
        shot("13_join_rules");
        step("чат до правил", () -> mc().player.connection.sendChat("до правил"), 10);
        step("принять", () -> {
            act("rules_accept", new CompoundTag());
            mc().setScreen(null);
        }, 20);
        server("правила приняты", () -> check("после «Принимаю» игрок свободен", !Gate.pending(player().getUUID())));
        // чат персонала
        step("чат персонала вкл", () -> act("staffchat", new CompoundTag()), 10);
        step("чат персонала", () -> check("переключатель чата персонала включился", HelmClient.staffChat), 2);
        step("чат персонала выкл", () -> act("staffchat", new CompoundTag()), 10);
        // новичок
        server("новичок", () -> {
            Helm.settings().newbieHours = 2;
            check("в новом мире игрок новичок", Gate.newbie(player()));
            Helm.settings().newbieHours = 0;
            check("выключили: не новичок", !Gate.newbie(player()));
            Helm.saveSettings();
        });
        // глазами игрока (макет 14): роль и данные окна закреплены, сервер не перезаписывает
        step("глазами игрока", () -> {
            HelmClient.pinned = true;
            HelmClient.role = "player";
            HelmClient.PERMS.clear();
            HelmClient.PERMS.add("ryshelm.report");
            HelmClient.PERMS.add("ryshelm.enderchest");
            HelmClient.PERMS.add("ryshelm.trash");
            HelmClient.PERMS.add("ryshelm.kickme");
            View.pinned = true;
            View.players = demoPlayers();
            View.card = null;
            ServerScreen.selected = null;
            ServerScreen.filter = "online";
            ServerScreen.open("players");
        }, 20);
        step("форма жалобы", () -> {
            if (mc().screen instanceof ServerScreen s) s.reportForm(GRIB, "Grib_ok");
        }, 20);
        shot("14_player_view");
        step("отпустить", () -> {
            HelmClient.pinned = false;
            View.pinned = false;
            mc().setScreen(null);
        }, 10);
    }

    /** Кто в сети, как в макете 14. */
    static CompoundTag demoPlayers() {
        CompoundTag t = new CompoundTag();
        t.putString("tab", "players");
        ListTag list = new ListTag();
        Object[][] rows = {
            {mc().player.getUUID(), "MityaYS", "operator", "Репка", 0xE07B53, "minecraft:overworld", -1, true},
            {VASYA, "Vasya_M", "moderator", "Северяне", 0x4FC3C8, "minecraft:overworld", 1400, false},
            {GRIB, "Grib_ok", "player", "Grib_ok", 0x7F7FE6, "minecraft:overworld", 210, false},
            {KOT, "Kot_Boris", "player", "Барсуки", 0xA97ADB, "minecraft:overworld", 1100, false},
            {LENA, "Lena_K", "player", "Репка", 0xE07B53, "minecraft:overworld", 80, true},
            {TOR, "Tor4ok", "player", "Репка", 0xE07B53, "minecraft:the_nether", -1, true}};
        for (Object[] r : rows) {
            CompoundTag c = new CompoundTag();
            c.putUUID("id", (UUID) r[0]);
            c.putString("name", (String) r[1]);
            c.putString("role", (String) r[2]);
            c.putString("team", (String) r[3]);
            c.putInt("color", (Integer) r[4]);
            c.putString("dim", (String) r[5]);
            if ((Integer) r[6] >= 0) c.putInt("dist", (Integer) r[6]);
            c.putBoolean("mine", (Boolean) r[7]);
            c.putBoolean("online", true);
            list.add(c);
        }
        t.put("players", list);
        return t;
    }
}
