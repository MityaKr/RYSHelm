package works.repka.ryshelm.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.Notice;
import works.repka.ryscore.ui.ScaledScreen;
import works.repka.ryscore.ui.Type.Weight;

/**
 * Правила при входе (план 5.7, макет 13): окно поверх мира при первом входе и после правки правил. «Я прочитал правила и
 * согласен», «Принимаю», «Выйти с сервера». Закрыл окно: уведомление «Примите правила сервера», клик его откроет.
 */
public final class RulesScreen extends ScaledScreen {
    private static final int W = 320;
    private final Kit.Hits hits = new Kit.Hits();
    private boolean agreed;
    private int mx, my;

    public RulesScreen() {
        super(Component.literal("Правила сервера"));
    }

    /** Открыть окно правил: данные придут с сервера вкладкой «Правила». */
    public static void request() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (!(mc.screen instanceof RulesScreen)) mc.setScreen(new RulesScreen());
        });
    }

    @Override
    protected void init() {
        super.init();
        View.request("rules", null, null, 0, "", 0, "");
    }

    @Override
    public void removed() {
        View.close();
        if (HelmClient.rulesPending) {
            Notice.row(Notice.HELM, "rules").icon(Icons.Shape.BOOK).title("Примите правила сервера").lines("клик откроет правила")
                .key(ClientEntry::keyName).action(RulesScreen::request).bad().show();
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // мир виден: окно лежит поверх игры, как в макете
    }

    @Override
    protected void draw(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Theme t = ThemeManager.get();
        mx = mouseX;
        my = mouseY;
        hits.clear();
        CompoundTag r = View.rules;
        ListTag items = r.getList("items", Tag.TAG_COMPOUND);
        int itemsH = 0;
        for (Tag x : items) itemsH += 24 + 8 * Math.max(0, Draw.wrapLabel(((CompoundTag) x).getString("detail"), W - 45, 12, Weight.REGULAR, 3).size() - 1);
        int h = 41 + 8 + Math.max(24, itemsH) + 8 + 70;
        int x = (width - W) / 2, y = Math.max(4, (height - h) / 2);
        if (t.shadow) Draw.shadow(g, x, y, W, h, 7f, 1f);
        Draw.roundBox(g, x, y, W, h, 7f, t.c("panel.bg"), t.c("panel.border"));
        Draw.round(g, x + 13.5f, y + 12.5f, 20, 20, 5f, Draw.mix(t.c("state.ok"), 0xFF000000, 0.25f));
        Icons.draw(g, Icons.Shape.SERVER, x + 18.5f, y + 17.5f, 10, 0xFFFFFFFF);
        String server = r.getString("server").isEmpty() ? HelmClient.server : r.getString("server");
        Draw.label(g, Draw.ellipsize("Добро пожаловать на " + server, W - 50, 20, Weight.DISPLAY), x + 39.5f, y + 18, 20, Weight.DISPLAY, t.c("text.primary"));
        long date = r.getLong("date");
        Draw.label(g, "Правила сервера" + (date > 0 ? " от " + Words.dayMonth(date) : "") + " · прочитайте до игры", x + 39.5f, y + 29.5f, 13, Weight.REGULAR, t.c("text.muted"));
        Draw.round(g, x + 13, y + 41, W - 26, 0.5f, 0.02f, t.c("panel.border"));
        int cy = y + 49;
        if (items.isEmpty()) Draw.label(g, "Загружаем правила…", x + 13.5f, cy + 6, 13, Weight.REGULAR, t.c("text.muted"));
        for (Tag xi : items) {
            CompoundTag i = (CompoundTag) xi;
            Draw.label(g, Integer.toString(i.getInt("n")), x + 13.5f, cy + 5, 14, Weight.SEMIBOLD, t.c("text.primary"));
            Draw.label(g, Draw.ellipsize(i.getString("title"), W - 45, 14, Weight.MEDIUM), x + 28.5f, cy + 5, 14, Weight.MEDIUM, t.c("text.primary"));
            List<String> lines = Draw.wrapLabel(i.getString("detail"), W - 45, 12, Weight.REGULAR, 3);
            float ly = cy + 14;
            for (String line : lines) {
                Draw.label(g, line, x + 28.5f, ly, 12, Weight.REGULAR, t.c("text.muted"));
                ly += 8;
            }
            cy += 24 + 8 * Math.max(0, lines.size() - 1);
        }
        cy = Math.max(cy, y + 49 + 24) + 4;
        Draw.round(g, x + 13, cy, W - 26, 0.5f, 0.02f, t.c("panel.border"));
        cy += 8;
        Kit.checkbox(g, t, hits, x + 13, cy, 9, agreed, false, mx, my, () -> agreed = !agreed);
        Draw.label(g, "Я прочитал правила и согласен", x + 27, cy + 4.5f, 13.5f, Weight.MEDIUM, t.c("text.primary"));
        hits.add(x + 13, cy - 2, (int) Draw.labelWidth("Я прочитал правила и согласен", 13.5f, Weight.MEDIUM) + 16, 13, b -> agreed = !agreed);
        cy += 17;
        cy += Ui.paragraph(g, "Пока правила не приняты, можно ходить и читать чат. Строить, открывать, писать и телепортироваться нельзя.", x + 13.5f, cy, W - 27, 12.5f, t.c("text.body"), 3);
        int by = y + h - 31;
        Ui.primary(g, t, hits, x + W - 13 - 58, by, 58, 21, null, "Принимаю", mx, my, agreed && !items.isEmpty() ? () -> {
            HelmClient.action("rules_accept");
            HelmClient.rulesPending = false;
            onClose();
        } : null);
        String quit = "Выйти с сервера";
        float qw = Draw.labelWidth(quit, 12.5f, Weight.MEDIUM) + 10;
        int qx = (int) (x + W - 13 - 58 - 12 - qw);
        boolean hot = Kit.over(mx, my, qx, by, (int) qw, 21);
        Icons.draw(g, Icons.Shape.EXIT, qx, by + 7.5f, 6, hot ? t.c("text.primary") : t.c("text.secondary"));
        Draw.label(g, quit, qx + 10, by + 10.5f, 12.5f, Weight.MEDIUM, hot ? t.c("text.primary") : t.c("text.secondary"));
        hits.add(qx, by, (int) qw, 21, b -> HelmClient.action("rules_quit"));
    }

    @Override
    protected boolean onClick(double x, double y, int button) {
        return hits.click(x, y, button) || super.onClick(x, y, button);
    }
}
