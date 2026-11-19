package works.repka.ryshelm.server;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import works.repka.ryshelm.data.HelmData;
import works.repka.ryshelm.logic.Term;

/**
 * Публичный вход (план 5.7): правила при входе и новичок. Пока правила не приняты, можно ходить и читать чат, нельзя
 * строить, ломать, открывать, бить, писать и телепортироваться. Новичок это состояние первых N часов игры.
 */
public final class Gate {
    private static final Set<UUID> PENDING = new HashSet<>();
    private static final Map<UUID, Long> REMINDED = new HashMap<>();

    private Gate() {}

    public static boolean pending(UUID id) {
        return PENDING.contains(id);
    }

    /** Новичок: первые N часов игры, если оператор включил (по умолчанию выключено). */
    public static boolean newbie(ServerPlayer p) {
        int hours = Helm.settings().newbieHours;
        return hours > 0 && Helm.played(p) < hours * Term.HOUR;
    }

    static boolean needs(HelmData.PlayerInfo info) {
        return Helm.settings().rulesOnJoin && !Helm.rules().isEmpty() && !Helm.rules().version().equals(info.rules);
    }

    /**
     * Ждать принятия правил: правила нужны и игрок не персонал. Модераторы и операторы правила пишут и включают сами, их не
     * запираем (ночные проверки 19.11: оператор, включивший правила, оставался без чата). Окно правил само им не открывается,
     * прочитать и принять можно командой {@code /rys rules}.
     */
    static boolean blocks(ServerPlayer p, HelmData.PlayerInfo info) {
        return needs(info) && !works.repka.ryscore.perm.Roles.atLeast(p, works.repka.ryscore.perm.Roles.MODERATOR);
    }

    static void login(ServerPlayer p, HelmData.PlayerInfo info) {
        if (blocks(p, info)) {
            PENDING.add(p.getUUID());
            Sync.hello(p);
        }
    }

    static void logout(ServerPlayer p) {
        PENDING.remove(p.getUUID());
        REMINDED.remove(p.getUUID());
    }

    /** Включили правила или поменяли их текст: всем в сети, кто не принял эту версию (кроме персонала), открыть окно. */
    public static void settingsChanged(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            HelmData.PlayerInfo info = Helm.data().player(p.getUUID(), p.getGameProfile().getName());
            boolean need = blocks(p, info);
            if (need) PENDING.add(p.getUUID());
            else PENDING.remove(p.getUUID());
        }
    }

    public static String accept(ServerPlayer p) {
        HelmData.PlayerInfo info = Helm.data().player(p.getUUID(), p.getGameProfile().getName());
        info.rules = Helm.rules().version();
        info.rulesAt = System.currentTimeMillis();
        Helm.dirty();
        PENDING.remove(p.getUUID());
        Sync.hello(p);
        Sync.staffChanged();
        return null;
    }

    /** Строка «Примите правила сервера» не чаще раза в 5 секунд. */
    static void remind(ServerPlayer p) {
        long now = System.currentTimeMillis();
        Long last = REMINDED.get(p.getUUID());
        if (last != null && now - last < 5000) return;
        REMINDED.put(p.getUUID(), now);
        Sync.notice(p, "rules", "row", "BOOK", "Примите правила сервера", "клик откроет правила", true);
    }

    /**
     * Новичок (план 5.7): первые N часов его нельзя звать к себе кроме своей команды. Ловит команду {@code /tpahere} в любом
     * виде ({@code /tpahere Имя}, {@code /rys place tpahere Имя}): её дают и FTB Essentials, и «Места» карты. Запрос из окна
     * карты идёт не командой, его закрыть нечем (нужен контракт ядра, см. отчёт А3).
     * @return причина отказа или null
     */
    static String summonDenied(ServerPlayer from, String command) {
        if (Helm.settings().newbieHours <= 0 || works.repka.ryscore.perm.Roles.atLeast(from, works.repka.ryscore.perm.Roles.MODERATOR)) return null;
        String[] w = command.strip().replaceFirst("^/", "").split("\\s+");
        for (int i = 0; i + 1 < w.length; i++) {
            String word = w[i].contains(":") ? w[i].substring(w[i].indexOf(':') + 1) : w[i];
            if (!word.equalsIgnoreCase("tpahere")) continue;
            ServerPlayer target = from.server.getPlayerList().getPlayerByName(w[i + 1]);
            if (target == null || target == from || !newbie(target)) return null;
            var teams = works.repka.ryscore.team.TeamsApi.get();
            if (teams.teamOf(target.getUUID()).equals(teams.teamOf(from.getUUID()))) return null;
            return target.getGameProfile().getName() + " новичок: первые " + Term.plural(Helm.settings().newbieHours, "час", "часа", "часов")
                + " звать к себе может только его команда";
        }
        return null;
    }

    /** Общий обработчик событий действия: строить, ломать, открывать, бить, телепорт. */
    public static void deny(Entity who, ICancellableEvent event) {
        if (!(who instanceof ServerPlayer p) || who instanceof FakePlayer || !PENDING.contains(p.getUUID())) return;
        event.setCanceled(true);
        remind(p);
    }
}
