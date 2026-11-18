package works.repka.ryshelm.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * Чужой инвентарь и сундук Края в обычном окне сундука игры (план 2, {@code /invsee}): модератору только смотреть,
 * оператору менять. Клиенту ничего своего не нужно: окно сундука игры, клики гасит сервер.
 */
final class Peek {
    private Peek() {}

    /** Инвентарь: три ряда рюкзака, ряд панели, броня и вторая рука; пять рядов. */
    static ChestMenu inventory(int id, Inventory viewer, ServerPlayer target, boolean edit) {
        return new Menu(MenuType.GENERIC_9x5, id, viewer, new InventoryView(target), 5, edit, InventoryView.USED);
    }

    static ChestMenu ender(int id, Inventory viewer, ServerPlayer target, boolean edit) {
        return new Menu(MenuType.GENERIC_9x3, id, viewer, new Live(target.getEnderChestInventory(), target), 3, edit, 27);
    }

    /** Окно, где клики по чужим слотам гасятся, если менять нельзя, а пустые хвостовые слоты не принимают вещи. */
    private static final class Menu extends ChestMenu {
        private final boolean edit;
        private final int used;

        Menu(MenuType<?> type, int id, Inventory viewer, Container container, int rows, boolean edit, int used) {
            super(type, id, viewer, container, rows);
            this.edit = edit;
            this.used = used;
        }

        @Override
        public void clicked(int slot, int button, ClickType type, Player player) {
            int top = getRowCount() * 9;
            boolean theirs = slot >= 0 && slot < top;
            if (theirs && (!edit || slot >= used)) return;
            // перенос в их сторону с Shift, двойной клик и раскладка задевают их слоты: без права менять тоже гасим
            if (!edit && (type == ClickType.QUICK_MOVE || type == ClickType.PICKUP_ALL || type == ClickType.QUICK_CRAFT)) return;
            super.clicked(slot, button, type, player);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return edit ? super.quickMoveStack(player, index) : ItemStack.EMPTY;
        }
    }

    /** Чужой контейнер, пока хозяин в сети. */
    private static class Live implements Container {
        final Container inner;
        final ServerPlayer owner;

        Live(Container inner, ServerPlayer owner) {
            this.inner = inner;
            this.owner = owner;
        }

        @Override
        public int getContainerSize() {
            return inner.getContainerSize();
        }

        @Override
        public boolean isEmpty() {
            return inner.isEmpty();
        }

        @Override
        public ItemStack getItem(int slot) {
            return inner.getItem(slot);
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            return inner.removeItem(slot, amount);
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return inner.removeItemNoUpdate(slot);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            inner.setItem(slot, stack);
        }

        @Override
        public void setChanged() {
            inner.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return !owner.hasDisconnected() && owner.isAlive();
        }

        @Override
        public void clearContent() {
            // окно просмотра чужое не чистит
        }
    }

    /** Инвентарь игрока в 45 слотах: 27 рюкзака, 9 панели, 4 брони, вторая рука, 4 пустых. */
    private static final class InventoryView extends Live {
        static final int USED = 41;

        InventoryView(ServerPlayer owner) {
            super(owner.getInventory(), owner);
        }

        /** Слот окна → слот инвентаря игрока или -1. */
        private static int map(int slot) {
            if (slot < 27) return slot + 9;          // рюкзак 9..35
            if (slot < 36) return slot - 27;         // панель 0..8
            if (slot < 40) return 36 + (39 - slot);  // броня: шлем первым
            if (slot == 40) return 40;               // вторая рука
            return -1;
        }

        @Override
        public int getContainerSize() {
            return 45;
        }

        @Override
        public ItemStack getItem(int slot) {
            int i = map(slot);
            return i < 0 ? ItemStack.EMPTY : inner.getItem(i);
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            int i = map(slot);
            return i < 0 ? ItemStack.EMPTY : inner.removeItem(i, amount);
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            int i = map(slot);
            return i < 0 ? ItemStack.EMPTY : inner.removeItemNoUpdate(i);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            int i = map(slot);
            if (i >= 0) inner.setItem(i, stack);
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return map(slot) >= 0;
        }
    }
}
