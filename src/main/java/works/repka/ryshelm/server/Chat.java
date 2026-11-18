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
    private static final Set<UUID> STAFF_CHAT = new HashSet<>();

    private Chat() {}

    static void clear() {
        LINES.clear();
        STAFF_CHAT.clear();
    }

    static void logout(ServerPlayer p) {
        // строки остаются до перезапуска: жалоба на вышедшего игрока тоже приложит его чат
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
        remember(p.getUUID(), e.getRawText());
        if (STAFF_CHAT.contains(p.getUUID()) && HelmPerms.STAFF_CHAT.test(p)) {
            e.setCanceled(true);
            staff(p.server, p.getGameProfile().getName(), e.getRawText());
        }
    }

    static void tellMuted(ServerPlayer p, HelmData.Punishment mute) {
        String until = mute.expires == 0 ? "навсегда" : "ещё " + Term.left(mute.expires - System.currentTimeMillis());
        Sync.notice(p, "muted", "row", "MUTE", "Вы заглушены · " + until, mute.shown ? Punish.told(mute) : "", true);
    }

    public static void onCommand(CommandEvent e) {
        if (!Helm.running()) return;
        ParseResults<CommandSourceStack> parse = e.getParseResults();
        ServerPlayer p = parse.getContext().getSource().getPlayer();
        if (p == null) return;
        String root = root(parse.getReader().getString());
        if (root.isEmpty()) return;
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
        Component line = Component.literal("[Персонал] ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(from + ": ").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(text).withStyle(ChatFormatting.WHITE));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) if (HelmPerms.STAFF_CHAT.test(p)) p.sendSystemMessage(line);
        RYSHelm.LOG.info("[staff] {}: {}", from, text);
    }
}
