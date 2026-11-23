package works.repka.ryshelm.server;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.perm.Perms;
import works.repka.ryshelm.HelmPerms;
import works.repka.ryshelm.data.HelmData;
import works.repka.ryshelm.data.Journal;
import works.repka.ryshelm.data.JournalLine;
import works.repka.ryshelm.logic.Kind;
import works.repka.ryshelm.logic.Said;
import works.repka.ryshelm.logic.Term;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Команды. Всегда {@code /rys admin …}, {@code /report} и {@code /sc}: их нет у FTB Essentials. Корневые имена FTB
 * ({@code /mute}, {@code /heal}, {@code /fly}, {@code /invsee}, {@code /enderchest}, {@code /trashcan}, {@code /kickme}…)
 * занимаются, только когда FTB Essentials не загружен (план 6, решение автора 18.11): привычки игроков не ломаются, а два
 * мода не спорят за одно имя.
 */
public final class Commands {
    /** Корни FTB Essentials, которые берёт RYSHelm без него. */
    static final List<String> FTB_ROOTS = List.of("mute", "unmute", "kickme", "enderchest", "trashcan", "heal", "feed", "extinguish", "fly", "god", "speed", "invsee", "kit");

    private Commands() {}

    public static boolean ftbEssentials() {
        return ModList.get() != null && ModList.get().isLoaded("ftbessentials");
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        Map<String, Supplier<LiteralArgumentBuilder<CommandSourceStack>>> sub = subcommands();
        LiteralArgumentBuilder<CommandSourceStack> admin = literal("admin");
        for (var e : sub.entrySet()) admin.then(e.getValue().get());
        d.register(literal("rys").then(admin).then(rules()).then(sub.get("kit").get()).then(literal("afk").executes(c -> {
            Afk.mark(c.getSource().getPlayerOrException());
            return ok(c.getSource(), tx(c.getSource(), "msg.afk.marked"));
        })));
        d.register(report("report"));
        d.register(literal("sc").requires(s -> Helm.can(s, HelmPerms.STAFF_CHAT))
            .then(argument("text", StringArgumentType.greedyString()).executes(c -> {
                CommandSourceStack s = c.getSource();
                String name = s.getPlayer() != null ? s.getPlayer().getGameProfile().getName() : tx(s, "msg.console");
                if (s.getPlayer() != null && Punish.muted(s.getPlayer().getUUID()) != null) return fail(s, tx(s, "msg.you_muted"));
                Chat.staff(s.getServer(), name, StringArgumentType.getString(c, "text"));
                return 1;
            })).executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                Chat.toggleStaffChat(p);
                return ok(c.getSource(), tx(c.getSource(), Chat.staffChatOn(p.getUUID()) ? "msg.staff_chat.on" : "msg.staff_chat.off"));
            }));
        if (!ftbEssentials()) {
            for (String root : FTB_ROOTS) {
                String name = root.equals("trashcan") ? "trash" : root;
                var b = sub.get(name);
                if (b == null) continue;
                // строитель отдаёт узел с именем подкоманды: корень собираем заново с теми же детьми
                LiteralArgumentBuilder<CommandSourceStack> node = b.get();
                LiteralArgumentBuilder<CommandSourceStack> r = literal(root).requires(node.getRequirement());
                if (node.getCommand() != null) r.executes(node.getCommand());
                for (var child : node.getArguments()) r.then(child);
                d.register(r);
            }
        }
    }

    private static Map<String, Supplier<LiteralArgumentBuilder<CommandSourceStack>>> subcommands() {
        Map<String, Supplier<LiteralArgumentBuilder<CommandSourceStack>>> m = new LinkedHashMap<>();
        m.put("warn", () -> literal("warn").requires(s -> Helm.can(s, HelmPerms.WARN)).then(argument("player", GameProfileArgument.gameProfile())
            .executes(c -> punish(c, Kind.WARN, 0, ""))
            .then(argument("reason", StringArgumentType.greedyString()).executes(c -> punish(c, Kind.WARN, 0, StringArgumentType.getString(c, "reason"))))));
        m.put("mute", () -> timed("mute", Kind.MUTE, HelmPerms.MUTE));
        m.put("unmute", () -> literal("unmute").requires(s -> Helm.can(s, HelmPerms.MUTE)).then(argument("player", GameProfileArgument.gameProfile())
            .executes(c -> lift(c, Kind.MUTE))));
        m.put("kick", () -> literal("kick").requires(s -> Helm.can(s, HelmPerms.KICK)).then(argument("player", GameProfileArgument.gameProfile())
            .executes(c -> punish(c, Kind.KICK, 0, ""))
            .then(argument("reason", StringArgumentType.greedyString()).executes(c -> punish(c, Kind.KICK, 0, StringArgumentType.getString(c, "reason"))))));
        m.put("ban", () -> timed("ban", Kind.BAN, HelmPerms.BAN_TEMP));
        m.put("unban", () -> literal("unban").requires(s -> Helm.can(s, HelmPerms.BAN_TEMP)).then(argument("player", GameProfileArgument.gameProfile())
            .executes(c -> lift(c, Kind.BAN))));
        m.put("history", () -> literal("history").requires(s -> Helm.can(s, HelmPerms.PLAYERS)).then(argument("player", GameProfileArgument.gameProfile())
            .executes(Commands::history)));
        m.put("note", () -> literal("note").requires(s -> Helm.can(s, HelmPerms.NOTE)).then(argument("player", GameProfileArgument.gameProfile())
            .then(argument("text", StringArgumentType.greedyString()).executes(c -> {
                GameProfile gp = profile(c);
                var a = new net.minecraft.nbt.CompoundTag();
                a.putUUID("target", gp.getId());
                a.putString("text", StringArgumentType.getString(c, "text"));
                Helm.data().player(gp.getId(), gp.getName());
                return result(c.getSource(), Requests.note(c.getSource().getPlayerOrException(), a), tx(c.getSource(), "msg.note_saved", gp.getName()));
            }))));
        m.put("tp", () -> onPlayer("tp", HelmPerms.TP, (s, t) -> Staff.tp(s.getPlayerOrException(), t)));
        m.put("tphere", () -> onPlayer("tphere", HelmPerms.TP, (s, t) -> Staff.tphere(s.getPlayerOrException(), t)));
        m.put("spectate", () -> onPlayer("spectate", HelmPerms.SPECTATE, (s, t) -> Staff.spectate(s.getPlayerOrException(), t)));
        m.put("tplast", () -> literal("tplast").requires(s -> Helm.can(s, HelmPerms.TP)).then(argument("player", GameProfileArgument.gameProfile()).executes(c -> {
            GameProfile gp = profile(c);
            return result(c.getSource(), Staff.tpLast(c.getSource().getPlayerOrException(), gp.getId()), "");
        })));
        m.put("tpx", () -> literal("tpx").requires(s -> Helm.can(s, HelmPerms.CHEAT)).then(argument("dimension", net.minecraft.commands.arguments.DimensionArgument.dimension()).executes(c ->
            result(c.getSource(), Staff.tpx(c.getSource().getPlayerOrException(), net.minecraft.commands.arguments.DimensionArgument.getDimension(c, "dimension").dimension().location().toString()), ""))));
        m.put("jump", () -> literal("jump").requires(s -> Helm.can(s, HelmPerms.CHEAT)).executes(c -> result(c.getSource(), Staff.jump(c.getSource().getPlayerOrException()), "")));
        m.put("back", () -> literal("back").requires(s -> Helm.can(s, HelmPerms.SPECTATE)).executes(c -> result(c.getSource(), Staff.unspectate(c.getSource().getPlayerOrException()), tx(c.getSource(), "msg.spectate.back"))));
        m.put("freeze", () -> literal("freeze").requires(s -> Helm.can(s, HelmPerms.FREEZE)).then(argument("player", EntityArgument.player()).executes(c ->
            ok(c.getSource(), Staff.freeze(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "player"))))));
        m.put("invsee", () -> literal("invsee").requires(s -> Helm.can(s, HelmPerms.INVSEE)).then(argument("player", EntityArgument.player()).executes(c -> {
            Staff.invsee(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "player"));
            return 1;
        })));
        m.put("enderchest", () -> literal("enderchest").requires(s -> Helm.can(s, HelmPerms.ENDER)).executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            Staff.enderChest(p, p);
            return 1;
        }).then(argument("player", EntityArgument.player()).requires(s -> Helm.can(s, HelmPerms.ENDER_OTHER)).executes(c -> {
            Staff.enderChest(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "player"));
            return 1;
        })));
        m.put("trash", () -> literal("trash").requires(s -> Helm.can(s, HelmPerms.TRASH)).executes(c -> {
            Staff.trash(c.getSource().getPlayerOrException());
            return 1;
        }));
        m.put("kickme", () -> literal("kickme").requires(s -> Helm.can(s, HelmPerms.KICKME)).executes(c -> {
            ServerPlayer me = c.getSource().getPlayerOrException();
            me.connection.disconnect(Texts.c(me, "msg.left_server"));
            return 1;
        }));
        m.put("heal", () -> cheat("heal", Staff::heal));
        m.put("feed", () -> cheat("feed", Staff::feed));
        m.put("extinguish", () -> cheat("extinguish", Staff::extinguish));
        m.put("fly", () -> cheat("fly", Staff::fly));
        m.put("god", () -> cheat("god", Staff::god));
        m.put("speed", () -> literal("speed").requires(s -> Helm.can(s, HelmPerms.CHEAT)).then(argument("times", FloatArgumentType.floatArg(0.1f, 10f))
            .executes(c -> ok(c.getSource(), Staff.speed(c.getSource().getPlayerOrException(), c.getSource().getPlayerOrException(), FloatArgumentType.getFloat(c, "times"))))
            .then(argument("player", EntityArgument.player()).executes(c ->
                ok(c.getSource(), Staff.speed(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "player"), FloatArgumentType.getFloat(c, "times")))))));
        m.put("move", () -> literal("move").requires(s -> Helm.can(s, HelmPerms.MOVE)).then(argument("player", GameProfileArgument.gameProfile()).executes(c -> {
            GameProfile gp = profile(c);
            return result(c.getSource(), Staff.move(c.getSource().getPlayerOrException(), gp.getId(), gp.getName()), tx(c.getSource(), "msg.move.done", gp.getName()));
        })));
        m.put("moderator", () -> literal("moderator").requires(s -> Helm.can(s, HelmPerms.ADMIN))
            .then(literal("add").then(argument("player", GameProfileArgument.gameProfile()).executes(c -> moderator(c, true))))
            .then(literal("remove").then(argument("player", GameProfileArgument.gameProfile()).executes(c -> moderator(c, false))))
            .then(literal("list").executes(c -> {
                Map<java.util.UUID, String> mods = Helm.data().moderators;
                return ok(c.getSource(), mods.isEmpty() ? tx(c.getSource(), "msg.mod.none") : tx(c.getSource(), "msg.mod.list", String.join(", ", mods.values())));
            })));
        m.put("journal", () -> literal("journal").requires(s -> Helm.can(s, HelmPerms.JOURNAL)).executes(c -> journal(c.getSource(), 10))
            .then(argument("count", IntegerArgumentType.integer(1, 100)).executes(c -> journal(c.getSource(), IntegerArgumentType.getInteger(c, "count")))));
        m.put("open", () -> literal("open").executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            var t = new net.minecraft.nbt.CompoundTag();
            t.putString("type", "open");
            works.repka.ryshelm.network.HelmNet.send(p, t);
            return 1;
        }));
        m.put("kit", () -> literal("kit").requires(s -> Helm.can(s, HelmPerms.KIT)).executes(c -> {
            CommandSourceStack s = c.getSource();
            var all = Kits.all();
            if (all.isEmpty()) return ok(s, tx(s, "ui.wheel.no_kits"));
            ServerPlayer p = s.getPlayer();
            for (Kits.Kit k : all) {
                long left = p == null ? 0 : Kits.left(p, k);
                var lang = Texts.lang(s);
                String state = left == 0 ? lang.t("ryshelm.msg.kit.ready") : left == Long.MAX_VALUE ? lang.t("ryshelm.msg.kit.taken") : lang.t("ryshelm.msg.kit.in", lang.termLeft(left));
                s.sendSuccess(() -> Component.literal(k.name + " · " + lang.kitRule(k.cooldown) + " · " + state), false);
            }
            return all.size();
        }).then(argument("name", StringArgumentType.word()).suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(Kits.all().stream().map(k -> k.name), b))
            .executes(c -> {
                Kits.Kit k = Kits.get(StringArgumentType.getString(c, "name"));
                if (k == null) return fail(c.getSource(), tx(c.getSource(), "msg.kit.no_such"));
                return result(c.getSource(), Kits.give(c.getSource().getPlayerOrException(), k, false, null), tx(c.getSource(), "msg.kit.given_self", k.name));
            })));
        m.put("kits", () -> literal("kits").requires(s -> Helm.can(s, HelmPerms.KIT_MANAGE))
            .then(literal("create").then(argument("name", StringArgumentType.word()).executes(c -> kitCreate(c, works.repka.ryshelm.logic.KitRule.ONCE))
                .then(argument("cooldown", StringArgumentType.word()).executes(c -> {
                    int cd;
                    try {
                        cd = works.repka.ryshelm.logic.KitRule.parse(StringArgumentType.getString(c, "cooldown"));
                    } catch (IllegalArgumentException e) {
                        return fail(c.getSource(), tx(c.getSource(), "msg.kit.bad_cooldown"));
                    }
                    return kitCreate(c, cd);
                }))))
            .then(literal("delete").then(argument("name", StringArgumentType.word()).executes(c -> {
                boolean done = Kits.delete(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name"));
                return done ? ok(c.getSource(), tx(c.getSource(), "msg.kit.deleted")) : fail(c.getSource(), tx(c.getSource(), "msg.kit.no_such"));
            })))
            .then(literal("give").then(argument("player", EntityArgument.player()).then(argument("name", StringArgumentType.word()).executes(c -> {
                Kits.Kit k = Kits.get(StringArgumentType.getString(c, "name"));
                if (k == null) return fail(c.getSource(), tx(c.getSource(), "msg.kit.no_such"));
                ServerPlayer target = EntityArgument.getPlayer(c, "player");
                return result(c.getSource(), Kits.give(target, k, true, c.getSource().getPlayer()), tx(c.getSource(), "msg.kit.given", k.name, target.getGameProfile().getName()));
            }))))
            .then(literal("starter").then(argument("name", StringArgumentType.word()).executes(c -> {
                String name = StringArgumentType.getString(c, "name");
                if (name.equals("none")) name = "";
                else if (Kits.get(name) == null) return fail(c.getSource(), tx(c.getSource(), "msg.kit.no_such"));
                Helm.settings().starterKit = name.toLowerCase(java.util.Locale.ROOT);
                Helm.saveSettings();
                Helm.log("settings", c.getSource().getPlayer(), null, "", name.isEmpty() ? Said.of("log.starter_kit.off") : Said.of("log.starter_kit", name), Said.NONE);
                return ok(c.getSource(), name.isEmpty() ? tx(c.getSource(), "msg.kit.starter.off") : tx(c.getSource(), "msg.kit.starter", name));
            }))));
        m.put("reload", () -> literal("reload").requires(s -> Helm.can(s, HelmPerms.ADMIN)).executes(c -> {
            Helm.loadRules();
            Gate.settingsChanged(c.getSource().getServer());
            Sync.helloAll(c.getSource().getServer());
            return ok(c.getSource(), tx(c.getSource(), "msg.rules.reloaded", Texts.n(c.getSource().getPlayer(), "ui.set.rules.items", Helm.rules().rules().size()), Helm.rules().version()));
        }));
        return m;
    }

    /** {@code /rys rules}: показать правила и принять их командой. */
    private static LiteralArgumentBuilder<CommandSourceStack> rules() {
        return literal("rules").executes(c -> {
            CommandSourceStack s = c.getSource();
            var r = Helm.rules();
            s.sendSuccess(() -> Component.literal(r.title().isEmpty() ? tx(s, "ui.rules.title") : r.title()).withStyle(ChatFormatting.GOLD), false);
            for (var rule : r.rules()) s.sendSuccess(() -> Component.literal(rule.number() + ". " + rule.title()).withStyle(ChatFormatting.WHITE)
                .append(rule.detail().isEmpty() ? Component.empty() : Component.literal("\n   " + rule.detail()).withStyle(ChatFormatting.GRAY)), false);
            return 1;
        }).then(literal("accept").executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            Gate.accept(p);
            return ok(c.getSource(), tx(c.getSource(), "msg.rules.accepted"));
        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> report(String name) {
        return literal(name).requires(s -> Helm.can(s, HelmPerms.REPORT)).then(argument("player", GameProfileArgument.gameProfile())
            .then(argument("text", StringArgumentType.greedyString()).executes(c -> {
                GameProfile gp = profile(c);
                String text = StringArgumentType.getString(c, "text");
                int rule = 0;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(?:п\\.?|p\\.?)?\\s*(\\d{1,2})[.)]?\\s+(.*)$").matcher(text);
                if (m.matches() && Helm.rules().rule(Integer.parseInt(m.group(1))) != null) {
                    rule = Integer.parseInt(m.group(1));
                    text = m.group(2);
                }
                Helm.data().player(gp.getId(), gp.getName());
                return result(c.getSource(), Reports.create(c.getSource().getPlayerOrException(), gp.getId(), rule, text), "");
            })));
    }

    interface OnPlayer {
        @Nullable String run(CommandSourceStack s, ServerPlayer target) throws CommandSyntaxException;
    }

    interface Cheat {
        String run(ServerPlayer op, ServerPlayer target);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> onPlayer(String name, Perms.Node node, OnPlayer body) {
        return literal(name).requires(s -> Helm.can(s, node)).then(argument("player", EntityArgument.player()).executes(c ->
            result(c.getSource(), body.run(c.getSource(), EntityArgument.getPlayer(c, "player")), "")));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> cheat(String name, Cheat body) {
        return literal(name).requires(s -> Helm.can(s, HelmPerms.CHEAT))
            .executes(c -> ok(c.getSource(), body.run(c.getSource().getPlayerOrException(), c.getSource().getPlayerOrException())))
            .then(argument("player", EntityArgument.player()).executes(c -> {
                ServerPlayer target = EntityArgument.getPlayer(c, "player");
                ServerPlayer op = c.getSource().getPlayer();
                return ok(c.getSource(), body.run(op != null ? op : target, target));
            }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> timed(String name, Kind kind, Perms.Node node) {
        return literal(name).requires(s -> Helm.can(s, node)).then(argument("player", GameProfileArgument.gameProfile())
            .then(argument("term", StringArgumentType.word())
                .executes(c -> punish(c, kind, term(c), ""))
                .then(argument("reason", StringArgumentType.greedyString()).executes(c -> punish(c, kind, term(c), StringArgumentType.getString(c, "reason"))))));
    }

    private static long term(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        try {
            return Term.parse(StringArgumentType.getString(c, "term"));
        } catch (IllegalArgumentException e) {
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal(tx(c.getSource(), "msg.bad_term"))).create();
        }
    }

    private static GameProfile profile(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Collection<GameProfile> all = GameProfileArgument.getGameProfiles(c, "player");
        return all.iterator().next();
    }

    /** Пункт правил в начале причины: {@code п.3 мат} или {@code 3 мат}. */
    static int[] ruleOf(String reason) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(?:п\\.?|p\\.?)\\s*(\\d{1,2})\\b\\s*(.*)$").matcher(reason.strip());
        if (m.matches()) return new int[] {Integer.parseInt(m.group(1)), m.start(2)};
        return new int[] {0, 0};
    }

    private static int punish(CommandContext<CommandSourceStack> c, Kind kind, long millis, String reasonRaw) throws CommandSyntaxException {
        GameProfile gp = profile(c);
        int[] r = ruleOf(reasonRaw);
        String reason = r[0] > 0 ? reasonRaw.strip().substring(r[1]) : reasonRaw;
        Helm.data().player(gp.getId(), gp.getName());
        String err = Punish.give(c.getSource().getServer(), c.getSource().getPlayer(), gp.getId(), gp.getName(), kind, millis, r[0], reason, true);
        return result(c.getSource(), err, tx(c.getSource(), "msg.punished." + kind.id(), gp.getName(), Texts.lang(c.getSource()).termOn(millis)));
    }

    private static int lift(CommandContext<CommandSourceStack> c, Kind kind) throws CommandSyntaxException {
        GameProfile gp = profile(c);
        String err = Punish.lift(c.getSource().getServer(), c.getSource().getPlayer(), gp.getId(), kind);
        return result(c.getSource(), err, tx(c.getSource(), kind == Kind.BAN ? "msg.lifted.ban" : "msg.lifted.mute", gp.getName()));
    }

    private static int kitCreate(CommandContext<CommandSourceStack> c, int cooldown) throws CommandSyntaxException {
        String key = Kits.create(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name"), cooldown);
        if (key == null) return fail(c.getSource(), tx(c.getSource(), "msg.kit.bad_name"));
        if (key.isEmpty()) return fail(c.getSource(), tx(c.getSource(), "msg.kit.empty_inv"));
        return ok(c.getSource(), tx(c.getSource(), "msg.kit.created", key, Texts.lang(c.getSource()).kitRule(cooldown)));
    }

    private static int moderator(CommandContext<CommandSourceStack> c, boolean on) throws CommandSyntaxException {
        GameProfile gp = profile(c);
        String err = Requests.moderator(c.getSource().getPlayer(), gp.getId(), gp.getName(), on);
        return result(c.getSource(), err, tx(c.getSource(), on ? "msg.mod.added" : "msg.mod.removed", gp.getName()));
    }

    private static int history(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        GameProfile gp = profile(c);
        List<HelmData.Punishment> list = Helm.data().of(gp.getId());
        if (list.isEmpty()) return ok(c.getSource(), tx(c.getSource(), "msg.history.none", gp.getName()));
        long now = System.currentTimeMillis();
        var lang = Texts.lang(c.getSource());
        for (HelmData.Punishment p : list) {
            String state = p.revoked > 0 ? " · " + lang.t("ryshelm.ui.card.revoked") : p.active(now) ? " · " + lang.t("ryshelm.ui.card.active") : "";
            c.getSource().sendSuccess(() -> Component.literal("#" + p.id + " " + Sync.dateTime(p.created) + " " + Views.history(lang, p, false) + state + " · " + Texts.who(lang, p.byName)
                + (p.reason.isEmpty() ? "" : " · " + p.reason)), false);
        }
        return list.size();
    }

    private static int journal(CommandSourceStack s, int count) {
        Journal j = Helm.journal();
        if (j == null) return 0;
        var list = j.query(e -> true, count);
        var lang = Texts.lang(s);
        for (int i = list.size() - 1; i >= 0; i--) {
            Journal.Entry e = list.get(i);
            String d = e.detail(lang), n = e.note(lang);
            String line = JournalLine.text(lang, e.type, Texts.who(lang, e.an), e.pn, d);
            s.sendSuccess(() -> Component.literal(Sync.dateTime(e.t) + " " + line
                + (n.isEmpty() ? "" : " · " + n)).withStyle(ChatFormatting.GRAY), false);
        }
        return list.size();
    }

    /** Текст ответа на языке того, кто дал команду (консоли по-английски). */
    static String tx(CommandSourceStack s, String key, Object... args) {
        return Texts.t(s.getPlayer(), key, args);
    }

    static int ok(CommandSourceStack s, String text) {
        if (!text.isEmpty()) s.sendSuccess(() -> Component.literal(text), false);
        return Command.SINGLE_SUCCESS;
    }

    static int fail(CommandSourceStack s, String text) {
        s.sendFailure(Component.literal(text));
        return 0;
    }

    static int result(CommandSourceStack s, @Nullable String err, String done) {
        return err != null ? fail(s, err) : ok(s, done);
    }
}
