package works.repka.ryshelm.server;

import com.mojang.authlib.GameProfile;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.UserWhiteListEntry;
import org.jetbrains.annotations.Nullable;

/**
 * Белый список окном (план 9): тот же список игры {@code whitelist.json}, что у команды {@code /whitelist}, ничего своего.
 * Ник ищется в кэше профилей сервера; на сервере без проверки входа берётся профиль без проверки. Игрока, которого сервер
 * никогда не видел, на сервере с проверкой добавляет команда игры (она спрашивает Mojang).
 */
public final class Whitelist {
    private Whitelist() {}

    public static boolean on(MinecraftServer s) {
        return s.getPlayerList().isUsingWhitelist();
    }

    public static List<String> names(MinecraftServer s) {
        String[] n = s.getPlayerList().getWhiteList().getUserList();
        Arrays.sort(n, String.CASE_INSENSITIVE_ORDER);
        return List.of(n);
    }

    static Optional<GameProfile> profile(MinecraftServer s, String name) {
        ServerPlayer online = s.getPlayerList().getPlayerByName(name);
        if (online != null) return Optional.of(online.getGameProfile());
        Optional<GameProfile> cached = s.getProfileCache() == null ? Optional.empty() : s.getProfileCache().get(name);
        if (cached.isPresent()) return cached;
        if (!s.usesAuthentication()) return Optional.of(UUIDUtil.createOfflineProfile(name));
        return Optional.empty();
    }

    /**
     * Включить или выключить. Включение кладёт в список всех, кто сейчас в сети (они уже играют на сервере, на сервере друзей
     * никто не вылетает от одного переключателя), дальше «строгий» вход не пускает тех, кого нет в списке.
     */
    public static void set(ServerPlayer actor, boolean on) {
        MinecraftServer s = actor.server;
        s.getPlayerList().setUsingWhiteList(on);
        s.setEnforceWhitelist(on);
        Helm.log("whitelist", actor, null, "", on ? "включён" : "выключен", "");
        if (on) {
            // включивший и все, кто сейчас в сети, попадают в список сами: от переключателя никто не вылетает
            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                if (s.getPlayerList().getWhiteList().isWhiteListed(p.getGameProfile())) continue;
                s.getPlayerList().getWhiteList().add(new UserWhiteListEntry(p.getGameProfile()));
                Helm.log("whitelist", actor, p.getUUID(), p.getGameProfile().getName(), "добавил", "был в сети при включении");
            }
            s.kickUnlistedPlayers(actor.createCommandSourceStack());
        }
    }

    public static @Nullable String add(ServerPlayer actor, String name) {
        MinecraftServer s = actor.server;
        if (!name.matches("[A-Za-z0-9_]{3,16}")) return "Ник: латиница, цифры и _, от 3 до 16 знаков";
        Optional<GameProfile> gp = profile(s, name);
        if (gp.isEmpty()) return name + " ещё не заходил: добавьте командой /whitelist add " + name;
        if (s.getPlayerList().getWhiteList().isWhiteListed(gp.get())) return gp.get().getName() + " уже в списке";
        s.getPlayerList().getWhiteList().add(new UserWhiteListEntry(gp.get()));
        Helm.log("whitelist", actor, gp.get().getId(), gp.get().getName(), "добавил", "");
        return null;
    }

    public static @Nullable String remove(ServerPlayer actor, String name) {
        MinecraftServer s = actor.server;
        Optional<GameProfile> gp = profile(s, name);
        if (gp.isEmpty() || !s.getPlayerList().getWhiteList().isWhiteListed(gp.get())) return name + " нет в списке";
        s.getPlayerList().getWhiteList().remove(gp.get());
        Helm.log("whitelist", actor, gp.get().getId(), gp.get().getName(), "убрал", "");
        s.kickUnlistedPlayers(actor.createCommandSourceStack());
        return null;
    }
}
