package works.repka.ryshelm.client;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Field;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.Type.Weight;

/** Мелкие элементы окна «Сервер» поверх {@code Kit}: лицо игрока, чипы ролей, кнопки со значком, поле ввода, подписи. */
final class Ui {
    private Ui() {}

    /** Лицо игрока; у незнакомого клиенту обычный скин. */
    static void face(GuiGraphics g, UUID id, int x, int y, int size) {
        Minecraft mc = Minecraft.getInstance();
        PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(id);
        ResourceLocation texture = info != null ? info.getSkin().texture() : DefaultPlayerSkin.get(id).texture();
        PlayerFaceRenderer.draw(g, texture, x, y, size);
    }

    /** Лицо в рамке-плашке, как в макетах: скруглённый фон и лицо внутри. */
    static void faceTile(GuiGraphics g, Theme t, UUID id, int x, int y, int size, boolean dim) {
        Draw.roundBox(g, x - 1, y - 1, size + 2, size + 2, 2.5f, t.c("slot.bg"), t.c("card.border"));
        face(g, id, x, y, size);
        if (dim) Draw.rect(g, x, y, size, size, Draw.alpha(t.c("panel.bg"), 0.55f));
    }

    static void caps(GuiGraphics g, Theme t, String text, float x, float cy) {
        Kit.caps(g, t, text, x, cy, 10.5f, 0.55f);
    }

    static float capsWidth(String text) {
        return Kit.capsWidth(text, 10.5f, 0.55f);
    }

    static void right(GuiGraphics g, String text, float rightX, float cy, float px, Weight w, int color) {
        Draw.label(g, text, rightX - Draw.labelWidth(text, px, w), cy, px, w, color);
    }

    /** Чип роли или состояния у ника: «оператор» с ключом, «модератор» синий, «новичок» серый. Возвращает ширину. */
    static int roleChip(GuiGraphics g, Theme t, String text, float x, float cy, String kind) {
        float px = 10.5f;
        boolean key = "operator".equals(kind);
        float w = Draw.labelWidth(text, px, Weight.SEMIBOLD) + 8 + (key ? 7 : 0);
        int bg, fg;
        switch (kind) {
            case "moderator" -> {
                bg = t.c("chip.mod.bg");
                fg = t.c("chip.mod.fg");
            }
            case "operator" -> {
                bg = Draw.mix(t.c("panel.bg"), 0xFF000000, 0.35f);
                fg = t.c("text.primary");
            }
            case "bad" -> {
                bg = Draw.alpha(t.c("state.bad"), 0.18f);
                fg = t.c("state.bad");
            }
            default -> {
                bg = t.c("filter.active.bg");
                fg = t.c("text.secondary");
            }
        }
        Draw.round(g, x, cy - 4.5f, w, 9, 2.5f, bg);
        float tx = x + 4;
        if (key) {
            Icons.draw(g, Icons.Shape.KEY, tx, cy - 2.5f, 5, fg);
            tx += 7;
        }
        Draw.label(g, text, tx, cy, px, Weight.SEMIBOLD, fg);
        return Math.round(w);
    }

    /** Точка цвета команды и имя. */
    static void team(GuiGraphics g, Theme t, String name, int color, float x, float cy, float room) {
        if (name.isEmpty()) {
            Draw.dot(g, Math.round(x), Math.round(cy - 1.5f), 3, t.c("text.dim"));
            Draw.label(g, Tr.tr("ui.card.no_team"), x + 6, cy, 12, Weight.REGULAR, t.c("text.muted"));
            return;
        }
        Draw.dot(g, Math.round(x), Math.round(cy - 1.5f), 3, 0xFF000000 | color);
        Draw.label(g, Draw.ellipsize(name, room - 6, 12, Weight.REGULAR), x + 6, cy, 12, Weight.REGULAR, t.c("text.body"));
    }

    /**
     * Кнопка со значком и подписью (ряды кнопок карточки, макет 08). {@code danger} красит подпись {@code state.bad},
     * без действия кнопка тусклая и не нажимается.
     */
    static void button(GuiGraphics g, Theme t, Kit.Hits hits, int x, int y, int w, int h, @Nullable Icons.Shape icon, String label, boolean danger,
                       int mx, int my, @Nullable Runnable action) {
        boolean hot = action != null && Kit.over(mx, my, x, y, w, h);
        if (t.beveled()) t.button(g, x, y, w, h, hot, false);
        else Draw.roundBox(g, x, y, w, h, 4f, hot ? Draw.mix(t.c("button.bg"), t.c("text.primary"), 0.08f) : t.c("button.bg"), t.c("button.border"));
        int fg = action == null ? t.c("text.dim") : danger ? t.c("state.bad") : t.c("button.fg");
        float px = 12;
        float tw = Draw.labelWidth(label, px, Weight.MEDIUM) + (icon != null ? 9 : 0);
        float tx = x + (w - tw) / 2f;
        if (icon != null) {
            Icons.draw(g, icon, tx, y + h / 2f - 3, 6, fg);
            tx += 9;
        }
        Draw.label(g, Draw.ellipsize(label, w - 6 - (icon != null ? 9 : 0), px, Weight.MEDIUM), tx, y + h / 2f, px, Weight.MEDIUM, fg);
        if (action != null) hits.add(x, y, w, h, b -> {
            if (b == 0) action.run();
        });
    }

    /** Главная янтарная кнопка со значком (макет 08, «К игроку»). */
    static void primary(GuiGraphics g, Theme t, Kit.Hits hits, int x, int y, int w, int h, @Nullable Icons.Shape icon, String label, int mx, int my, @Nullable Runnable action) {
        boolean hot = action != null && Kit.over(mx, my, x, y, w, h);
        if (t.beveled()) t.button(g, x, y, w, h, hot, true);
        else Draw.round(g, x, y, w, h, 4.5f, action == null ? Draw.alpha(t.c("accent"), 0.4f) : hot ? Draw.mix(t.c("accent"), 0xFFFFFFFF, 0.15f) : t.c("accent"));
        int fg = t.c("accent.text");
        float px = 13;
        float tw = Draw.labelWidth(label, px, Weight.SEMIBOLD) + (icon != null ? 10 : 0);
        float tx = x + (w - tw) / 2f;
        if (icon != null) {
            Icons.draw(g, icon, tx, y + h / 2f - 3.5f, 7, fg);
            tx += 10;
        }
        Draw.label(g, label, tx, y + h / 2f, px, Weight.SEMIBOLD, fg);
        if (action != null) hits.add(x, y, w, h, b -> {
            if (b == 0) action.run();
        });
    }

    /** Кнопка-значок с подсказкой (ряд оператора). Возвращает true под курсором. */
    static boolean iconButton(GuiGraphics g, Theme t, Kit.Hits hits, int x, int y, int w, int h, Icons.Shape icon, boolean active, int mx, int my, Runnable action) {
        boolean hot = Kit.over(mx, my, x, y, w, h);
        int fill = active ? Draw.alpha(t.c("state.ok"), 0.25f) : hot ? Draw.mix(t.c("button.bg"), t.c("text.primary"), 0.08f) : t.c("button.bg");
        Draw.roundBox(g, x, y, w, h, 4f, fill, active ? t.c("state.ok") : t.c("button.border"));
        Icons.draw(g, icon, x + (w - 7) / 2f, y + (h - 7) / 2f, 7, active ? t.c("state.ok") : t.c("button.fg"));
        hits.add(x, y, w, h, b -> {
            if (b == 0) action.run();
        });
        return hot;
    }

    /** Ссылка: текст цвета {@code link}, нажимается. Возвращает ширину. */
    static int link(GuiGraphics g, Theme t, Kit.Hits hits, String text, float x, float cy, float px, int mx, int my, Runnable action) {
        float w = Draw.labelWidth(text, px, Weight.MEDIUM);
        boolean hot = Kit.over(mx, my, (int) x, (int) (cy - 5), (int) w + 1, 10);
        Draw.label(g, text, x, cy, px, Weight.MEDIUM, hot ? Draw.mix(t.c("link"), 0xFFFFFFFF, 0.25f) : t.c("link"));
        hits.add((int) x, (int) (cy - 5), (int) w + 1, 10, b -> {
            if (b == 0) action.run();
        });
        return Math.round(w);
    }

    /** Полный текст подсказки поля и то, что из него сейчас показано (обрезано под ширину). */
    private static final Map<Field, String> HINTS = new WeakHashMap<>(), SHOWN = new WeakHashMap<>();

    /** Подсказка пустого поля: в узком поле она обрезается с многоточием, а не уходит под край и плашку клавиши. */
    static void hint(Field f, String full) {
        HINTS.put(f, full);
        SHOWN.put(f, full);
        f.setHint(Component.literal(full));
    }

    /** Подогнать подсказку под ширину поля; true, если она обрезана (тогда полную стоит показать подсказкой). */
    private static boolean fitHint(Field f, float room) {
        String full = HINTS.get(f);
        if (full == null) return false;
        // кегль подсказки как у Field ядра: 13 по макету, обычное начертание
        String shown = Draw.ellipsize(full, room, 13f, Weight.REGULAR);
        if (!shown.equals(SHOWN.get(f))) {
            SHOWN.put(f, shown);
            f.setHint(Component.literal(shown));
        }
        return !shown.equals(full);
    }

    /** Поле ввода в рамке темы; рисует само поле игры внутри. Рамка одна: у темы с фаской своя, у плоской скруглённая. */
    static void input(GuiGraphics g, Theme t, Field f, int x, int y, int w, int h, int mx, int my) {
        if (t.beveled()) t.input(g, x, y, w, h, f.isFocused());
        else Draw.roundBox(g, x, y, w, h, 4f, t.c("input.bg"), f.isFocused() ? t.c("text.muted") : t.c("input.border"));
        f.setX(x + 5);
        f.setY(y + (h - 10) / 2);
        f.setWidth(w - 10);
        f.setHeight(10);
        f.setVisible(true);
        f.setTextColor(t.c("input.fg") & 0xFFFFFF);
        fitHint(f, w - 10);
        f.render(g, mx, my, 0);
    }

    /**
     * Поле поиска, как в окне карты: лупа всегда слева, текст и подсказка начинаются после неё, справа место под плашку
     * клавиши «/» (видна, пока поле пустое и без курсора). Подсказка обрезается перед плашкой; возвращает true, если она
     * обрезана и курсор над полем: тогда окно показывает полную подсказкой.
     */
    static boolean search(GuiGraphics g, Theme t, Field f, int x, int y, int w, int h, int mx, int my) {
        if (t.beveled()) t.input(g, x, y, w, h, f.isFocused());
        else Draw.roundBox(g, x, y, w, h, 4f, t.c("input.bg"), f.isFocused() ? t.c("text.muted") : t.c("input.border"));
        Icons.draw(g, Icons.Shape.MAGNIFIER, x + 5, y + (h - 8) / 2f, 8, t.c("text.muted"));
        f.setX(x + 16);
        f.setY(y + (h - 10) / 2);
        f.setWidth(w - 16 - 14);
        f.setHeight(10);
        f.setVisible(true);
        f.setTextColor(t.c("input.fg") & 0xFFFFFF);
        // до плашки «/» (x + w - 12) остаётся зазор в 2 единицы
        boolean cut = fitHint(f, w - 16 - 14);
        f.render(g, mx, my, 0);
        boolean empty = f.getValue().isEmpty() && !f.isFocused();
        if (empty) {
            float kx = x + w - 12, ky = y + (h - 8) / 2f;
            Draw.roundBox(g, kx, ky, 8, 8, 2f, t.c("filter.active.bg"), t.c("panel.border"));
            float sw = Draw.labelWidth("/", 10, Weight.MEDIUM);
            Draw.label(g, "/", kx + (8 - sw) / 2f, ky + 4, 10, Weight.MEDIUM, t.c("text.muted"));
        }
        return cut && empty && Kit.over(mx, my, x, y, w, h);
    }

    /**
     * Крестик «убрать» в правой части плашки высотой {@code h} (ник белого списка, модератор): середина крестика на
     * середине плашки. Пока снятие ждёт ответа, крестик не нажимается, через 150 мс на его месте крутилка.
     */
    static void chipCross(GuiGraphics g, Theme t, Kit.Hits hits, int x, int y, int h, boolean busy, boolean spinning, int mx, int my, Runnable action) {
        float cy = y + h / 2f;
        if (busy) {
            if (spinning) Kit.spinner(g, x + 3, cy, 2.5f, t.c("text.muted"));
            hits.add(x - 2, y, 10, h, b -> {});
            return;
        }
        boolean hot = Kit.over(mx, my, x - 2, y, 10, h);
        Icons.draw(g, Icons.Shape.CROSS, x - 0.5f, cy - 3.5f, 7, hot ? t.c("state.bad") : t.c("text.muted"));
        hits.add(x - 2, y, 10, h, b -> {
            if (b == 0) action.run();
        });
    }

    /** Врезка «важно» (макет 09, подсказка лестницы): значок и текст с переносом. Возвращает высоту. */
    static int callout(GuiGraphics g, Theme t, String text, int x, int y, int w, boolean warn) {
        var lines = Draw.wrapLabel(text, w - 24, 12, Weight.REGULAR, 6);
        int h = lines.size() * 9 + 10;
        if (warn) Draw.roundBox(g, x, y, w, h, 5f, Draw.alpha(t.c("state.warn"), 0.12f), Draw.alpha(t.c("state.warn"), 0.55f));
        else Draw.roundBox(g, x, y, w, h, 5f, t.c("card.bg"), t.c("card.border"));
        if (warn) Icons.draw(g, Icons.Shape.WARN, x + 7, y + 6, 7, t.c("state.warn"));
        else Draw.info(g, x + 7f, y + 6f, 7f, t.c("text.muted"));
        float cy = y + 9.5f;
        for (String line : lines) {
            Draw.label(g, line, x + 19, cy, 12, Weight.REGULAR, t.c("text.body"));
            cy += 9;
        }
        return h;
    }

    /** Абзац текста с переносом; возвращает высоту. */
    static int paragraph(GuiGraphics g, String text, float x, float y, float w, float px, int color, int maxLines) {
        var lines = Draw.wrapLabel(text, w, px, Weight.REGULAR, maxLines);
        float cy = y + px * 0.35f;
        float step = px * 0.72f;
        for (String line : lines) {
            Draw.label(g, line, x, cy, px, Weight.REGULAR, color);
            cy += step;
        }
        return Math.round(lines.size() * step);
    }
}
