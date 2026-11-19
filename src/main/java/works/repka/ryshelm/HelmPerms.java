package works.repka.ryshelm;

import java.util.List;
import net.minecraft.network.chat.Component;
import works.repka.ryscore.perm.Perms;
import works.repka.ryscore.perm.Roles;

/**
 * Узлы прав RYSHelm (план 5.1, макет 11). Объявляются в конструкторе мода на обеих сторонах: позже объявленный узел не
 * попадёт в PermissionAPI. Умолчание узла решает таблица ролей ({@code server.Helm#decide}), с LuckPerms решает он.
 */
public final class HelmPerms {
    private HelmPerms() {}

    private static Perms.Node node(String name, String role) {
        return Perms.node(RYSHelm.MODID, name, role, Component.translatable("ryshelm.perm." + name));
    }

    // модерация (левая таблица макета 11, по порядку)
    public static final Perms.Node WARN = node("warn", Roles.MODERATOR);
    public static final Perms.Node MUTE = node("mute", Roles.MODERATOR);
    public static final Perms.Node KICK = node("kick", Roles.MODERATOR);
    public static final Perms.Node BAN_TEMP = node("ban.temp", Roles.MODERATOR);
    public static final Perms.Node BAN = node("ban", Roles.OPERATOR);
    public static final Perms.Node REPORTS = node("reports", Roles.MODERATOR);
    public static final Perms.Node INVSEE = node("invsee", Roles.MODERATOR);
    public static final Perms.Node INVSEE_EDIT = node("invsee.edit", Roles.OPERATOR);
    public static final Perms.Node SPECTATE = node("spectate", Roles.MODERATOR);
    public static final Perms.Node FREEZE = node("freeze", Roles.MODERATOR);
    public static final Perms.Node JOURNAL = node("journal", Roles.MODERATOR);
    public static final Perms.Node REPORT = node("report", Roles.PLAYER);
    // карточка игрока
    public static final Perms.Node TP = node("tp", Roles.MODERATOR);
    public static final Perms.Node NOTE = node("note", Roles.MODERATOR);
    public static final Perms.Node ENDER_OTHER = node("enderchest.other", Roles.MODERATOR);
    public static final Perms.Node STAFF_CHAT = node("staffchat", Roles.MODERATOR);
    public static final Perms.Node PLAYERS = node("players", Roles.MODERATOR);
    // игроку
    public static final Perms.Node ENDER = node("enderchest", Roles.PLAYER);
    public static final Perms.Node TRASH = node("trash", Roles.PLAYER);
    public static final Perms.Node KICKME = node("kickme", Roles.PLAYER);
    public static final Perms.Node KIT = node("kit", Roles.PLAYER);
    // оператор
    public static final Perms.Node KIT_MANAGE = node("kit.manage", Roles.OPERATOR);
    public static final Perms.Node CHEAT = node("cheat", Roles.OPERATOR);
    public static final Perms.Node MOVE = node("move", Roles.OPERATOR);
    public static final Perms.Node ADMIN = node("admin", Roles.OPERATOR);

    /** Узлы левой таблицы «Модерация»; остальные узлы семьи идут в правую. */
    public static final List<Perms.Node> MODERATION = List.of(WARN, MUTE, KICK, BAN_TEMP, BAN, REPORTS, INVSEE, INVSEE_EDIT, SPECTATE,
        FREEZE, JOURNAL, TP, PLAYERS, NOTE, ENDER_OTHER, STAFF_CHAT, REPORT);

    /** Загружает класс: узлы объявляются при первом обращении. */
    public static void init() {}
}
