package works.repka.ryshelm.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.map.MapApi;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.Scroller;
import works.repka.ryscore.ui.Type.Weight;
import works.repka.ryshelm.logic.Kind;
import static works.repka.ryshelm.client.Tr.tr;

/**
 * Жалобы в окне «Сервер»: вкладка модератора (макет 10: список, карточка с местом, чатом и прошлым нарушителя, «Взять»,
 * «Наказать…», «Закрыть») и форма жалобы игрока справа во вкладке «Игроки» (макет 14).
 */
final class ReportsPane {
    private final ServerScreen s;
    private String state = "new";
    private final Scroller listScroll = new Scroller(), chatScroll = new Scroller();
    private final Kit.Dropdown formRuleDd = new Kit.Dropdown(), closeDd = new Kit.Dropdown();
    @Nullable UUID formTarget;
    private String formName = "";
    private int formRule;

    ReportsPane(ServerScreen s) {
        this.s = s;
    }

    // ---- форма игрока (макет 14) ----

    void openForm(UUID target, String name) {
        formTarget = target;
        formName = name;
        formRule = 0;
        s.reportText.setValue("");
        ServerScreen.selected = null;
        s.request();
    }

    void form(GuiGraphics g) {
        var t = s.t;
        int x = s.px + ServerScreen.SPLIT + 9, w = ServerScreen.W - ServerScreen.SPLIT - 18, top = s.py + ServerScreen.HEAD;
        Ui.faceTile(g, t, formTarget, x + 1, top + 9, 22, false);
        Draw.label(g, Draw.ellipsize(tr("ui.report.on", formName), w - 50, 18, Weight.DISPLAY), x + 29, top + 15, 18, Weight.DISPLAY, t.c("text.primary"));
        Draw.label(g, tr("ui.report.only_mods"), x + 29, top + 26.5f, 12, Weight.REGULAR, t.c("text.muted"));
        int cx = s.px + ServerScreen.W - 20;
        boolean hot = Kit.over(s.mx, s.my, cx - 3, top + 9, 12, 12);
        Draw.cross(g, cx, top + 12, hot ? t.c("accent") : t.c("text.muted"));
        s.hits.add(cx - 3, top + 9, 12, 12, b -> formTarget = null);
        int y = top + 40;
        Ui.caps(g, t, tr("ui.report.rule"), x, y + 3);
        y += 10;
        String label = formRule > 0 ? formRule + ". " + ServerScreen.ruleTitle(formRule) : tr("ui.report.rule.pick");
        fullDropdown(g, formRuleDd, x, y, w, label, formRule > 0);
        y += 23;
        Ui.caps(g, t, tr("ui.hint.what"), x, y + 3);
        y += 10;
        Ui.input(g, t, s.reportText, x, y, w, 46, s.mx, s.my);
        y += 52;
        Ui.callout(g, t, tr("ui.report.attach"), x, y, w, false);
        int by = s.py + ServerScreen.H - 40;
        Draw.round(g, s.px + ServerScreen.SPLIT + 1, by - 8, ServerScreen.W - ServerScreen.SPLIT - 2, 0.5f, 0.02f, t.c("panel.border"));
        boolean ready = formRule > 0 || !s.reportText.getValue().isBlank();
        Ui.primary(g, t, s.hits, x, by, w, 20, Icons.Shape.FLAG, tr("ui.report.send"), s.mx, s.my, ready ? this::send : null);
        String hint = tr("ui.report.cooldown");
        Draw.label(g, hint, x + (w - Draw.labelWidth(hint, 11, Weight.REGULAR)) / 2f, by + 28, 11, Weight.REGULAR, t.c("text.muted"));
    }

    void send() {
        if (formTarget == null) return;
        CompoundTag a = new CompoundTag();
        a.putUUID("target", formTarget);
        a.putInt("rule", formRule);
        a.putString("text", s.reportText.getValue().strip());
        HelmClient.action("report", a);
        formTarget = null;
        s.reportText.setValue("");
    }

    private void fullDropdown(GuiGraphics g, Kit.Dropdown d, int x, int y, int w, String label, boolean on) {
        var t = s.t;
        Kit.dropdown(g, t, s.hits, d, x, y, w, label, on, s.mx, s.my);
        Draw.roundBorder(g, x, y, w, Kit.DROP_H, 4f, on || d.open ? t.c("text.secondary") : t.c("panel.border"));
        Icons.draw(g, Icons.Shape.CHEVRON_DOWN, x + w - 10.5f, y + 3.75f, 6.5f, t.c("text.secondary"));
        s.hits.add(x, y, w, Kit.DROP_H, b -> d.open = !d.open);
    }

    // ---- вкладка модератора (макет 10) ----

    void draw(GuiGraphics g) {
        var t = s.t;
        CompoundTag v = View.reports;
        int left = s.px + 10, top = s.py + ServerScreen.HEAD, listW = 165;
        ListTag all = v.getList("list", Tag.TAG_COMPOUND);
        int nNew = 0, nTaken = 0, nClosed = 0;
        for (Tag x : all) {
            switch (((CompoundTag) x).getString("state")) {
                case "new" -> nNew++;
                case "taken" -> nTaken++;
                default -> nClosed++;
            }
        }
        int x = left, fy = top + 9;
        x += Kit.pill(g, t, s.hits, x, fy, tr("ui.report.filter.new") + " · " + nNew, state.equals("new"), s.mx, s.my, () -> state = "new") + 3;
        x += Kit.pill(g, t, s.hits, x, fy, tr("ui.report.filter.taken") + " · " + nTaken, state.equals("taken"), s.mx, s.my, () -> state = "taken") + 3;
        Kit.pill(g, t, s.hits, x, fy, tr("ui.report.filter.closed") + " · " + nClosed, state.equals("closed"), s.mx, s.my, () -> state = "closed");
        List<CompoundTag> rows = new ArrayList<>();
        String q = s.search.getValue().strip().toLowerCase();
        for (Tag xr : all) {
            CompoundTag r = (CompoundTag) xr;
            boolean match = state.equals(r.getString("state")) || (state.equals("new") && r.getString("state").equals("new"));
            if (!match) continue;
            if (!q.isEmpty() && !(r.getString("from") + " " + r.getString("target") + " " + r.getString("text")).toLowerCase().contains(q)) continue;
            rows.add(r);
        }
        int listTop = top + 29, step = 32, h = 29, bottom = s.py + ServerScreen.H - 10;
        int scroll = Math.round(listScroll.update(Math.max(0, rows.size() * step - (bottom - listTop))));
        s.hits.clip(listTop, bottom);
        Draw.scissor(g, left - 2, listTop, left + listW + 2, bottom);
        int y = listTop - scroll;
        CompoundTag detail = v.contains("detail") ? v.getCompound("detail") : null;
        long sel = detail == null ? 0 : detail.getLong("id");
        if (rows.isEmpty()) Draw.label(g, state.equals("new") ? tr("ui.report.none_new") : tr("ui.report.empty"), left + 4, listTop + 8, 12.5f, Weight.REGULAR, t.c("text.muted"));
        for (CompoundTag r : rows) {
            if (y + h >= listTop && y <= bottom) item(g, r, left, y, listW, h, r.getLong("id") == sel);
            y += step;
        }
        g.disableScissor();
        s.hits.unclip();
        Draw.round(g, s.px + 186, top + 8, 0.5f, ServerScreen.H - ServerScreen.HEAD - 16, 0.02f, t.c("panel.border"));
        if (detail != null) detail(g, detail);
        else Draw.label(g, tr("ui.report.pick"), s.px + 196, top + 16, 12.5f, Weight.REGULAR, t.c("text.muted"));
    }

    private void item(GuiGraphics g, CompoundTag r, int x, int y, int w, int h, boolean sel) {
        var t = s.t;
        boolean hot = Kit.over(s.mx, s.my, x, y, w, h);
        int fill = sel ? t.c("row.selected.bg") : hot ? Draw.mix(t.c("card.bg"), t.c("text.primary"), 0.04f) : t.c("card.bg");
        Draw.roundBox(g, x, y, w, h, 5f, fill, sel ? t.c("text.muted") : t.c("panel.border"));
        s.hits.add(x, y, w, h, b -> {
            ServerScreen.report = r.getLong("id");
            s.request();
        });
        String st = r.getString("state");
        String chip = st.equals("new") ? tr("ui.report.chip.new") : st.equals("taken") ? r.getString("by") : tr("ui.report.chip.closed");
        int cw = Ui.roleChip(g, t, chip, x + 6, y + 9.5f, st.equals("new") ? "bad" : st.equals("taken") ? "moderator" : "newbie");
        String who = r.getString("from") + " → " + r.getString("target");
        String when = Words.when(r.getLong("t"));
        float ww = Draw.labelWidth(when, 11, Weight.REGULAR);
        Draw.label(g, Draw.ellipsize(who, w - cw - 18 - ww, 12.5f, Weight.SEMIBOLD), x + 6 + cw + 4, y + 9.5f, 12.5f, Weight.SEMIBOLD, t.c("text.primary"));
        Ui.right(g, when, x + w - 6, y + 9, 11, Weight.REGULAR, t.c("text.muted"));
        String sub = st.equals("closed") ? r.getString("label") : r.getInt("rule") > 0 ? r.getInt("rule") + ". " + ServerScreen.ruleTitle(r.getInt("rule")) : r.getString("text");
        Draw.label(g, Draw.ellipsize(sub, w - 12, 11.5f, Weight.REGULAR), x + 6, y + 20.5f, 11.5f, Weight.REGULAR, t.c("text.muted"));
    }

    private void detail(GuiGraphics g, CompoundTag d) {
        var t = s.t;
        int x = s.px + 196, w = s.px + ServerScreen.W - 10 - x, top = s.py + ServerScreen.HEAD;
        UUID target = d.getUUID("targetId");
        String name = d.getString("target");
        Ui.faceTile(g, t, target, x + 1, top + 10, 20, false);
        Draw.label(g, tr("ui.report.on", name), x + 27, top + 16, 18, Weight.DISPLAY, t.c("text.primary"));
        String sub = tr("ui.report.from", d.getString("from")) + " · " + Words.dayTime(d.getLong("t")) + (d.getInt("rule") > 0 ? " · " + tr("ui.report.rule_ref", d.getInt("rule"), d.getString("ruleTitle")) : "");
        Draw.label(g, Draw.ellipsize(sub, w - 30, 12, Weight.REGULAR), x + 27, top + 27.5f, 12, Weight.REGULAR, t.c("text.muted"));
        int y = top + 38;
        if (!d.getString("text").isEmpty()) {
            var lines = Draw.wrapLabel(d.getString("text"), w - 10, 12.5f, Weight.REGULAR, 3);
            Draw.round(g, x, y, 1, lines.size() * 9 + 3, 0.02f, t.c("card.border"));
            float cy = y + 5;
            for (String line : lines) {
                Draw.label(g, line, x + 6, cy, 12.5f, Weight.REGULAR, t.c("text.body"));
                cy += 9;
            }
            y += lines.size() * 9 + 9;
        }
        int colW = (w - 8) / 2 - 2, rx = x + colW + 10;
        // где это было: тайлы карты, если RYSMap их даёт
        Ui.caps(g, t, tr("ui.report.where"), x, y + 4);
        Ui.right(g, tr("ui.report.at", Words.time(d.getLong("t"))), x + colW, y + 4, 11, Weight.REGULAR, t.c("text.muted"));
        int my = y + 10, mh = 82;
        CompoundTag tp = d.contains("targetPos") ? d.getCompound("targetPos") : null, fp = d.contains("fromPos") ? d.getCompound("fromPos") : null;
        Draw.roundBox(g, x, my, colW, mh, 5f, t.c("map.bg"), t.c("panel.border"));
        if (tp != null) {
            ResourceLocation dim = ResourceLocation.tryParse(tp.getString("dim"));
            double ppb = 1.0;
            double cx = tp.getDouble("x"), cz = tp.getDouble("z");
            if (fp != null && fp.getString("dim").equals(tp.getString("dim"))) {
                cx = (cx + fp.getDouble("x")) / 2;
                cz = (cz + fp.getDouble("z")) / 2;
                double span = Math.max(Math.abs(tp.getDouble("x") - fp.getDouble("x")), Math.abs(tp.getDouble("z") - fp.getDouble("z")));
                ppb = Math.max(0.25, Math.min(2.0, (colW - 30) / Math.max(1, span)));
            }
            Draw.scissor(g, x + 1, my + 1, x + colW - 1, my + mh - 1);
            boolean tiles = dim != null && MapApi.get().drawTiles(g, ResourceKey.create(Registries.DIMENSION, dim), cx, cz, ppb, x + 1, my + 1, colW - 2, mh - 2);
            if (!tiles) Draw.label(g, Words.dim(tp.getString("dim")), x + 6, my + 8, 11.5f, Weight.REGULAR, t.c("text.muted"));
            pin(g, d.getUUID("fromId"), fp, tp.getString("dim"), cx, cz, ppb, x, my, colW, mh);
            pin(g, target, tp, tp.getString("dim"), cx, cz, ppb, x, my, colW, mh);
            g.disableScissor();
        }
        int cy = my + mh + 3;
        Draw.roundBox(g, x, cy, colW, 57, 5f, t.c("card.bg"), t.c("card.border"));
        posRow(g, d.getUUID("fromId"), d.getString("from"), fp, x, cy + 9.5f, colW);
        Draw.round(g, x + 6, cy + 19, colW - 12, 0.5f, 0.02f, t.c("card.border"));
        posRow(g, target, name, tp, x, cy + 29, colW);
        Draw.round(g, x + 6, cy + 38.5f, colW - 12, 0.5f, 0.02f, t.c("card.border"));
        Draw.label(g, tr("ui.report.land"), x + 6, cy + 48, 12, Weight.REGULAR, t.c("text.muted"));
        Ui.right(g, d.getString("land").isEmpty() ? tr("ui.card.land.none") : d.getString("land"), x + colW - 6, cy + 48, 12, Weight.SEMIBOLD, t.c("text.primary"));
        cy += 61;
        final CompoundTag tpf = tp;
        Ui.button(g, t, s.hits, x, cy, colW, 16, Icons.Shape.TELEPORT, tr("ui.report.tp"), false, s.mx, s.my, tpf != null && HelmClient.can("ryshelm.tp") ? () -> {
            CompoundTag a = new CompoundTag();
            a.putString("dim", tpf.getString("dim"));
            a.putDouble("x", tpf.getDouble("x"));
            a.putDouble("y", tpf.getDouble("y"));
            a.putDouble("z", tpf.getDouble("z"));
            HelmClient.action("tp_spot", a);
        } : null);
        // чат до жалобы
        ListTag chat = d.getList("chat", Tag.TAG_COMPOUND);
        Ui.caps(g, t, tr("ui.report.chat", name), rx, y + 4);
        Ui.right(g, Tr.n("ui.report.lines", chat.size()), rx + colW, y + 4, 11, Weight.REGULAR, t.c("text.muted"));
        int chatTop = y + 10, chatH = Math.max(24, Math.min(10, Math.max(1, chat.size())) * 9 + 6);
        Draw.roundBox(g, rx, chatTop, colW, chatH, 5f, Draw.mix(t.c("panel.bg"), 0xFF000000, 0.3f), t.c("panel.border"));
        int scroll = Math.round(chatScroll.update(Math.max(0, chat.size() * 9 + 6 - chatH)));
        Draw.scissor(g, rx + 1, chatTop + 1, rx + colW - 1, chatTop + chatH - 1);
        float ly = chatTop + 7.5f - scroll;
        if (chat.isEmpty()) Draw.label(g, tr("ui.report.chat.empty"), rx + 5, ly, 11.5f, Weight.REGULAR, t.c("text.muted"));
        for (Tag xc : chat) {
            CompoundTag c = (CompoundTag) xc;
            String time = Words.time(c.getLong("t"));
            Draw.label(g, time, rx + 5, ly, 11, Weight.REGULAR, t.c("text.muted"));
            float nx = rx + 7 + Draw.labelWidth(time, 11, Weight.REGULAR);
            Draw.label(g, name, nx, ly, 11.5f, Weight.SEMIBOLD, t.c("text.primary"));
            nx += Draw.labelWidth(name, 11.5f, Weight.SEMIBOLD) + 3;
            Draw.label(g, Draw.ellipsize(c.getString("text"), rx + colW - 5 - nx, 11.5f, Weight.REGULAR), nx, ly, 11.5f, Weight.REGULAR, t.c("text.body"));
            ly += 9;
        }
        g.disableScissor();
        // прошлое нарушителя
        int py = chatTop + chatH + 8;
        Ui.caps(g, t, tr("ui.report.past", name), rx, py + 4);
        float lw = Draw.labelWidth(tr("ui.side.card"), 11.5f, Weight.MEDIUM);
        if (HelmClient.can("ryshelm.players")) Ui.link(g, t, s.hits, tr("ui.side.card"), rx + colW - lw, py + 4, 11.5f, s.mx, s.my, () -> {
            ServerScreen.selected = target;
            s.switchTab(ServerScreen.Tab.PLAYERS);
        });
        py += 12;
        ListTag past = d.getList("past", Tag.TAG_COMPOUND);
        if (past.isEmpty()) Draw.label(g, tr("ui.report.past.none"), rx, py + 5, 12, Weight.REGULAR, t.c("text.muted"));
        for (Tag xp : past) {
            CompoundTag p = (CompoundTag) xp;
            Icons.Shape icon = switch (p.getString("kind")) {
                case "warn" -> Icons.Shape.WARN;
                case "mute" -> Icons.Shape.MUTE;
                case "ban" -> Icons.Shape.BAN;
                case "kick" -> Icons.Shape.EXIT;
                default -> Icons.Shape.FLAG;
            };
            Icons.draw(g, icon, rx, py + 2, 6, t.c("text.secondary"));
            Draw.label(g, Draw.ellipsize(p.getString("text"), colW - 40, 12, Weight.REGULAR), rx + 10, py + 5, 12, Weight.REGULAR, t.c("text.body"));
            Ui.right(g, Words.when(p.getLong("t")), rx + colW, py + 5, 11.5f, Weight.REGULAR, t.c("text.muted"));
            py += 15;
        }
        // нижняя полоса: взять, наказать, закрыть
        int by = s.py + ServerScreen.H - 26;
        Draw.round(g, x, by - 7, w, 0.5f, 0.02f, t.c("panel.border"));
        String st = d.getString("state");
        long id = d.getLong("id");
        int bx = x;
        if (!st.equals("closed")) {
            if (!st.equals("taken") || !d.getBoolean("mine")) {
                int tw = 44;
                Ui.primary(g, t, s.hits, bx, by, tw, 16, Icons.Shape.HAND, tr("ui.report.take"), s.mx, s.my, () -> act("report_take", id, ""));
                bx += tw + 4;
            } else {
                Draw.label(g, tr("ui.report.taken_by_you"), bx, by + 8, 12, Weight.MEDIUM, t.c("text.secondary"));
                bx += (int) Draw.labelWidth(tr("ui.report.taken_by_you"), 12, Weight.MEDIUM) + 8;
            }
            Ui.button(g, t, s.hits, bx, by, 60, 16, Icons.Shape.MUTE, tr("ui.report.punish"), false, s.mx, s.my, HelmClient.can("ryshelm.mute") || HelmClient.can("ryshelm.warn") ? () -> {
                Kind kind = HelmClient.can("ryshelm.mute") ? Kind.MUTE : Kind.WARN;
                s.openPunish(kind, target, name);
            } : null);
            bx += 64;
            String closeText = tr("ui.report.close.rejected");
            float cw = Draw.labelWidth(closeText, 12, Weight.MEDIUM);
            boolean hot = Kit.over(s.mx, s.my, bx + 4, by, (int) cw + 6, 16);
            Draw.label(g, closeText, bx + 7, by + 8, 12, Weight.MEDIUM, hot ? t.c("text.primary") : t.c("text.secondary"));
            s.hits.add(bx + 4, by, (int) cw + 6, 16, b -> act("report_close", id, "rejected"));
            bx += (int) cw + 12;
            String done = tr("ui.report.close.punished");
            float dw = Draw.labelWidth(done, 12, Weight.MEDIUM);
            boolean hot2 = Kit.over(s.mx, s.my, bx + 4, by, (int) dw + 6, 16);
            Draw.label(g, "· " + done, bx + 2, by + 8, 12, Weight.MEDIUM, hot2 ? t.c("text.primary") : t.c("text.secondary"));
            s.hits.add(bx + 4, by, (int) dw + 8, 16, b -> act("report_close", id, "punished"));
            if (hot2) s.tooltip = List.of(tr("ui.report.close.punished.tip"));
        } else {
            Draw.label(g, tr("ui.report.closed_by", d.getString("by")), bx, by + 8, 12, Weight.MEDIUM, t.c("text.muted"));
        }
        String foot = tr("ui.report.foot", d.getString("from"));
        Ui.right(g, Draw.ellipsize(foot, x + w - bx - 150, 11.5f, Weight.REGULAR), x + w, by + 8, 11.5f, Weight.REGULAR, t.c("text.muted"));
    }

    private void pin(GuiGraphics g, UUID id, @Nullable CompoundTag p, String dim, double cx, double cz, double ppb, int x, int y, int w, int h) {
        if (p == null || !p.getString("dim").equals(dim)) return;
        int sx = (int) Math.round(x + w / 2.0 + (p.getDouble("x") - cx) * ppb) - 5, sy = (int) Math.round(y + h / 2.0 + (p.getDouble("z") - cz) * ppb) - 5;
        sx = Math.max(x + 2, Math.min(x + w - 12, sx));
        sy = Math.max(y + 2, Math.min(y + h - 12, sy));
        Draw.roundBox(g, sx - 1, sy - 1, 12, 12, 3f, s.t.c("panel.bg"), s.t.c("text.primary"));
        Ui.face(g, id, sx, sy, 10);
    }

    private void posRow(GuiGraphics g, UUID id, String name, @Nullable CompoundTag p, int x, float cy, int w) {
        var t = s.t;
        Ui.face(g, id, x + 6, Math.round(cy - 4), 8);
        Draw.label(g, name, x + 17, cy, 12, Weight.REGULAR, t.c("text.muted"));
        Ui.right(g, p == null ? "—" : p.getString("coords"), x + w - 6, cy, 12, Weight.SEMIBOLD, t.c("text.primary"));
    }

    private static void act(String action, long id, String outcome) {
        CompoundTag a = new CompoundTag();
        a.putLong("id", id);
        if (!outcome.isEmpty()) a.putString("outcome", outcome);
        HelmClient.action(action, a);
    }

    // ---- служебное ----

    void dropdowns(GuiGraphics g) {
        if (formTarget != null && formRuleDd.open) {
            List<Kit.Option> options = new ArrayList<>();
            for (Tag x : View.rules.getList("items", Tag.TAG_COMPOUND)) {
                CompoundTag r = (CompoundTag) x;
                options.add(new Kit.Option(r.getInt("n") + ". " + r.getString("title"), -1));
            }
            options.add(new Kit.Option(tr("ui.report.rule.other"), -1));
            int w = ServerScreen.W - ServerScreen.SPLIT - 18;
            Kit.dropdownList(g, s.t, s.hits, formRuleDd, options, ServerScreen.ruleIndex(formRule) - 1, w, s.mx, s.my, i -> {
                ListTag items = View.rules.getList("items", Tag.TAG_COMPOUND);
                formRule = i < items.size() ? items.getCompound(i).getInt("n") : 0;
            });
        }
    }

    void closeDropdowns() {
        formRuleDd.close();
        closeDd.close();
    }

    boolean scrolled(double x, double y, double dy) {
        return formRuleDd.scrolled(x, y, dy) || closeDd.scrolled(x, y, dy);
    }

    void scroll(double x, float by) {
        if (x < s.px + 186) listScroll.by(by);
        else chatScroll.by(by);
    }

    boolean keyPressed(int key) {
        return formRuleDd.keyPressed(key) || closeDd.keyPressed(key);
    }
}
