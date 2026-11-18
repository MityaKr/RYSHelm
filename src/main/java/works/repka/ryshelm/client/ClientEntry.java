package works.repka.ryshelm.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import works.repka.ryscore.action.Action;
import works.repka.ryscore.action.Actions;
import works.repka.ryscore.dock.DockButton;
import works.repka.ryscore.ui.Icons;
import works.repka.ryshelm.RYSHelm;

/**
 * Клиентская часть RYSHelm: клавиша окна (без умолчания: в RepYS все буквы заняты), кнопка «Сервер» в панели семьи,
 * действия колеса ядра (кольца «Моё», «Модерация», «Оператор»), сброс при выходе.
 */
public final class ClientEntry {
    static final String CATEGORY = "key.categories.ryshelm";
    public static final KeyMapping OPEN = new KeyMapping("key.ryshelm.open", KeyConflictContext.IN_GAME, InputConstants.UNKNOWN, CATEGORY);

    private ClientEntry() {}

    public static void init(IEventBus modBus) {
        modBus.addListener((RegisterKeyMappingsEvent e) -> e.register(OPEN));
        modBus.addListener((FMLClientSetupEvent e) -> e.enqueueWork(ClientEntry::setup));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            HelmClient.reset();
            View.reset();
        });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            while (OPEN.consumeClick()) ServerScreen.open(null);
        });
    }

    /** Имя клавиши окна для уведомлений или пустая строка. */
    static String keyName() {
        return OPEN.isUnbound() ? "" : OPEN.getTranslatedKeyMessage().getString();
    }

    static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(RYSHelm.MODID, path);
    }

    static Component tr(String path) {
        return Component.translatable("ryshelm.action." + path);
    }

    private static void setup() {
        DockButton.builder("ryshelm_server").label(Component.translatable("ryshelm.dock")).description(() -> Component.translatable("ryshelm.dock.desc"))
            .tile(0xFF3F7A57).icon(Icons.Shape.SERVER).key(OPEN).order(40).action(() -> ServerScreen.open(null)).register();
        Actions.coversDock("ryshelm_server");
        actions();
    }

    private static Action.State need(String node, String why) {
        if (!HelmClient.present) return Action.State.off("На сервере нет RYSHelm");
        return HelmClient.can(node) ? Action.State.ready() : Action.State.off(why);
    }

    private static Action.State toggle(String node, BooleanSupplier on) {
        Action.State s = need(node, "Нет права");
        return s.usable() && on.getAsBoolean() ? Action.State.active() : s;
    }

    private static void actions() {
        // окна и «Моё»
        Action.builder(id("open")).label(tr("open")).icon(Icons.Shape.SERVER).group(Action.Group.SERVER).run(() -> ServerScreen.open(null)).register();
        Action.builder(id("rules")).label(tr("rules")).icon(Icons.Shape.BOOK).group(Action.Group.SERVER).run(() -> ServerScreen.open("rules")).register();
        Action.builder(id("ender_chest")).label(tr("ender_chest")).icon(Icons.Shape.CHEST).group(Action.Group.SERVER)
            .state(() -> need("ryshelm.enderchest", "Нет права")).run(() -> HelmClient.action("ender")).register();
        Action.builder(id("trash")).label(tr("trash")).icon(Icons.Shape.TRASH).group(Action.Group.SERVER)
            .state(() -> need("ryshelm.trash", "Нет права")).run(() -> HelmClient.action("trash")).register();
        // модерация
        Action.builder(id("players")).label(tr("players")).icon(Icons.Shape.USERS).group(Action.Group.MODERATION).run(() -> ServerScreen.open("players")).register();
        Action.builder(id("reports")).label(tr("reports")).icon(Icons.Shape.FLAG).group(Action.Group.MODERATION).badge(() -> HelmClient.reports)
            .state(() -> need("ryshelm.reports", "Нет права")).run(() -> ServerScreen.open("reports")).register();
        Action.builder(id("journal")).label(tr("journal")).icon(Icons.Shape.LIST).group(Action.Group.MODERATION)
            .state(() -> need("ryshelm.journal", "Нет права")).run(() -> ServerScreen.open("journal")).register();
        Action.builder(id("staff_chat")).label(tr("staff_chat")).icon(Icons.Shape.CHAT).group(Action.Group.MODERATION)
            .state(() -> toggle("ryshelm.staffchat", () -> HelmClient.staffChat)).run(() -> HelmClient.action("staffchat")).register();
        Action.builder(id("spectate")).label(tr("spectate")).icon(Icons.Shape.EYE).group(Action.Group.MODERATION)
            .state(() -> need("ryshelm.spectate", "Нет права")).list(() -> players("spectate", "ryshelm.spectate")).register();
        Action.builder(id("spectate_return")).label(tr("spectate_return")).icon(Icons.Shape.UNDO).group(Action.Group.MODERATION)
            .state(() -> !HelmClient.present ? Action.State.off("На сервере нет RYSHelm") : HelmClient.spectating ? Action.State.ready() : Action.State.off("Вы не наблюдаете"))
            .run(() -> HelmClient.action("spectate_return")).register();
        // оператор
        Action.builder(id("fly")).label(tr("fly")).icon(Icons.Shape.FEATHER).group(Action.Group.OPERATOR)
            .state(() -> toggle("ryshelm.cheat", () -> HelmClient.fly)).run(() -> cheat("fly", 0)).register();
        Action.builder(id("god")).label(tr("god")).icon(Icons.Shape.SHIELD).group(Action.Group.OPERATOR)
            .state(() -> toggle("ryshelm.cheat", () -> HelmClient.god)).run(() -> cheat("god", 0)).register();
        Action.builder(id("heal")).label(tr("heal")).icon(Icons.Shape.HEART).group(Action.Group.OPERATOR)
            .state(() -> need("ryshelm.cheat", "Нет права")).run(() -> cheat("heal", 0)).register();
        Action.builder(id("feed")).label(tr("feed")).icon(Icons.Shape.FORK).group(Action.Group.OPERATOR)
            .state(() -> need("ryshelm.cheat", "Нет права")).run(() -> cheat("feed", 0)).register();
        Action.builder(id("speed")).label(tr("speed")).icon(Icons.Shape.SPEED).group(Action.Group.OPERATOR)
            .state(() -> need("ryshelm.cheat", "Нет права")).list(ClientEntry::speeds).register();
        Action.builder(id("invsee")).label(tr("invsee")).icon(Icons.Shape.CHEST).group(Action.Group.OPERATOR)
            .state(() -> need("ryshelm.invsee", "Нет права")).list(() -> players("invsee", "ryshelm.invsee")).register();
    }

    private static void cheat(String what, float value) {
        CompoundTag a = new CompoundTag();
        a.putString("what", what);
        if (value > 0) a.putFloat("value", value);
        HelmClient.action("cheat", a);
    }

    private static List<Action> speeds() {
        List<Action> out = new ArrayList<>();
        for (int k : new int[] {1, 2, 3, 5}) {
            out.add(Action.builder(id("speed/" + k)).label(Component.literal("×" + k)).icon(Icons.Shape.SPEED).group(Action.Group.OPERATOR)
                .notBindable().run(() -> cheat("speed", k)).build());
        }
        return out;
    }

    /** Игроки в сети списком рядом с колесом: для «Наблюдать» и «Инвентарь». */
    private static List<Action> players(String action, String node) {
        List<Action> out = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.player == null || !HelmClient.can(node)) return out;
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            UUID id = info.getProfile().getId();
            if (id.equals(mc.player.getUUID())) continue;
            String name = info.getProfile().getName();
            out.add(Action.builder(id(action + "/" + name.toLowerCase())).label(Component.literal(name)).icon(Icons.Shape.USER).group(Action.Group.MODERATION)
                .notBindable().run(() -> HelmClient.onTarget(action, id)).build());
        }
        out.sort((a, b) -> a.label().getString().compareToIgnoreCase(b.label().getString()));
        return out;
    }
}
