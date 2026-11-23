package works.repka.ryshelm.server;

import com.google.gson.reflect.TypeToken;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import works.repka.ryshelm.RYSHelm;
import works.repka.ryshelm.data.HelmData;
import works.repka.ryshelm.logic.KitRule;
import works.repka.ryshelm.logic.Term;

/**
 * Наборы (план 9: {@code /kit}, стартовый набор для публичных серверов). Оператор собирает набор из своего инвентаря,
 * игрок берёт его командой, из колеса или получает сам при первом входе (стартовый, имя в настройках). Файл
 * {@code world/ryshelm/kits.json}, предметы в SNBT с компонентами 1.21. Когда кто брал, хранит {@code HelmData}.
 */
public final class Kits {
    /** Набор: имя, пауза, предметы. */
    public static final class Kit {
        public String name = "";
        public int cooldown = KitRule.ONCE;
        public List<String> items = new ArrayList<>();
    }

    private static Map<String, Kit> kits = new LinkedHashMap<>();

    private Kits() {}

    static Path file(MinecraftServer s) {
        return Helm.worldDir(s).resolve("ryshelm").resolve("kits.json");
    }

    static void load(MinecraftServer s) {
        Map<String, Kit> read = Helm.readJson(file(s), new TypeToken<LinkedHashMap<String, Kit>>() {}.getType(), new LinkedHashMap<>());
        kits = read == null ? new LinkedHashMap<>() : read;
    }

    static void save(MinecraftServer s) {
        Helm.writeJson(file(s), kits);
    }

    public static List<Kit> all() {
        return new ArrayList<>(kits.values());
    }

    public static @Nullable Kit get(String name) {
        return kits.get(name.toLowerCase(Locale.ROOT));
    }

    /** Набор из инвентаря оператора: рюкзак, панель, броня, вторая рука. */
    public static String create(ServerPlayer op, String name, int cooldown) {
        String key = name.toLowerCase(Locale.ROOT);
        if (!key.matches("[a-z0-9_\\-]{1,24}")) return null;
        Kit kit = new Kit();
        kit.name = key;
        kit.cooldown = cooldown;
        HolderLookup.Provider reg = op.registryAccess();
        var inv = op.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty()) kit.items.add(st.save(reg).toString());
        }
        if (kit.items.isEmpty()) return "";
        kits.put(key, kit);
        save(op.server);
        Helm.log("kit_create", op, null, "", key, Term.plural(kit.items.size(), "предмет", "предмета", "предметов") + " · " + KitRule.label(cooldown));
        return key;
    }

    public static boolean delete(ServerPlayer op, String name) {
        Kit k = kits.remove(name.toLowerCase(Locale.ROOT));
        if (k == null) return false;
        if (Helm.server() != null) save(Helm.server());
        Helm.log("kit_delete", op, null, "", k.name, "");
        return true;
    }

    public static long left(ServerPlayer p, Kit kit) {
        HelmData.PlayerInfo info = Helm.data().players.get(p.getUUID());
        long last = info == null || info.kits == null ? 0 : info.kits.getOrDefault(kit.name, 0L);
        return KitRule.left(kit.cooldown, last, System.currentTimeMillis());
    }

    /**
     * Выдать набор.
     * @param force без паузы (оператор, стартовый набор)
     * @return null при успехе, иначе причина
     */
    public static @Nullable String give(ServerPlayer p, Kit kit, boolean force, @Nullable ServerPlayer by) {
        long left = left(p, kit);
        ServerPlayer tell = by != null ? by : p;
        if (!force && left > 0) return left == Long.MAX_VALUE ? Texts.t(tell, "msg.kit.once", kit.name) : Texts.t(tell, "msg.kit.wait", kit.name, Texts.lang(tell).termLeft(left));
        HolderLookup.Provider reg = p.registryAccess();
        int given = 0;
        for (String snbt : kit.items) {
            try {
                ItemStack st = ItemStack.parse(reg, TagParser.parseTag(snbt)).orElse(ItemStack.EMPTY);
                if (st.isEmpty()) continue;
                if (!p.getInventory().add(st)) p.drop(st, false);
                given++;
            } catch (Exception e) {
                // предмет мода, которого больше нет: остальное выдаётся
                RYSHelm.LOG.warn("[ryshelm] набор {}: предмет не прочитан: {}", kit.name, e.getMessage());
            }
        }
        HelmData.PlayerInfo info = Helm.data().player(p.getUUID(), p.getGameProfile().getName());
        if (info.kits == null) info.kits = new LinkedHashMap<>();
        info.kits.put(kit.name, System.currentTimeMillis());
        Helm.dirty();
        if (by != null) Helm.log("kit_give", by, p.getUUID(), p.getGameProfile().getName(), kit.name, "");
        Sync.hello(p);
        return given == 0 ? Texts.t(tell, "msg.kit.unreadable") : null;
    }

    /** Первый вход: стартовый набор из настроек, если он есть. */
    static void firstJoin(ServerPlayer p) {
        String name = Helm.settings().starterKit;
        if (name == null || name.isBlank()) return;
        Kit kit = get(name);
        if (kit == null) return;
        if (give(p, kit, true, null) == null) Sync.notice(p, "kit", "row", "CHEST", Texts.t(p, "msg.kit.starter_given"), "", false);
    }
}
