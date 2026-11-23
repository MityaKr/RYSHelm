package works.repka.ryshelm.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import works.repka.ryscore.perm.Roles;
import works.repka.ryshelm.logic.Term;

/**
 * «Отошёл» и выход по простою (план 9). Игрок без движения, поворота, чата и команд {@code afkMinutes} минут помечается
 * «отошёл» (в списке игры и в окне «Сервер»); через {@code idleKickMinutes} без дела выходит с сервера, персонал не выходит.
 * {@code /rys afk} ставит отметку сразу, любое действие её снимает.
 */
public final class Afk {
    private static final class State {
        long active = System.currentTimeMillis();
        double x, y, z;
        float yRot, xRot;
        boolean afk;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    private Afk() {}

    static void clear() {
        STATES.clear();
    }

    public static boolean afk(UUID id) {
        State s = STATES.get(id);
        return s != null && s.afk;
    }

    /** Сколько игрок без дела, мс. */
    public static long idle(UUID id) {
        State s = STATES.get(id);
        return s == null ? 0 : System.currentTimeMillis() - s.active;
    }

    static void logout(ServerPlayer p) {
        STATES.remove(p.getUUID());
    }

    /** Действие игрока: чат, команда. */
    public static void active(ServerPlayer p) {
        State s = STATES.computeIfAbsent(p.getUUID(), k -> new State());
        s.active = System.currentTimeMillis();
        if (s.afk) set(p, s, false);
    }

    /** {@code /rys afk}: отметка сразу. */
    public static void mark(ServerPlayer p) {
        State s = STATES.computeIfAbsent(p.getUUID(), k -> new State());
        remember(p, s);
        s.active = System.currentTimeMillis() - Math.max(1, Helm.settings().afkMinutes) * Term.MINUTE;
        set(p, s, true);
    }

    private static void remember(ServerPlayer p, State s) {
        s.x = p.getX();
        s.y = p.getY();
        s.z = p.getZ();
        s.yRot = p.getYRot();
        s.xRot = p.getXRot();
    }

    private static void set(ServerPlayer p, State s, boolean afk) {
        s.afk = afk;
        p.refreshTabListName();
        Sync.broadcastRoster();
    }

    /** Раз в секунду. */
    static void tick(MinecraftServer server) {
        var settings = Helm.settings();
        long now = System.currentTimeMillis();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            State s = STATES.get(p.getUUID());
            if (s == null) {
                s = new State();
                remember(p, s);
                STATES.put(p.getUUID(), s);
                continue;
            }
            boolean moved = p.distanceToSqr(s.x, s.y, s.z) > 0.01 || Math.abs(p.getYRot() - s.yRot) > 0.5f || Math.abs(p.getXRot() - s.xRot) > 0.5f;
            // замороженный и наблюдатель за модератором не считаются отошедшими по движению: их двигает сервер
            if (moved && !Staff.frozen(p.getUUID())) {
                remember(p, s);
                s.active = now;
                if (s.afk) set(p, s, false);
                continue;
            }
            remember(p, s);
            long idle = now - s.active;
            if (!s.afk && settings.afkMinutes > 0 && idle >= settings.afkMinutes * Term.MINUTE) set(p, s, true);
            if (settings.idleKickMinutes > 0 && idle >= settings.idleKickMinutes * Term.MINUTE && !Roles.atLeast(p, Roles.MODERATOR)) {
                Helm.log("afk_kick", null, "сервер", p.getUUID(), p.getGameProfile().getName(), Term.label(idle / Term.MINUTE * Term.MINUTE) + " без дела", "");
                p.connection.disconnect(Texts.c(p, "msg.idle_kick", Texts.lang(p).term(settings.idleKickMinutes * Term.MINUTE)));
            }
        }
    }

    /** Имя в списке игры: «Grib_ok · отошёл» серым. */
    public static void tabName(PlayerEvent.TabListNameFormat e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !afk(p.getUUID())) return;
        Component base = e.getDisplayName() != null ? e.getDisplayName() : p.getName();
        e.setDisplayName(base.copy().withStyle(ChatFormatting.GRAY).append(Component.literal(" · ").append(Texts.c(null, "msg.afk.tab")).withStyle(ChatFormatting.DARK_GRAY)));
    }
}
