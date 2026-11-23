package works.repka.ryshelm.server;

import com.mojang.brigadier.ParseResults;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import works.repka.ryshelm.HelmPerms;
import works.repka.ryshelm.RYSHelm;
import works.repka.ryshelm.data.HelmData;
import works.repka.ryshelm.logic.Lang;
import works.repka.ryshelm.logic.Term;

/**
 * Чат: заглушение (план 5.4: общий чат и команды {@code /msg}, {@code /tell}, {@code /w}, {@code /me}, {@code /teammsg}),
 * чат персонала (5.8, {@code /sc} и переключатель), запрет писать до принятия правил (5.7) и последние строки каждого
 * игрока для жалобы (5.5). Слушатель чата стоит первым: каналы RYSTeams тоже идут через {@code ServerChatEvent}.
 */
public final class Chat {
    /** Команды сообщений, которые гасит заглушение. */
    static final Set<String> MESSAGE_COMMANDS = Set.of("msg", "tell", "w", "me", "teammsg", "tm", "say", "r", "reply", "sc");
    /** Что можно до принятия правил. */
    static final Set<String> RULES_FREE = Set.of("rys", "help", "list", "kickme", "rules");
    private static final int KEEP = 100;

    private static final Map<UUID, Deque<HelmData.ChatLine>> LINES = new HashMap<>();
    private static final works.repka.ryshelm.logic.SpamGuard SPAM = new works.repka.ryshelm.logic.SpamGuard();
    private static final Set<UUID> STAFF_CHAT = new HashSet<>();
    /** Когда игрок вышел: его строки для жалобы живут час (разбор логики 19.11, п. 7). */
    private static final Map<UUID, Long> LEFT = new HashMap<>();
    static final long KEEP_AFTER_LEAVE = 3_600_000L;

    private Chat() {}

    static void clear() {
        LINES.clear();
        STAFF_CHAT.clear();
        LEFT.clear();
        SPAM.clear();
    }

    static void logout(ServerPlayer p) {
        // строки остаются час: жалоба на только что вышедшего игрока тоже приложит его чат
        SPAM.forget(p.getUUID());
        if (LINES.containsKey(p.getUUID())) LEFT.put(p.getUUID(), System.currentTimeMillis());
    }

    static void login(ServerPlayer p) {
        LEFT.remove(p.getUUID());
    }

    /** Раз в минуту: строки вышедших больше часа назад уходят. */
    static void prune(long now) {
        var it = LEFT.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (now - e.getValue() < KEEP_AFTER_LEAVE) continue;
            LINES.remove(e.getKey());
            it.remove();
        }
    }

    /** Последние строки игрока, старые сверху. */
    public static List<HelmData.ChatLine> last(UUID id, int n) {
        Deque<HelmData.ChatLine> d = LINES.get(id);
        if (d == null || n <= 0) return List.of();
        List<HelmData.ChatLine> all = new ArrayList<>(d);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }

    static void remember(UUID id, String text) {
        Deque<HelmData.ChatLine> d = LINES.computeIfAbsent(id, k -> new ArrayDeque<>());
        d.addLast(new HelmData.ChatLine(System.currentTimeMillis(), text.length() > 256 ? text.substring(0, 256) : text));
        while (d.size() > KEEP) d.removeFirst();
    }

    public static void onChat(ServerChatEvent e) {
        ServerPlayer p = e.getPlayer();
        if (!Helm.running() || p == null) return;
        if (Gate.pending(p.getUUID())) {
            e.setCanceled(true);
            Gate.remind(p);
            return;
        }
        HelmData.Punishment mute = Punish.muted(p.getUUID());
        if (mute != null) {
            e.setCanceled(true);
            tellMuted(p, mute);
            return;
        }
        Afk.active(p);
        if (spam(p, e.getRawText())) {
            e.setCanceled(true);
            return;
        }
        remember(p.getUUID(), e.getRawText());
        if (STAFF_CHAT.contains(p.getUUID()) && HelmPerms.STAFF_CHAT.test(p)) {
            e.setCanceled(true);
            staff(p.server, p.getGameProfile().getName(), e.getRawText());
        }
    }

    /** Антиспам (план 9): персонал не проверяется; третий отказ за 2 минуты глушит на несколько минут. */
    static boolean spam(ServerPlayer p, String text) {
        var s = Helm.settings();
        if (!s.antiSpam || works.repka.ryscore.perm.Roles.atLeast(p, works.repka.ryscore.perm.Roles.MODERATOR)) return false;
        var v = SPAM.check(p.getUUID(), text, System.currentTimeMillis(), s.spamMessages, s.spamSeconds);
        switch (v) {
            case OK -> {
                return false;
            }
            case TOO_FAST -> Sync.notice(p, "spam", "row", "CHAT", Texts.t(p, "msg.spam.fast", s.spamMessages, s.spamSeconds), "", true);
            case REPEAT -> Sync.notice(p, "spam", "row", "CHAT", Texts.t(p, "msg.spam.repeat"), "", true);
            case STRIKE -> {
                if (s.spamMuteMinutes > 0) {
                    Punish.give(p.server, null, p.getUUID(), p.getGameProfile().getName(), works.repka.ryshelm.logic.Kind.MUTE, s.spamMuteMinutes * Term.MINUTE, 0, Texts.t(p, "msg.spam.who"), true);
                    var last = Helm.data().active(p.getUUID(), works.repka.ryshelm.logic.Kind.MUTE, System.currentTimeMillis());
                    if (last != null) last.byName = Texts.t(p, "msg.spam.who");
                } else {
                    Sync.notice(p, "spam", "row", "CHAT", Texts.t(p, "msg.spam.hold"), "", true);
                }
            }
        }
        return true;
    }

    static void tellMuted(ServerPlayer p, HelmData.Punishment mute) {
        Lang lang = Texts.lang(p);
        String until = mute.expires == 0 ? lang.t("ryshelm.term.forever") : lang.t("ryshelm.msg.left", lang.termLeft(mute.expires - System.currentTimeMillis()));
        Sync.notice(p, "muted", "row", "MUTE", lang.t("ryshelm.msg.muted", until), mute.shown ? Punish.told(lang, mute) : "", true);
    }

    public static void onCommand(CommandEvent e) {
        if (!Helm.running()) return;
        ParseResults<CommandSourceStack> parse = e.getParseResults();
        ServerPlayer p = parse.getContext().getSource().getPlayer();
        if (p == null) return;
        String root = root(parse.getReader().getString());
        if (root.isEmpty()) return;
        if (!root.equals("afk") && !parse.getReader().getString().contains(" afk")) Afk.active(p);
        if (Gate.pending(p.getUUID()) && !RULES_FREE.contains(root)) {
            e.setCanceled(true);
            Gate.remind(p);
            return;
        }
        String newbie = Gate.summonDenied(p, parse.getReader().getString());
        if (newbie != null) {
            e.setCanceled(true);
            Sync.notice(p, "newbie", "row", "USERS", newbie, "", true);
            return;
        }
        if (MESSAGE_COMMANDS.contains(root)) {
            HelmData.Punishment mute = Punish.muted(p.getUUID());
            if (mute != null) {
                e.setCanceled(true);
                tellMuted(p, mute);
                return;
            }
            String text = parse.getReader().getString();
            remember(p.getUUID(), "/" + text.replaceFirst("^/", ""));
        }
    }

    /** Имя команды без косой и пространства: {@code minecraft:tell} даёт {@code tell}. */
    static String root(String command) {
        String s = command.strip();
        if (s.startsWith("/")) s = s.substring(1);
        int sp = s.indexOf(' ');
        if (sp >= 0) s = s.substring(0, sp);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        return s.toLowerCase(Locale.ROOT);
    }

    // ---- чат персонала ----

    public static boolean staffChatOn(UUID id) {
        return STAFF_CHAT.contains(id);
    }

    /** Переключатель колеса «Чат персонала»: обычные строки идут персоналу. */
    public static void toggleStaffChat(ServerPlayer p) {
        if (!STAFF_CHAT.remove(p.getUUID())) STAFF_CHAT.add(p.getUUID());
        Sync.hello(p);
    }

    /** Строка персоналу: всем, у кого есть право {@code ryshelm.staffchat}, и в лог сервера. */
    public static void staff(MinecraftServer server, String from, String text) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!HelmPerms.STAFF_CHAT.test(p)) continue;
            Component line = Texts.c(p, "msg.staff_chat.tag").withStyle(ChatFormatting.GOLD).append(" ")
                .append(Component.literal(from + ": ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(text).withStyle(ChatFormatting.WHITE));
            p.sendSystemMessage(line);
        }
        RYSHelm.LOG.info("[staff] {}: {}", from, text);
    }
}
