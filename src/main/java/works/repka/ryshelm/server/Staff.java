package works.repka.ryshelm.server;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.travel.TravelApi;
import works.repka.ryshelm.HelmPerms;
import works.repka.ryshelm.RYSHelm;
import works.repka.ryshelm.data.HelmData;
import works.repka.ryshelm.logic.Term;

/**
 * Действия персонала над игроком (план 5.3): к игроку и позвать (через {@code TravelApi}, с историей «назад»), наблюдать и
 * вернуться, заморозить, инвентарь и сундук Края, корзина, команды оператора (вылечить, накормить, полёт, бессмертие,
 * скорость), переставить вне сети. Права проверяет вызывающий ({@link Requests}, {@link Commands}).
 */
public final class Staff {
    private static final ResourceLocation SPEED_ID = ResourceLocation.fromNamespaceAndPath(RYSHelm.MODID, "speed");

    /** Заморозка: кто заморозил и где стоять. Живёт, пока модератор в сети (план 5.4). */
    private record Frozen(UUID by, String byName, ResourceKey<Level> dim, double x, double y, double z) {}

    private static final Map<UUID, Frozen> FROZEN = new HashMap<>();

    private Staff() {}

    static void clear() {
        FROZEN.clear();
    }

    // ---- перемещения ----

    /** «К игроку»: без запроса и паузы, но с историей «назад». */
    public static @Nullable String tp(ServerPlayer mod, ServerPlayer target) {
        TravelApi.Result r = TravelApi.get().teleport(mod, TravelApi.Trip.to(TravelApi.Spot.of(target)).reason(TravelApi.Reason.STAFF)
            .label(Component.literal(target.getGameProfile().getName())).skipCooldown().skipWarmup().skipClaims().build());
        if (r.status() == TravelApi.Status.DENIED) return r.message().getString();
        Helm.log("tp", mod, target.getUUID(), target.getGameProfile().getName(), "", Sync.where(target));
        return null;
    }

    /** «Позвать к себе» без запроса (план 5.3). */
    public static @Nullable String tphere(ServerPlayer mod, ServerPlayer target) {
        TravelApi.Result r = TravelApi.get().teleport(target, TravelApi.Trip.to(TravelApi.Spot.of(mod)).reason(TravelApi.Reason.STAFF)
            .label(Component.literal(mod.getGameProfile().getName())).skipCooldown().skipWarmup().skipClaims().build());
        if (r.status() == TravelApi.Status.DENIED) return r.message().getString();
        Helm.log("tphere", mod, target.getUUID(), target.getGameProfile().getName(), "", Sync.where(mod));
        Sync.notice(target, "tphere", "row", "USERS", "Модератор " + mod.getGameProfile().getName() + " позвал вас к себе", "", false);
        return null;
    }

    /** «Переставить»: игрок вне сети окажется у оператора при следующем входе. */
    public static @Nullable String move(ServerPlayer op, UUID target, String name) {
        if (op.server.getPlayerList().getPlayer(target) != null) return name + " в сети: «Позвать к себе»";
        HelmData.PlayerInfo info = Helm.data().player(target, name);
        info.moveTo = Helm.spot(op);
        info.moveBy = op.getGameProfile().getName();
        Helm.dirty();
        Helm.log("move", op, target, name, "", Sync.where(op));
        return null;
    }

    static void login(ServerPlayer p, HelmData.PlayerInfo info) {
        HelmData.Spot to = info.moveTo;
        if (to != null) {
            info.moveTo = null;
            Helm.dirty();
            ServerLevel level = level(p.server, to.dim);
            if (level != null) {
                TravelApi.get().teleport(p, TravelApi.Trip.to(TravelApi.Spot.of(level, to.x, to.y, to.z)).reason(TravelApi.Reason.STAFF)
                    .skipCooldown().skipWarmup().skipClaims().build());
            }
        }
        // заморозка переживает перезаход, если модератор ещё в сети
        Frozen f = FROZEN.get(p.getUUID());
        if (f != null) FROZEN.put(p.getUUID(), new Frozen(f.by, f.byName, p.level().dimension(), p.getX(), p.getY(), p.getZ()));
    }

    static void logout(ServerPlayer p) {
        // модератор вышел: его заморозки снимаются
        Iterator<Map.Entry<UUID, Frozen>> it = FROZEN.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Frozen> e = it.next();
            if (!e.getValue().by.equals(p.getUUID())) continue;
            it.remove();
            ServerPlayer target = p.server.getPlayerList().getPlayer(e.getKey());
            if (target != null) Sync.notice(target, "freeze", "row", "SNOW", "Заморозка снята", "", false);
            Helm.log("unfreeze", p.getUUID(), p.getGameProfile().getName(), e.getKey(), Helm.data().name(e.getKey()), "", "модератор вышел");
        }
    }

    static @Nullable ServerLevel level(MinecraftServer server, String dim) {
        ResourceLocation id = ResourceLocation.tryParse(dim);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    // ---- наблюдение ----

    public static boolean spectating(UUID id) {
        return Helm.data().spectating.containsKey(id);
    }

    /** «Наблюдать»: режим наблюдателя рядом с игроком, «Вернуться» ставит назад (план 5.3). */
    public static @Nullable String spectate(ServerPlayer mod, ServerPlayer target) {
        if (mod == target) return "Наблюдать за собой нельзя";
        HelmData data = Helm.data();
        if (!data.spectating.containsKey(mod.getUUID())) {
            HelmData.Spot back = Helm.spot(mod);
            back.mode = mod.gameMode.getGameModeForPlayer().getName();
            back.since = System.currentTimeMillis();
            data.spectating.put(mod.getUUID(), back);
        }
        HelmData.Spot s = data.spectating.get(mod.getUUID());
        s.target = target.getUUID();
        s.targetName = target.getGameProfile().getName();
        Helm.dirty();
        mod.setGameMode(GameType.SPECTATOR);
        mod.teleportTo(target.serverLevel(), target.getX(), target.getY() + 1.5, target.getZ(), target.getYRot(), mod.getXRot());
        Helm.log("spectate_start", mod, target.getUUID(), target.getGameProfile().getName(), "", Sync.where(target));
        Sync.hello(mod);
        return null;
    }

    /** «Вернуться»: на место и в прежний режим игры; в журнал строка «наблюдал за X · 4 мин · вернулся на место». */
    public static @Nullable String unspectate(ServerPlayer mod) {
        HelmData.Spot back = Helm.data().spectating.remove(mod.getUUID());
        if (back == null) return "Вы не наблюдаете";
        Helm.dirty();
        ServerLevel level = level(mod.server, back.dim);
        if (level == null) level = mod.server.overworld();
        mod.teleportTo(level, back.x, back.y, back.z, back.yRot, back.xRot);
        mod.setGameMode(GameType.byName(back.mode, GameType.SURVIVAL));
        long took = System.currentTimeMillis() - back.since;
        Helm.log("spectate", mod, back.target, back.targetName, "", Term.label(Math.max(Term.MINUTE, took)) + " · вернулся на место");
        Sync.hello(mod);
        return null;
    }

    /** Сервер упал, пока кто-то наблюдал: вернуть при входе. */
    static void restoreAfterCrash(MinecraftServer server) {
        if (!Helm.data().spectating.isEmpty()) RYSHelm.LOG.info("[ryshelm] наблюдателей с прошлого запуска: {}, вернутся кнопкой или командой", Helm.data().spectating.size());
    }

    // ---- заморозка ----

    public static boolean frozen(UUID id) {
        return FROZEN.containsKey(id);
    }

    /** Заморозить или разморозить. */
    public static String freeze(ServerPlayer mod, ServerPlayer target) {
        String name = target.getGameProfile().getName();
        if (FROZEN.remove(target.getUUID()) != null) {
            Helm.log("unfreeze", mod, target.getUUID(), name, "", "");
            Sync.notice(target, "freeze", "row", "SNOW", "Заморозка снята", "", false);
            return name + " разморожен";
        }
        FROZEN.put(target.getUUID(), new Frozen(mod.getUUID(), mod.getGameProfile().getName(), target.level().dimension(), target.getX(), target.getY(), target.getZ()));
        Helm.log("freeze", mod, target.getUUID(), name, "", Sync.where(target));
        Sync.notice(target, "freeze", "card", "SNOW", "Вас заморозил модератор", mod.getGameProfile().getName() + " · стойте на месте, он скоро ответит", true);
        return name + " заморожен";
    }

    static void tick(MinecraftServer server) {
        if (FROZEN.isEmpty()) return;
        for (Map.Entry<UUID, Frozen> e : FROZEN.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) continue;
            Frozen f = e.getValue();
            boolean away = p.level().dimension() != f.dim || p.distanceToSqr(f.x, f.y, f.z) > 0.04;
            if (away) {
                ServerLevel level = server.getLevel(f.dim);
                if (level != null) p.teleportTo(level, f.x, f.y, f.z, p.getYRot(), p.getXRot());
            }
            p.setDeltaMovement(0, Math.min(0, p.getDeltaMovement().y), 0);
        }
    }

    // ---- инвентарь, сундук Края, корзина ----

    public static void invsee(ServerPlayer mod, ServerPlayer target) {
        boolean edit = HelmPerms.INVSEE_EDIT.test(mod);
        mod.openMenu(new SimpleMenuProvider((id, inv, p) -> Peek.inventory(id, inv, target, edit),
            Component.literal("Инвентарь " + target.getGameProfile().getName() + (edit ? "" : " · только смотреть"))));
        Helm.log(edit ? "invsee_edit" : "invsee", mod, target.getUUID(), target.getGameProfile().getName(), "", edit ? "мог менять" : "только смотрел");
    }

    /** Свой сундук Края всем, чужой модератору (смотреть) и оператору (менять). */
    public static void enderChest(ServerPlayer viewer, ServerPlayer target) {
        if (viewer == target) {
            viewer.openMenu(new SimpleMenuProvider((id, inv, p) -> ChestMenu.threeRows(id, inv, target.getEnderChestInventory()),
                Component.translatable("container.enderchest")));
            return;
        }
        boolean edit = HelmPerms.INVSEE_EDIT.test(viewer);
        viewer.openMenu(new SimpleMenuProvider((id, inv, p) -> Peek.ender(id, inv, target, edit),
            Component.literal("Сундук Края " + target.getGameProfile().getName() + (edit ? "" : " · только смотреть"))));
        Helm.log("enderchest", viewer, target.getUUID(), target.getGameProfile().getName(), "", edit ? "мог менять" : "только смотрел");
    }

    /** Корзина: что положил, пропадает при закрытии. */
    public static void trash(ServerPlayer p) {
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> ChestMenu.threeRows(id, inv, new SimpleContainer(27)), Component.literal("Корзина · закрыть значит выбросить")));
    }

    // ---- оператор ----

    public static String heal(ServerPlayer op, ServerPlayer target) {
        target.setHealth(target.getMaxHealth());
        target.clearFire();
        target.getFoodData().setFoodLevel(20);
        log(op, target, "heal", "");
        return target.getGameProfile().getName() + ": здоровье полное";
    }

    public static String feed(ServerPlayer op, ServerPlayer target) {
        target.getFoodData().setFoodLevel(20);
        target.getFoodData().setSaturation(20f);
        log(op, target, "feed", "");
        return target.getGameProfile().getName() + " сыт";
    }

    public static String extinguish(ServerPlayer op, ServerPlayer target) {
        target.clearFire();
        log(op, target, "extinguish", "");
        return target.getGameProfile().getName() + " потушен";
    }

    public static String fly(ServerPlayer op, ServerPlayer target) {
        var a = target.getAbilities();
        a.mayfly = !a.mayfly;
        if (!a.mayfly) a.flying = false;
        target.onUpdateAbilities();
        log(op, target, "fly", a.mayfly ? "включил" : "выключил");
        Sync.hello(target);
        return target.getGameProfile().getName() + ": полёт " + (a.mayfly ? "включён" : "выключен");
    }

    public static String god(ServerPlayer op, ServerPlayer target) {
        var a = target.getAbilities();
        a.invulnerable = !a.invulnerable;
        target.onUpdateAbilities();
        log(op, target, "god", a.invulnerable ? "включил" : "выключил");
        Sync.hello(target);
        return target.getGameProfile().getName() + ": бессмертие " + (a.invulnerable ? "включено" : "выключено");
    }

    /** Скорость ходьбы и полёта: 1 обычная, до 10. */
    public static String speed(ServerPlayer op, ServerPlayer target, float times) {
        float k = Math.max(0.1f, Math.min(10f, times));
        AttributeInstance attr = target.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr != null) {
            attr.removeModifier(SPEED_ID);
            if (Math.abs(k - 1f) > 0.01f) attr.addPermanentModifier(new AttributeModifier(SPEED_ID, k - 1f, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
        target.getAbilities().setFlyingSpeed(0.05f * k);
        target.onUpdateAbilities();
        log(op, target, "speed", "×" + (k == Math.round(k) ? Integer.toString(Math.round(k)) : Float.toString(k)));
        return target.getGameProfile().getName() + ": скорость ×" + k;
    }

    private static void log(ServerPlayer op, ServerPlayer target, String type, String detail) {
        String self = op == target ? "себе" : "";
        Helm.log(type, op, op == target ? null : target.getUUID(), op == target ? "" : target.getGameProfile().getName(), detail, self);
    }

    public static boolean flying(ServerPlayer p) {
        return p.getAbilities().mayfly && !p.isCreative() && !p.isSpectator();
    }

    public static boolean god(ServerPlayer p) {
        return p.getAbilities().invulnerable && !p.isCreative() && !p.isSpectator();
    }
}
