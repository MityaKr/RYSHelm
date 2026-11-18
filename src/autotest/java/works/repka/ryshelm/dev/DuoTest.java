package works.repka.ryshelm.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryshelm.RYSHelm;
import works.repka.ryshelm.client.HelmClient;
import works.repka.ryshelm.client.RulesScreen;
import works.repka.ryshelm.client.View;
import works.repka.ryshelm.logic.Term;
import works.repka.ryshelm.network.HelmNet;

/**
 * Настоящий сервер и два клиента (как {@code DuoTest} RYSTeams): правила при входе, жалоба и её разбор, заморозка, заглушение,
 * наблюдение, чат персонала. Alpha оператор, Beta игрок. Запуск {@code tools/duo.sh}, вердикты строками {@code [duo]} в
 * {@code run-duo/*}/logs/latest.log. Клиенты договариваются строками чата.
 */
@EventBusSubscriber(modid = RYSHelm.MODID, value = Dist.CLIENT)
public final class DuoTest {
    private record Step(String name, BooleanSupplier ready, Runnable action, int pause) {}

    static final String ADDRESS = "localhost:25578";
    private static final String ROLE = System.getProperty("ryshelm.duo");
    private static final List<Step> STEPS = new ArrayList<>();
    private static final List<String> CHAT = new ArrayList<>();
    private static boolean built;
    private static int index, wait, waited, shots = 1, failures;
    private static Vec3 mark = Vec3.ZERO;

    private DuoTest() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post e) {
        if (ROLE == null) return;
        if (!built) {
            built = true;
            if (ROLE.equals("alpha")) alpha();
            else beta();
        }
        if (wait > 0) {
            wait--;
            return;
        }
        if (index >= STEPS.size()) return;
        Step s = STEPS.get(index);
        if (!s.ready.getAsBoolean()) {
            if (++waited % 600 == 0) RYSHelm.LOG.info("[duo] жду шаг {}", s.name);
            if (waited > 6000) {
                RYSHelm.LOG.error("[duo] FAILED: не дождались шага {}", s.name);
                Minecraft.getInstance().stop();
            }
            return;
        }
        waited = 0;
        try {
            RYSHelm.LOG.info("[duo] шаг {}", s.name);
            s.action.run();
        } catch (Throwable t) {
            failures++;
            RYSHelm.LOG.error("[duo] шаг {} упал", s.name, t);
        }
        wait = s.pause;
        index++;
    }

    @SubscribeEvent
    public static void chat(ClientChatReceivedEvent e) {
        if (ROLE != null) CHAT.add(e.getMessage().getString());
    }

    static Minecraft mc() {
        return Minecraft.getInstance();
    }

    static void step(String name, BooleanSupplier ready, Runnable action, int pause) {
        STEPS.add(new Step(name, ready, action, pause));
    }

    static void step(String name, Runnable action, int pause) {
        step(name, () -> true, action, pause);
    }

    static void shot(String name) {
        step("shot " + name, () -> Screenshot.grab(mc().gameDirectory, "duo_" + ROLE + "_" + (shots++) + "_" + name + ".png", mc().getMainRenderTarget(), m -> {}), 4);
    }

    static void check(String what, boolean ok) {
        if (!ok) failures++;
        RYSHelm.LOG.info("[duo] {} {}", ok ? "OK  " : "FAIL", what);
    }

    static boolean heard(String part) {
        return CHAT.stream().anyMatch(s -> s.contains(part));
    }

    static void say(String text) {
        mc().player.connection.sendChat(text);
    }

    static UUID other(String name) {
        for (PlayerInfo info : mc().getConnection().getOnlinePlayers()) if (info.getProfile().getName().equals(name)) return info.getProfile().getId();
        throw new IllegalStateException("нет игрока " + name);
    }

    static boolean online(String name) {
        return mc().getConnection() != null && mc().getConnection().getOnlinePlayers().stream().anyMatch(i -> i.getProfile().getName().equals(name));
    }

    static void act(String action, CompoundTag a) {
        HelmNet.action(action, a);
    }

    static CompoundTag target(String name) {
        CompoundTag a = new CompoundTag();
        a.putUUID("target", other(name));
        return a;
    }

    static void connect() {
        step("title", () -> mc().screen != null && mc().level == null && mc().getOverlay() == null, () -> {
            mc().options.pauseOnLostFocus = false;
            mc().options.onboardAccessibility = false;
            mc().options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0);
            ThemeManager.select("ryscore:slate");
            ConnectScreen.startConnecting(new TitleScreen(), mc(), ServerAddress.parseString(ADDRESS), new ServerData("duo", ADDRESS, ServerData.Type.OTHER), false, null);
        }, 20);
        step("in world", () -> mc().player != null && mc().level != null && HelmClient.present, () -> {}, 40);
    }

    static void finish() {
        RYSHelm.LOG.info(failures == 0 ? "[duo] PASS" : "[duo] FAILED: {}", failures);
    }

    private static void beta() {
        connect();
        // правила при входе: Alpha включила их до входа Beta или сразу после
        step("окно правил", () -> mc().screen instanceof RulesScreen && View.rules.getList("items", 10).size() > 0,
            () -> check("правила при входе открылись у игрока", HelmClient.rulesPending), 10);
        shot("13_join_rules");
        step("чат до правил", () -> say("до-правил"), 20);
        step("принять", () -> {
            act("rules_accept", new CompoundTag());
            mc().setScreen(null);
        }, 20);
        step("принято", () -> !HelmClient.rulesPending, () -> say("beta-accepted"), 20);
        // жалоба на Alpha
        step("жалоба", () -> {
            CompoundTag a = target("Alpha");
            a.putInt("rule", 3);
            a.putString("text", "проверка жалобы из duo");
            act("report", a);
        }, 20);
        step("отправлена", () -> check("жалоба отправлена", HelmClient.RECENT.contains("report_sent")), 2);
        step("сказать", () -> say("beta-reported"), 10);
        step("рассмотрена", () -> HelmClient.RECENT.contains("report_done"), () -> check("автору пришло «Жалоба рассмотрена»", true), 5);
        // заморозка
        step("ждём заморозку", () -> heard("alpha-froze") && HelmClient.frozen, () -> mark = mc().player.position(), 5);
        step("идём", () -> mc().options.keyUp.setDown(true), 40);
        step("стоп", () -> mc().options.keyUp.setDown(false), 10);
        step("стоим", () -> {
            double moved = mc().player.position().distanceTo(mark);
            check("замороженный не сдвинулся (" + String.format("%.2f", moved) + ")", moved < 0.6);
            say("beta-frozen-checked");
        }, 10);
        step("ждём разморозку", () -> heard("alpha-unfroze") && !HelmClient.frozen, () -> mark = mc().player.position(), 5);
        step("идём снова", () -> mc().options.keyUp.setDown(true), 30);
        step("стоп 2", () -> mc().options.keyUp.setDown(false), 10);
        step("сдвинулись", () -> {
            double moved = mc().player.position().distanceTo(mark);
            check("после разморозки игрок идёт (" + String.format("%.2f", moved) + ")", moved > 1.0);
            say("beta-moved");
        }, 10);
        // заглушение
        step("ждём мут", () -> HelmClient.mutedUntil > 0, () -> say("сообщение-под-мутом"), 40);
        step("ждём снятия", () -> HelmClient.mutedUntil == 0, () -> say("после-снятия-мута"), 20);
        // наблюдение и чат персонала Alpha
        step("ждём конца", () -> heard("alpha-done"), () -> {
            check("чат персонала не дошёл до игрока", !heard("[Персонал]"));
            check("игрок не получил кнопок персонала", !HelmClient.can("ryshelm.mute"));
            finish();
            say("beta-finished");
        }, 20);
        step("quit", () -> mc().stop(), 0);
    }

    private static void alpha() {
        connect();
        step("оператор", () -> check("Alpha оператор", "operator".equals(HelmClient.role)), 2);
        step("правила при входе", () -> {
            CompoundTag a = new CompoundTag();
            a.putBoolean("rulesOnJoin", true);
            act("settings", a);
        }, 10);
        step("Beta в сети", () -> online("Beta"), () -> {}, 40);
        step("Beta приняла", () -> heard("beta-accepted"), () -> check("строка до правил не прошла в чат", !heard("до-правил")), 5);
        // жалоба
        step("жалоба пришла", () -> heard("beta-reported") && HelmClient.reports > 0, () -> {
            check("модератору пришло уведомление о жалобе", HelmClient.RECENT.contains("report_new"));
            View.request("reports", null, null, 0, "", 0, "");
        }, 20);
        step("закрыть жалобу", () -> View.reports.getList("list", 10).size() > 0, () -> {
            CompoundTag r = View.reports.getList("list", 10).getCompound(0);
            check("жалоба от Beta в списке", r.getString("from").equals("Beta"));
            CompoundTag a = new CompoundTag();
            a.putLong("id", r.getLong("id"));
            a.putString("outcome", "rejected");
            act("report_close", a);
            HelmClient.action("close");
        }, 20);
        // заморозка
        step("заморозить", () -> act("freeze", target("Beta")), 10);
        step("сказать", () -> say("alpha-froze"), 10);
        step("Beta проверила", () -> heard("beta-frozen-checked"), () -> act("freeze", target("Beta")), 10);
        step("сказать 2", () -> say("alpha-unfroze"), 10);
        // заглушение
        step("Beta пошла", () -> heard("beta-moved"), () -> {
            CompoundTag a = target("Beta");
            a.putString("kind", "mute");
            a.putLong("term", 10 * Term.MINUTE);
            a.putInt("rule", 3);
            a.putString("reason", "проверка");
            a.putBoolean("shown", true);
            act("punish", a);
        }, 80);
        step("мут держит", () -> {
            check("сообщение под мутом не дошло", !heard("сообщение-под-мутом"));
            View.request("players", other("Beta"), null, 0, "", 0, "");
        }, 20);
        step("снять мут", () -> View.card != null && View.card.contains("mute"), () -> {
            CompoundTag a = new CompoundTag();
            a.putLong("id", View.card.getLong("mute"));
            act("revoke", a);
            HelmClient.action("close");
        }, 10);
        step("после снятия", () -> heard("после-снятия-мута"), () -> check("после снятия мута сообщение дошло", true), 5);
        // наблюдение
        step("наблюдать", () -> act("spectate", target("Beta")), 30);
        step("наблюдатель", () -> {
            check("Alpha в режиме наблюдателя", mc().player.isSpectator());
            check("флаг наблюдения пришёл", HelmClient.spectating);
        }, 5);
        shot("spectate");
        step("вернуться", () -> act("spectate_return", new CompoundTag()), 30);
        step("вернулась", () -> check("Alpha вернулась из наблюдения", !mc().player.isSpectator() && !HelmClient.spectating), 5);
        // чат персонала
        step("чат персонала", () -> mc().player.connection.sendCommand("sc привет персоналу"), 20);
        step("услышала", () -> check("чат персонала дошёл до оператора", heard("[Персонал]")), 2);
        step("журнал", () -> View.request("journal", null, null, 0, "", 1, ""), 20);
        step("журнал пришёл", () -> {
            check("в журнале заморозка, мут, наблюдение, жалоба", View.journal.getList("entries", 10).size() >= 6);
            HelmClient.action("close");
            say("alpha-done");
        }, 5);
        step("Beta закончила", () -> heard("beta-finished"), () -> {
            finish();
            mc().player.connection.sendCommand("stop");
        }, 40);
        step("quit", () -> mc().stop(), 0);
    }
}
