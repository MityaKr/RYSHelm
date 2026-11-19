package works.repka.ryshelm.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.action.Actions;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Field;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.ScaledScreen;
import works.repka.ryscore.ui.Scroller;
import works.repka.ryscore.ui.Type.Weight;
import works.repka.ryshelm.logic.Kind;
import works.repka.ryshelm.logic.Term;

/**
 * Окно «Сервер» (план 5.2, макеты 08–14): вкладки «Игроки», «Правила», «Жалобы», «Журнал», «Роли», «Настройки». Модератор
 * видит карточку игрока и окно наказания, игрок только кто в сети, запрос, жалобу и правила. Окно рисуется целиком каждый
 * кадр и складывает нажимаемые места в {@link Kit.Hits}; данные приходят с сервера ({@link View}).
 */
public final class ServerScreen extends ScaledScreen {
    enum Tab {
        PLAYERS("players", "Игроки"), RULES("rules", "Правила"), REPORTS("reports", "Жалобы"), JOURNAL("journal", "Журнал"), ROLES("roles", "Роли"), SETTINGS("settings", "Настройки");

        final String id, label;

        Tab(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    // размеры окна в единицах (макет 1280×800 при масштабе 2: окно 1200×728 пикселей)
    static final int W = 600, H = 364, HEAD = 28, SPLIT = 404;

    static Tab tab = Tab.PLAYERS;
    public static @Nullable UUID selected;
    public static String filter = "online";
    static int sort;
    static String journalCat = "";
    static int journalDays = 7;
    public static long report;

    final Kit.Hits hits = new Kit.Hits();
    int mx, my, px, py;
    Theme t;
    @Nullable List<String> tooltip;
    private String lastRequest = "";

    // поля ввода
    Field search, reason, customTerm, noteBox, reportText, who, welcome, serverName, ladder;
    private final List<Field> fields = new ArrayList<>();

    // прокрутка и списки
    final Scroller listScroll = new Scroller(), cardScroll = new Scroller(), rolesScroll = new Scroller(), journalScroll = new Scroller(), rulesScroll = new Scroller();
    final Kit.Dropdown sortDd = new Kit.Dropdown(), ruleDd = new Kit.Dropdown(), daysDd = new Kit.Dropdown(), keepDd = new Kit.Dropdown(), addModDd = new Kit.Dropdown();

    // окно наказания
    @Nullable Kind punishKind;
    @Nullable UUID punishTarget;
    String punishName = "";
    long punishTerm;
    int punishRule;
    boolean punishShown = true, customMode;
    // заметка в карточке
    boolean noteOpen;
    // меню «···» у строки игрока (макет 14)
    @Nullable UUID menuFor;
    int menuX, menuY;
    final ReportsPane reports = new ReportsPane(this);

    public ServerScreen() {
        super(Component.literal("Сервер"));
    }

    /** Открыть окно на вкладке (null: прежняя). */
    public static void open(@Nullable String tabId) {
        if (tabId != null) for (Tab x : Tab.values()) if (x.id.equals(tabId)) tab = x;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new ServerScreen()));
    }

    private Field field(int max, String hint, @Nullable Field old) {
        Field f = new Field(100, 10);
        f.setMaxLength(max);
        f.setHint(Component.literal(hint));
        f.setSpans(List.of(), new int[0]);
        if (old != null) f.setValue(old.getValue());
        addWidget(f);
        fields.add(f);
        return f;
    }

    @Override
    protected void init() {
        super.init();
        fields.clear();
        search = field(32, "Игрок, жалоба, запись журнала", search);
        reason = field(200, "Что случилось", reason);
        customTerm = field(12, "2h, 3d", customTerm);
        noteBox = field(500, "Заметка видна только персоналу", noteBox);
        reportText = field(600, "Что случилось", reportText);
        who = field(32, "Игрок или модератор", who);
        welcome = field(200, "Текст при входе", welcome);
        serverName = field(24, "Имя в шапке окна", serverName);
        ladder = field(120, "warn, mute 1h, ban 1d", ladder);
        if (!tabVisible(tab)) tab = Tab.PLAYERS;
        lastRequest = "";
        request();
    }

    @Override
    public void removed() {
        View.close();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, ThemeManager.get().c("overlay"));
    }

    static boolean tabVisible(Tab x) {
        return switch (x) {
            case PLAYERS, RULES -> true;
            case REPORTS -> HelmClient.can("ryshelm.reports");
            case JOURNAL -> HelmClient.can("ryshelm.journal");
            case ROLES -> HelmClient.staff();
            case SETTINGS -> HelmClient.can("ryshelm.admin");
        };
    }

    boolean staffView() {
        return HelmClient.can("ryshelm.players");
    }

    /** Сказать серверу, что смотрим: вкладка, карточка, окно наказания, фильтры журнала. Повтор не шлётся. */
    void request() {
        if (!HelmClient.present) return;
        UUID card = tab == Tab.PLAYERS && staffView() ? selected : null;
        String whoText = who == null ? "" : who.getValue();
        String key = tab.id + card + punishTarget + report + whoText + journalDays + journalCat;
        if (key.equals(lastRequest)) return;
        lastRequest = key;
        View.request(tab.id, card, punishTarget, report, whoText, journalDays, journalCat);
    }

    static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    // ---- кадр ----

    @Override
    protected void draw(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        t = ThemeManager.get();
        mx = mouseX;
        my = mouseY;
        hits.clear();
        tooltip = null;
        for (Field f : fields) f.setVisible(false);
        renderBackground(g, mouseX, mouseY, partialTick);
        px = (width - W) / 2;
        py = Math.max(4, (height - H) / 2);
        boolean modal = punishKind != null;
        int bx = modal ? -1000 : mx, by = modal ? -1000 : my;
        int saveX = mx, saveY = my;
        mx = bx;
        my = by;
        panel(g);
        header(g);
        switch (tab) {
            case PLAYERS -> players(g);
            case RULES -> rules(g);
            case REPORTS -> reports.draw(g);
            case JOURNAL -> journal(g);
            case ROLES -> roles(g);
            case SETTINGS -> settings(g);
        }
        dropdownLists(g);
        menu(g);
        mx = saveX;
        my = saveY;
        if (modal) punishDialog(g);
        if (tooltip != null) Kit.tooltip(g, t, tooltip, mx, my);
        request();
    }

    private void panel(GuiGraphics g) {
        if (t.beveled()) {
            t.panel(g, px, py, W, H);
            return;
        }
        if (t.shadow) Draw.shadow(g, px, py, W, H, 7f, 1f);
        Draw.roundBox(g, px, py, W, H, 7f, t.c("panel.bg"), t.c("panel.border"));
    }

    private void header(GuiGraphics g) {
        Draw.round(g, px + 0.5f, py + HEAD - 0.5f, W - 1, 0.5f, 0.02f, t.c("panel.border"));
        // плашка сервера: зелёный квадрат со значком
        Draw.round(g, px + 8.5f, py + 7.5f, 14, 14, 3.5f, Draw.mix(t.c("state.ok"), 0xFF000000, 0.25f));
        Icons.draw(g, Icons.Shape.SERVER, px + 11.5f, py + 10.5f, 8, 0xFFFFFFFF);
        String name = HelmClient.server.isEmpty() ? "Сервер" : HelmClient.server;
        Draw.label(g, Draw.ellipsize(name, 40, 15, Weight.DISPLAY), px + 27, py + 14.5f, 15, Weight.DISPLAY, t.c("text.primary"));
        List<Kit.Tab> tabs = new ArrayList<>();
        for (Tab x : Tab.values()) {
            if (!tabVisible(x)) continue;
            int count = x == Tab.REPORTS ? HelmClient.reports : -1;
            tabs.add(new Kit.Tab(x.label, count > 0 ? count : -1, tab == x, () -> switchTab(x)));
        }
        int tabsX = px + 70;
        Kit.tabs(g, t, hits, tabsX, py, HEAD, px + 418 - tabsX, tabs, mx, my);
        // поиск, шестерёнка клавиш, закрыть
        Ui.input(g, t, search, px + 425, py + 6, 120, 16, mx, my);
        if (search.getValue().isEmpty() && !search.isFocused()) {
            Draw.magnifier(g, px + 429, py + 11, t.c("text.muted"));
        }
        int gx = px + W - 47;
        boolean hot = Kit.over(mx, my, gx, py + 6, 17, 17);
        Kit.iconButton(g, t, gx, py + 6, 17, hot);
        Icons.draw(g, Icons.Shape.GEAR, gx + 4.5f, py + 10.5f, 8, hot ? t.c("accent") : t.c("button.fg"));
        hits.add(gx, py + 6, 17, 17, b -> Actions.openKeys(this));
        if (hot) tooltip = List.of("Клавиши и колесо");
        Kit.close(g, t, hits, px + W - 26, py + 5, mx, my, this::onClose);
    }

    void switchTab(Tab x) {
        tab = x;
        menuFor = null;
        listScroll.reset();
        request();
    }

    private void dropdownLists(GuiGraphics g) {
        if (tab == Tab.PLAYERS && sortDd.open) {
            Kit.dropdownList(g, t, hits, sortDd, List.of(new Kit.Option("Сначала в сети", -1), new Kit.Option("По имени", -1), new Kit.Option("По команде", -1)),
                sort, 100, mx, my, i -> sort = i);
        }
        if (tab == Tab.JOURNAL) {
            if (daysDd.open) Kit.dropdownList(g, t, hits, daysDd, List.of(new Kit.Option("1 день", -1), new Kit.Option("7 дней", -1), new Kit.Option("30 дней", -1),
                new Kit.Option("90 дней", -1), new Kit.Option("всё время", -1)), indexOf(DAYS, journalDays), 70, mx, my, i -> {
                    journalDays = DAYS[i];
                    request();
                });
            if (keepDd.open) Kit.dropdownList(g, t, hits, keepDd, List.of(new Kit.Option("30 дней", -1), new Kit.Option("60 дней", -1), new Kit.Option("90 дней", -1),
                new Kit.Option("180 дней", -1), new Kit.Option("365 дней", -1)), indexOf(KEEP, View.journal.getInt("keep")), 70, mx, my, i -> {
                    CompoundTag a = new CompoundTag();
                    a.putInt("journalDays", KEEP[i]);
                    HelmClient.action("settings", a);
                });
        }
        if (tab == Tab.ROLES && addModDd.open) {
            ListTag c = View.roles.getList("candidates", Tag.TAG_COMPOUND);
            List<Kit.Option> options = new ArrayList<>();
            for (Tag x : c) options.add(new Kit.Option(((CompoundTag) x).getString("name"), -1));
            if (options.isEmpty()) options.add(new Kit.Option("Некого: все в сети уже персонал", -1));
            Kit.dropdownList(g, t, hits, addModDd, options, -1, 120, mx, my, i -> {
                if (i >= c.size()) return;
                CompoundTag p = c.getCompound(i);
                CompoundTag a = new CompoundTag();
                a.putUUID("target", p.getUUID("id"));
                a.putString("name", p.getString("name"));
                a.putBoolean("on", true);
                HelmClient.action("moderator", a);
            });
        }
        if (punishKind != null && ruleDd.open) {
            List<Kit.Option> options = new ArrayList<>();
            options.add(new Kit.Option("Без пункта правил", -1));
            for (Tag x : View.rules.getList("items", Tag.TAG_COMPOUND)) {
                CompoundTag r = (CompoundTag) x;
                options.add(new Kit.Option(r.getInt("n") + ". " + r.getString("title"), -1));
            }
            Kit.dropdownList(g, t, hits, ruleDd, options, ruleIndex(punishRule), 240, mx, my, i -> punishRule = i == 0 ? 0 : View.rules.getList("items", Tag.TAG_COMPOUND).getCompound(i - 1).getInt("n"));
        }
        reports.dropdowns(g);
    }

    static final int[] DAYS = {1, 7, 30, 90, 0}, KEEP = {30, 60, 90, 180, 365};

    static int indexOf(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return -1;
    }

    static int ruleIndex(int n) {
        ListTag items = View.rules.getList("items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) if (items.getCompound(i).getInt("n") == n) return i + 1;
        return 0;
    }

    static String ruleTitle(int n) {
        for (Tag x : View.rules.getList("items", Tag.TAG_COMPOUND)) if (((CompoundTag) x).getInt("n") == n) return ((CompoundTag) x).getString("title");
        return "";
    }

    // ---- игроки ----

    /** Строка игрока из данных сервера, без мода на сервере из списка вкладки игры. */
    List<CompoundTag> rows() {
        List<CompoundTag> out = new ArrayList<>();
        if (HelmClient.present) {
            for (Tag x : View.players.getList("players", Tag.TAG_COMPOUND)) out.add((CompoundTag) x);
        } else if (Minecraft.getInstance().getConnection() != null) {
            for (PlayerInfo info : Minecraft.getInstance().getConnection().getOnlinePlayers()) {
                CompoundTag r = new CompoundTag();
                r.putUUID("id", info.getProfile().getId());
                r.putString("name", info.getProfile().getName());
                r.putBoolean("online", true);
                r.putString("role", "player");
                out.add(r);
            }
        }
        return out;
    }

    List<CompoundTag> visibleRows() {
        String q = search.getValue().strip().toLowerCase(Locale.ROOT);
        List<CompoundTag> out = new ArrayList<>();
        for (CompoundTag r : rows()) {
            if (!q.isEmpty() && !r.getString("name").toLowerCase(Locale.ROOT).contains(q) && !r.getString("team").toLowerCase(Locale.ROOT).contains(q)) continue;
            if (!passes(r, filter)) continue;
            out.add(r);
        }
        Comparator<CompoundTag> byName = Comparator.comparing(r -> r.getString("name").toLowerCase(Locale.ROOT));
        Comparator<CompoundTag> c = switch (sort) {
            case 1 -> byName;
            case 2 -> Comparator.comparing((CompoundTag r) -> r.getString("team").isEmpty() ? "~" : r.getString("team").toLowerCase(Locale.ROOT)).thenComparing(byName);
            default -> Comparator.comparing((CompoundTag r) -> !r.getBoolean("online")).thenComparing((CompoundTag r) -> -Math.max(r.getLong("since"), r.getLong("seen")) * (r.getBoolean("online") ? -1 : 1)).thenComparing(byName);
        };
        out.sort(c);
        return out;
    }

    static boolean passes(CompoundTag r, String f) {
        return switch (f) {
            case "online" -> r.getBoolean("online");
            case "team" -> r.getBoolean("mine") && r.getBoolean("online");
            case "newbie" -> r.getBoolean("newbie") || r.getBoolean("rulesPending");
            case "punished" -> r.getBoolean("muted") || r.getBoolean("banned") || r.getBoolean("frozen");
            case "reported" -> r.getInt("reports") > 0;
            default -> true;
        };
    }

    private int count(String f) {
        int n = 0;
        for (CompoundTag r : rows()) if (passes(r, f)) n++;
        return n;
    }

    private void players(GuiGraphics g) {
        boolean staff = staffView();
        int left = px + 10, top = py + HEAD, listW = 384;
        int x = left;
        int fy = top + 9;
        if (staff) {
            for (String[] f : new String[][] {{"online", "В сети"}, {"all", "Все"}, {"newbie", "Новички"}, {"punished", "Наказаны"}, {"reported", "С жалобами"}}) {
                x += Kit.pill(g, t, hits, x, fy, f[1] + " · " + count(f[0]), filter.equals(f[0]), mx, my, () -> {
                    filter = f[0];
                    listScroll.reset();
                }) + 3;
            }
            String label = switch (sort) {
                case 1 -> "По имени";
                case 2 -> "По команде";
                default -> "Сначала в сети";
            };
            int dw = 85;
            Kit.dropdown(g, t, hits, sortDd, left + listW - dw, fy, dw, label, sort != 0, mx, my);
        } else {
            if (!filter.equals("online") && !filter.equals("team")) filter = "online";
            for (String[] f : new String[][] {{"online", "В сети"}, {"team", "Моя команда"}}) {
                x += Kit.pill(g, t, hits, x, fy, f[1] + " · " + count(f[0]), filter.equals(f[0]), mx, my, () -> filter = f[0]) + 3;
            }
            String mods = onlineStaff();
            if (!mods.isEmpty()) {
                String text = "модератор в сети: " + mods;
                float tw = Draw.labelWidth(text, 11.5f, Weight.REGULAR);
                Icons.draw(g, Icons.Shape.EYE, left + listW - tw - 8, fy + 3.5f, 6, t.c("text.muted"));
                Draw.label(g, text, left + listW - tw, fy + 7, 11.5f, Weight.REGULAR, t.c("text.muted"));
            }
        }
        // заголовки столбцов
        int hy = top + 36;
        int cTeam = staff ? left + 106 : left + 102, cWhere = staff ? left + 186 : left + 177, cWhen = left + 278;
        Ui.caps(g, t, "Игрок", left + 6, hy);
        Ui.caps(g, t, "Команда", cTeam, hy);
        Ui.caps(g, t, "Где", cWhere, hy);
        if (staff) Ui.caps(g, t, "Когда", cWhen, hy);
        // строки
        List<CompoundTag> rows = visibleRows();
        int rowH = staff ? 23 : 23, step = staff ? 26 : 31;
        int listTop = top + 43, listBottom = py + H - 8;
        int content = rows.size() * step;
        int scroll = Math.round(listScroll.update(Math.max(0, content - (listBottom - listTop))));
        hits.clip(listTop, listBottom);
        Draw.scissor(g, left - 2, listTop, left + listW + 2, listBottom);
        int y = listTop - scroll;
        if (rows.isEmpty()) {
            Draw.label(g, HelmClient.present ? "Ничего не нашлось." : "На сервере нет RYSHelm: видно только, кто в сети.", left + 6, listTop + 8, 12.5f, Weight.REGULAR, t.c("text.muted"));
            if (HelmClient.present && (!search.getValue().isEmpty() || !filter.equals("online")))
                Ui.link(g, t, hits, "Показать все", left + 6 + Draw.labelWidth("Ничего не нашлось. ", 12.5f, Weight.REGULAR), listTop + 8, 12.5f, mx, my, () -> {
                    search.setValue("");
                    filter = staff ? "all" : "online";
                });
        }
        for (CompoundTag r : rows) {
            if (y + rowH >= listTop && y <= listBottom) row(g, r, left, y, listW, rowH, staff, cTeam, cWhere, cWhen);
            y += step;
        }
        g.disableScissor();
        hits.unclip();
        // правая панель
        Draw.round(g, px + SPLIT, py + HEAD, 0.5f, H - HEAD - 1, 0.02f, t.c("panel.border"));
        if (reports.formTarget != null) reports.form(g);
        else if (staff && selected != null && View.card != null && selected.equals(View.card.getUUID("id"))) card(g, View.card);
        else sideHint(g, staff);
    }

    private String onlineStaff() {
        List<String> names = new ArrayList<>();
        for (CompoundTag r : rows()) if (r.getBoolean("online") && "moderator".equals(r.getString("role"))) names.add(r.getString("name"));
        if (names.isEmpty()) for (CompoundTag r : rows()) if (r.getBoolean("online") && "operator".equals(r.getString("role"))) names.add(r.getString("name"));
        return names.isEmpty() ? "" : names.size() <= 2 ? String.join(", ", names) : names.get(0) + " и ещё " + (names.size() - 1);
    }

    private void row(GuiGraphics g, CompoundTag r, int x, int y, int w, int h, boolean staff, int cTeam, int cWhere, int cWhen) {
        UUID id = r.getUUID("id");
        boolean online = r.getBoolean("online");
        boolean sel = id.equals(selected) && staff;
        boolean hot = Kit.over(mx, my, x, y, w, h);
        int fill = sel ? t.c("row.selected.bg") : hot && staff ? Draw.mix(t.c("card.bg"), t.c("text.primary"), 0.04f) : t.c("card.bg");
        Draw.roundBox(g, x, y, w, h, 5f, fill, sel ? t.c("text.muted") : t.c("panel.border"));
        if (staff) hits.add(x, y, w, h, b -> {
            selected = id;
            noteOpen = false;
            reports.formTarget = null;
            request();
        });
        float cy = y + h / 2f;
        Ui.faceTile(g, t, id, x + 6, y + (h - 14) / 2, 14, !online);
        String name = r.getString("name");
        int nameColor = online ? t.c("text.primary") : t.c("text.muted");
        Draw.label(g, Draw.ellipsize(name, 60, 13, Weight.SEMIBOLD), x + 25, cy, 13, Weight.SEMIBOLD, nameColor);
        float chipX = x + 25 + Math.min(60, Draw.labelWidth(name, 13, Weight.SEMIBOLD)) + 4;
        String role = r.getString("role");
        if ("operator".equals(role)) Ui.roleChip(g, t, "оператор", chipX, cy, "operator");
        else if ("moderator".equals(role)) Ui.roleChip(g, t, "модератор", chipX, cy, "moderator");
        else if (r.getBoolean("newbie") || r.getBoolean("rulesPending")) Ui.roleChip(g, t, "новичок", chipX, cy, "newbie");
        if (r.contains("team")) Ui.team(g, t, r.getString("team"), r.getInt("color"), cTeam, cy, cWhere - cTeam - 6);
        // где
        String where;
        if (!online) where = "не в сети";
        else {
            String dim = Words.dim(r.getString("dim"));
            if (r.getUUID("id").equals(Minecraft.getInstance().player.getUUID())) where = dim + " · вы";
            else where = r.contains("dist") ? dim + " · " + Words.distance(r.getInt("dist")) : dim;
            if (staff && !r.getString("land").isEmpty() && !r.contains("dist")) where = dim + " · " + r.getString("land");
        }
        float whereRoom = staff ? cWhen - cWhere - 6 : 110;
        Draw.label(g, Draw.ellipsize(where, whereRoom, 12, Weight.REGULAR), cWhere, cy, 12, Weight.REGULAR, online ? t.c("text.body") : t.c("text.muted"));
        if (staff) {
            // когда
            String when;
            int dot;
            if (online && r.getBoolean("rulesPending")) {
                when = "правила не приняты";
                dot = t.c("state.warn");
            } else if (online) {
                when = Words.online(r.getLong("since"));
                dot = t.c("state.ok");
            } else {
                when = Words.ago(r.getLong("seen"));
                dot = t.c("text.dim");
            }
            Draw.dot(g, cWhen, Math.round(cy - 1.5f), 3, dot);
            Draw.label(g, Draw.ellipsize(when, 70, 12, Weight.REGULAR), cWhen + 6, cy, 12, Weight.REGULAR, online ? t.c("text.body") : t.c("text.muted"));
            // значки справа: предупреждения, жалобы, заглушён, бан, заморожен
            float ix = x + w - 8;
            ix = mark(g, ix, cy, Icons.Shape.FLAG, r.getInt("reports"), t.c("text.secondary"), "жалоб на игрока: " + r.getInt("reports"));
            ix = mark(g, ix, cy, Icons.Shape.WARN, r.getInt("warns"), t.c("text.secondary"), "предупреждений за 30 дней: " + r.getInt("warns"));
            if (r.getBoolean("muted")) ix = mark(g, ix, cy, Icons.Shape.MUTE, 0, t.c("state.warn"), "заглушён");
            if (r.getBoolean("banned")) ix = mark(g, ix, cy, Icons.Shape.BAN, 0, t.c("state.bad"), "забанен");
            if (r.getBoolean("frozen")) mark(g, ix, cy, Icons.Shape.SNOW, 0, t.c("chip.mod.fg"), "заморожен");
        } else if (online && !id.equals(Minecraft.getInstance().player.getUUID())) {
            // игроку: «К игроку», «Позвать» и меню (макет 14). Запрос шлёт карта командой, у FTB Essentials та же команда
            int bx = x + w - 6 - 14;
            boolean dots = Kit.over(mx, my, bx, y + 4, 14, 15);
            Draw.roundBox(g, bx, y + 4, 14, 15, 4f, dots || id.equals(menuFor) ? Draw.mix(t.c("button.bg"), t.c("text.primary"), 0.08f) : t.c("button.bg"), id.equals(menuFor) ? t.c("text.muted") : t.c("button.border"));
            Icons.draw(g, Icons.Shape.DOTS, bx + 3.5f, y + 8, 7, t.c("button.fg"));
            hits.add(bx, y + 4, 14, 15, b -> {
                menuFor = id.equals(menuFor) ? null : id;
                menuX = bx;
                menuY = y + 21;
            });
            boolean ally = r.getBoolean("mine");
            Ui.button(g, t, hits, bx - 3 - 45, y + 4, 45, 15, Icons.Shape.USERS, "Позвать", false, mx, my, () -> command("tpahere " + name));
            Ui.button(g, t, hits, bx - 6 - 92, y + 4, 47, 15, Icons.Shape.TELEPORT, "К игроку", false, mx, my, () -> command("tpa " + name));
            if (ally && Kit.over(mx, my, bx - 3 - 45, y + 4, 45, 15)) tooltip = List.of("Свой по команде: запрос всё равно уходит, ответ клавишей Y");
        }
    }

    private float mark(GuiGraphics g, float rightX, float cy, Icons.Shape icon, int n, int color, String tip) {
        if (n <= 0 && (icon == Icons.Shape.FLAG || icon == Icons.Shape.WARN)) return rightX;
        String text = n > 0 ? Integer.toString(n) : "";
        float tw = text.isEmpty() ? 0 : Draw.labelWidth(text, 11.5f, Weight.REGULAR) + 2;
        float x0 = rightX - tw - 7;
        Icons.draw(g, icon, x0, cy - 3, 6, color);
        if (!text.isEmpty()) Draw.label(g, text, x0 + 8, cy, 11.5f, Weight.REGULAR, color);
        if (Kit.over(mx, my, (int) x0, (int) cy - 4, (int) (rightX - x0), 8)) tooltip = List.of(tip);
        return x0 - 6;
    }

    static void command(String cmd) {
        var conn = Minecraft.getInstance().getConnection();
        if (conn != null) conn.sendCommand(cmd);
    }

    /** Меню «···» у строки игрока (макет 14): написать, на карте, пожаловаться. */
    private void menu(GuiGraphics g) {
        if (menuFor == null || tab != Tab.PLAYERS) return;
        CompoundTag r = null;
        for (CompoundTag x : rows()) if (x.getUUID("id").equals(menuFor)) r = x;
        if (r == null) {
            menuFor = null;
            return;
        }
        String name = r.getString("name");
        UUID id = menuFor;
        int w = 104, h = 52;
        int x = Math.min(menuX - w + 14, px + W - w - 4), y = menuY;
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        Draw.shadow(g, x, y, w, h, 5f, 0.8f);
        Draw.roundBox(g, x, y, w, h, 5f, t.c("panel.bg"), t.c("panel.border"));
        menuItem(g, x + 3, y + 3, w - 6, Icons.Shape.CHAT, "Написать", () -> {
            Minecraft.getInstance().setScreen(new net.minecraft.client.gui.screens.ChatScreen("/msg " + name + " "));
        });
        menuItem(g, x + 3, y + 18, w - 6, Icons.Shape.MAP, "Найти в списке карты", () -> {
            works.repka.ryscore.map.MapApi.get().openTab(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("rysmap", "players"));
        });
        Draw.round(g, x + 4, y + 33.5f, w - 8, 0.5f, 0.02f, t.c("panel.border"));
        boolean canReport = !HelmClient.present || HelmClient.can("ryshelm.report");
        menuItem(g, x + 3, y + 35, w - 6, Icons.Shape.FLAG, "Пожаловаться", canReport ? () -> {
            reports.openForm(id, name);
        } : null);
        g.pose().popPose();
        hits.add(x, y, w, h, b -> {});
    }

    private void menuItem(GuiGraphics g, int x, int y, int w, Icons.Shape icon, String label, @Nullable Runnable action) {
        boolean hot = action != null && Kit.over(mx, my, x, y, w, 14);
        if (hot) Draw.round(g, x, y, w, 14, 3.5f, t.c("filter.active.bg"));
        int fg = action == null ? t.c("text.dim") : t.c("text.primary");
        Icons.draw(g, icon, x + 5, y + 4, 6, fg);
        Draw.label(g, label, x + 16, y + 7, 12.5f, Weight.REGULAR, fg);
        if (action != null) hits.add(x, y, w, 14, b -> {
            menuFor = null;
            action.run();
        });
    }

    /** Правая панель без выбранного игрока: кто в сети, что тут можно. */
    private void sideHint(GuiGraphics g, boolean staff) {
        int x = px + SPLIT + 9, y = py + HEAD + 10, w = W - SPLIT - 18;
        Draw.label(g, staff ? "Карточка игрока" : "Игроки сервера", x, y + 6, 16, Weight.DISPLAY, t.c("text.primary"));
        String text = staff
            ? "Выберите игрока слева: наиграно, где он и чья там земля, прошлые наказания, жалобы и заметки персонала, кнопки модератора."
            : "«К игроку» и «Позвать» отправляют запрос, ответ приходит уведомлением с клавишами Y и N. Пожаловаться: кнопка ··· у игрока.";
        int h = Ui.paragraph(g, text, x, y + 18, w, 12.5f, t.c("text.body"), 8);
        Ui.callout(g, t, staff ? "Окно видят модераторы и операторы. Каждое действие персонала ложится в журнал." : "Модераторы видят жалобы сразу. Если в сети никого нет, жалоба ждёт.",
            x, y + 26 + h, w, false);
    }

    // ---- карточка игрока (макет 08) ----

    private void card(GuiGraphics g, CompoundTag c) {
        UUID id = c.getUUID("id");
        String name = c.getString("name");
        boolean online = c.getBoolean("online");
        int x = px + SPLIT + 9, w = W - SPLIT - 18, top = py + HEAD;
        Ui.faceTile(g, t, id, x + 1, top + 9, 22, !online);
        Draw.label(g, Draw.ellipsize(name, w - 50, 18, Weight.DISPLAY), x + 29, top + 15, 18, Weight.DISPLAY, t.c("text.primary"));
        String team = c.getString("team");
        String sub = (team.isEmpty() ? "без команды" : c.getBoolean("ownTeam") ? "своя команда" : team) + " · "
            + (online ? Words.online(c.getLong("seen")) : "был " + Words.ago(c.getLong("seen")));
        if (!"player".equals(c.getString("role"))) sub = Words.role(c.getString("role")) + " · " + sub;
        Draw.label(g, Draw.ellipsize(sub, w - 40, 12, Weight.REGULAR), x + 29, top + 26.5f, 12, Weight.REGULAR, t.c("text.muted"));
        int cx = px + W - 20;
        boolean hot = Kit.over(mx, my, cx - 3, top + 9, 12, 12);
        Draw.cross(g, cx, top + 12, hot ? t.c("accent") : t.c("text.muted"));
        hits.add(cx - 3, top + 9, 12, 12, b -> {
            selected = null;
            request();
        });
        // три строки сведений
        int by = top + 37, bh = 58;
        Draw.roundBox(g, x, by, w, bh, 5f, t.c("card.bg"), t.c("card.border"));
        long played = c.getLong("played");
        String first = c.contains("first") && c.getLong("first") > 0 ? " · с " + Words.dayMonth(c.getLong("first")) : "";
        stat(g, x, by + 9.5f, w, "Наиграно", played > 0 ? Words.span(played) + first : "—", t.c("text.primary"));
        Draw.round(g, x + 6, by + 19.5f, w - 12, 0.5f, 0.02f, t.c("card.border"));
        String where = c.contains("dim") ? Words.dim(c.getString("dim")) + " · " + c.getString("coords") : "—";
        stat(g, x, by + 29, w, online ? "Где сейчас" : "Где видели", where, t.c("text.primary"));
        Draw.round(g, x + 6, by + 38.5f, w - 12, 0.5f, 0.02f, t.c("card.border"));
        String land = c.getString("land");
        String landText = land.isEmpty() ? (online ? "ничья" : "—") : land + (c.getBoolean("landOwn") ? ", своя" : ", чужая для него");
        stat(g, x, by + 48.5f, w, "Чья земля", landText, !land.isEmpty() && !c.getBoolean("landOwn") ? t.c("state.warn") : t.c("text.primary"));
        // история и заметки прокручиваются между сведениями и кнопками
        boolean op = HelmClient.can("ryshelm.cheat");
        int actionsTop = py + H - (op ? 104 : 88);
        int areaTop = by + bh + 6, areaBottom = actionsTop - 4;
        ListTag history = c.getList("history", Tag.TAG_COMPOUND);
        ListTag notes = c.getList("notes", Tag.TAG_COMPOUND);
        int content = 14 + Math.max(1, history.size()) * 13 + 8 + 14 + notes.size() * 26 + 14 + (noteOpen ? 22 : 0);
        int scroll = Math.round(cardScroll.update(Math.max(0, content - (areaBottom - areaTop))));
        hits.clip(areaTop, areaBottom);
        Draw.scissor(g, px + SPLIT + 1, areaTop, px + W - 1, areaBottom);
        int y = areaTop - scroll;
        Ui.caps(g, t, "История", x, y + 5);
        Ui.right(g, Term.plural(history.size(), "запись", "записи", "записей"), x + w, y + 5, 11, Weight.REGULAR, t.c("text.muted"));
        y += 14;
        if (history.isEmpty()) {
            Draw.label(g, "Наказаний и жалоб не было", x, y + 5, 12, Weight.REGULAR, t.c("text.muted"));
            y += 13;
        }
        for (Tag xh : history) {
            CompoundTag h = (CompoundTag) xh;
            historyRow(g, h, x, y, w);
            y += 13;
        }
        y += 8;
        Ui.caps(g, t, "Заметки персонала", x, y + 5);
        y += 14;
        for (Tag xn : notes) {
            CompoundTag n = (CompoundTag) xn;
            Draw.round(g, x, y, 1, 22, 0.02f, t.c("card.border"));
            var lines = Draw.wrapLabel(n.getString("text"), w - 8, 12, Weight.REGULAR, 2);
            float ly = y + 4.5f;
            for (String line : lines) {
                Draw.label(g, line, x + 6, ly, 12, Weight.REGULAR, t.c("text.body"));
                ly += 8.5f;
            }
            Draw.label(g, n.getString("by") + " · " + Words.date(n.getLong("t")), x + 6, ly + 0.5f, 10.5f, Weight.REGULAR, t.c("text.muted"));
            y += 26;
        }
        if (noteOpen) {
            Ui.input(g, t, noteBox, x, y, w - 52, 16, mx, my);
            Kit.button(g, t, hits, x + w - 48, y, 48, 16, "Сохранить", false, mx, my, () -> saveNote(id));
            y += 22;
        } else if (HelmClient.can("ryshelm.note")) {
            Icons.draw(g, Icons.Shape.PLUS, x, y + 2, 5, t.c("link"));
            Ui.link(g, t, hits, "Заметка", x + 7, y + 5, 12, mx, my, () -> {
                noteOpen = true;
                setFocused(noteBox);
                noteBox.setFocused(true);
            });
        }
        g.disableScissor();
        hits.unclip();
        actions(g, c, x, actionsTop, w);
    }

    private void stat(GuiGraphics g, int x, float cy, int w, String label, String value, int color) {
        Draw.label(g, label, x + 6, cy, 12, Weight.REGULAR, t.c("text.muted"));
        float room = w - 12 - Draw.labelWidth(label, 12, Weight.REGULAR) - 8;
        Ui.right(g, Draw.ellipsize(value, room, 12, Weight.SEMIBOLD), x + w - 6, cy, 12, Weight.SEMIBOLD, color);
    }

    private void historyRow(GuiGraphics g, CompoundTag h, int x, int y, int w) {
        String kind = h.getString("kind");
        Icons.Shape icon = switch (kind) {
            case "warn" -> Icons.Shape.WARN;
            case "mute" -> Icons.Shape.MUTE;
            case "kick" -> Icons.Shape.EXIT;
            case "ban" -> Icons.Shape.BAN;
            default -> Icons.Shape.FLAG;
        };
        boolean active = h.getBoolean("active");
        int color = h.getBoolean("revoked") ? t.c("text.dim") : active ? t.c("state.warn") : t.c("text.body");
        float cy = y + 5;
        Icons.draw(g, icon, x, cy - 3, 6, color);
        String text = h.getString("text") + (h.getBoolean("revoked") ? " · снято" : active ? " · действует" : "");
        String date = Words.when(h.getLong("t"));
        float dw = Draw.labelWidth(date, 11.5f, Weight.REGULAR);
        float room = w - 10 - dw - 6;
        if (h.getBoolean("canRevoke")) {
            float lw = Draw.labelWidth("Снять", 11.5f, Weight.MEDIUM);
            Ui.link(g, t, hits, "Снять", x + w - dw - 6 - lw, cy, 11.5f, mx, my, () -> {
                CompoundTag a = new CompoundTag();
                a.putLong("id", h.getLong("id"));
                HelmClient.action("revoke", a);
            });
            room -= lw + 6;
        }
        Draw.label(g, Draw.ellipsize(text, room, 12, Weight.REGULAR), x + 10, cy, 12, Weight.REGULAR, color);
        Ui.right(g, date, x + w, cy, 11.5f, Weight.REGULAR, t.c("text.muted"));
    }

    private void saveNote(UUID id) {
        String text = noteBox.getValue().strip();
        if (text.isEmpty()) return;
        CompoundTag a = new CompoundTag();
        a.putUUID("target", id);
        a.putString("text", text);
        HelmClient.action("note", a);
        noteBox.setValue("");
        noteOpen = false;
    }

    /** Кнопки карточки по правам (план 5.3). */
    private void actions(GuiGraphics g, CompoundTag c, int x, int top, int w) {
        UUID id = c.getUUID("id");
        String name = c.getString("name");
        boolean online = c.getBoolean("online");
        boolean self = Minecraft.getInstance().player != null && id.equals(Minecraft.getInstance().player.getUUID());
        Draw.round(g, px + SPLIT + 1, top - 2, W - SPLIT - 2, 0.5f, 0.02f, t.c("panel.border"));
        int y = top + 4;
        if (HelmClient.can("ryshelm.cheat")) {
            // ряд оператора: значки с подсказками
            String[][] ops = {{"heal", "HEART", "Вылечить"}, {"feed", "FORK", "Накормить"}, {"fly", "FEATHER", "Полёт"}, {"god", "SHIELD", "Бессмертие"}, {"speed", "SPEED", "Скорость ×2, ещё раз ×1"}, {"move", "MOVE", "Переставить: окажется здесь при входе"}};
            int bw = (w - 5 * 3) / 6;
            for (int i = 0; i < ops.length; i++) {
                String[] o = ops[i];
                int bx = x + i * (bw + 3);
                boolean active = ("fly".equals(o[0]) && c.getBoolean("fly")) || ("god".equals(o[0]) && c.getBoolean("god"));
                boolean usable = o[0].equals("move") ? !online && HelmClient.can("ryshelm.move") : online;
                if (!usable) {
                    Draw.roundBox(g, bx, y, bw, 13, 4f, Draw.alpha(t.c("button.bg"), 0.5f), t.c("button.border"));
                    Icons.draw(g, HelmClient.shape(o[1]), bx + (bw - 7) / 2f, y + 3, 7, t.c("text.dim"));
                    if (Kit.over(mx, my, bx, y, bw, 13)) tooltip = List.of(o[2], o[0].equals("move") ? "Только для игрока вне сети" : "Игрок не в сети");
                    continue;
                }
                boolean hot = Ui.iconButton(g, t, hits, bx, y, bw, 13, HelmClient.shape(o[1]), active, mx, my, () -> {
                    if (o[0].equals("move")) {
                        HelmClient.onTarget("move", id);
                        return;
                    }
                    CompoundTag a = new CompoundTag();
                    a.putUUID("target", id);
                    a.putString("what", o[0]);
                    if (o[0].equals("speed")) a.putFloat("value", speedNext());
                    HelmClient.action("cheat", a);
                });
                if (hot) tooltip = List.of(o[2]);
            }
            y += 17;
        }
        if (online && !self) Ui.primary(g, t, hits, x, y, w, 20, Icons.Shape.TELEPORT, "К игроку", mx, my, HelmClient.can("ryshelm.tp") ? () -> HelmClient.onTarget("tp", id) : null);
        else if (self) Ui.primary(g, t, hits, x, y, w, 20, Icons.Shape.USER, "Это вы", mx, my, null);
        else Ui.primary(g, t, hits, x, y, w, 20, Icons.Shape.TELEPORT, "К месту, где видели", mx, my, c.contains("dim") && HelmClient.can("ryshelm.tp") ? () -> HelmClient.onTarget("tplast", id) : null);
        y += 24;
        int bw = (w - 6) / 3, bh = 14;
        boolean target = online && !self;
        Ui.button(g, t, hits, x, y, bw, bh, Icons.Shape.USERS, "Позвать", false, mx, my, target && HelmClient.can("ryshelm.tp") ? () -> HelmClient.onTarget("tphere", id) : null);
        Ui.button(g, t, hits, x + bw + 3, y, bw, bh, Icons.Shape.EYE, "Наблюдать", false, mx, my, target && HelmClient.can("ryshelm.spectate") ? () -> HelmClient.onTarget("spectate", id) : null);
        boolean frozen = c.getBoolean("frozen");
        Ui.button(g, t, hits, x + 2 * (bw + 3), y, bw, bh, Icons.Shape.SNOW, frozen ? "Разморозить" : "Заморозить", false, mx, my, target && HelmClient.can("ryshelm.freeze") ? () -> HelmClient.onTarget("freeze", id) : null);
        y += bh + 3;
        Ui.button(g, t, hits, x, y, bw, bh, Icons.Shape.CHEST, "Инвентарь", false, mx, my, target && HelmClient.can("ryshelm.invsee") ? () -> HelmClient.onTarget("invsee", id) : null);
        Ui.button(g, t, hits, x + bw + 3, y, bw, bh, Icons.Shape.CHEST, "Сундук Края", false, mx, my, target && HelmClient.can("ryshelm.enderchest.other") ? () -> HelmClient.onTarget("ender", id) : null);
        Ui.button(g, t, hits, x + 2 * (bw + 3), y, bw, bh, Icons.Shape.NOTE, "Заметка", false, mx, my, HelmClient.can("ryshelm.note") ? () -> {
            noteOpen = true;
            setFocused(noteBox);
            noteBox.setFocused(true);
        } : null);
        y += bh + 3;
        int qw = (w - 9) / 4;
        boolean muted = c.contains("mute"), banned = c.contains("ban");
        Ui.button(g, t, hits, x, y, qw, bh, null, "Предупредить", false, mx, my, !self && HelmClient.can("ryshelm.warn") ? () -> openPunish(Kind.WARN, id, name) : null);
        Ui.button(g, t, hits, x + qw + 3, y, qw, bh, null, muted ? "Снять мут" : "Заглушить", false, mx, my, !self && HelmClient.can("ryshelm.mute") ? () -> {
            if (muted) revoke(c.getLong("mute"));
            else openPunish(Kind.MUTE, id, name);
        } : null);
        Ui.button(g, t, hits, x + 2 * (qw + 3), y, qw, bh, null, "Выгнать", false, mx, my, target && HelmClient.can("ryshelm.kick") ? () -> openPunish(Kind.KICK, id, name) : null);
        Ui.button(g, t, hits, x + 3 * (qw + 3), y, qw, bh, null, banned ? "Снять бан" : "Бан", true, mx, my, !self && HelmClient.can("ryshelm.ban.temp") ? () -> {
            if (banned) revoke(c.getLong("ban"));
            else openPunish(Kind.BAN, id, name);
        } : null);
    }

    private static int speedStep;

    private static float speedNext() {
        speedStep = (speedStep + 1) % 3;
        return new float[] {2f, 3f, 1f}[speedStep];
    }

    static void revoke(long id) {
        CompoundTag a = new CompoundTag();
        a.putLong("id", id);
        HelmClient.action("revoke", a);
    }

    // ---- окно наказания (макет 09) ----

    public void openPunish(Kind kind, UUID target, String name) {
        punishKind = kind;
        punishTarget = target;
        punishName = name;
        punishRule = 0;
        punishShown = true;
        customMode = false;
        punishTerm = kind == Kind.MUTE ? Term.HOUR : kind == Kind.BAN ? Term.DAY : 0;
        reason.setValue("");
        customTerm.setValue("");
        request();
    }

    /** Форма жалобы игрока справа (макет 14). */
    public void reportForm(UUID target, String name) {
        reports.openForm(target, name);
    }

    public void closePunish() {
        punishKind = null;
        punishTarget = null;
        ruleDd.close();
        request();
    }

    private void punishDialog(GuiGraphics g) {
        Kind kind = punishKind;
        if (kind == null || punishTarget == null) return;
        CompoundTag hint = View.punish;
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.fill(px, py, px + W, py + H, Draw.alpha(0xFF000000, 0.45f));
        // окно под затемнением не нажимается
        hits.add(0, 0, width, height, b -> {});
        int w = 270, x = px + (W - w) / 2;
        boolean timed = kind.timed();
        int h = timed ? 246 : 206;
        int y = py + Math.max(10, (H - h) / 2);
        if (t.shadow) Draw.shadow(g, x, y, w, h, 7f, 1f);
        Draw.roundBox(g, x, y, w, h, 7f, t.c("panel.bg"), t.c("panel.border"));
        hits.add(x, y, w, h, b -> {});
        int ix = x + 11, iw = w - 22;
        Ui.faceTile(g, t, punishTarget, ix, y + 10, 16, false);
        String verb = switch (kind) {
            case WARN -> "Предупредить";
            case MUTE -> "Заглушить";
            case KICK -> "Выгнать";
            case BAN -> "Бан для";
        };
        Draw.label(g, verb + " " + punishName, ix + 22, y + 18, 16, Weight.DISPLAY, t.c("text.primary"));
        boolean hotX = Kit.over(mx, my, x + w - 21, y + 12, 12, 12);
        Draw.cross(g, x + w - 18, y + 15, hotX ? t.c("accent") : t.c("text.muted"));
        hits.add(x + w - 21, y + 12, 12, 12, b -> closePunish());
        int cy = y + 34;
        if (timed) {
            long max = hint == null ? Term.FOREVER : hint.getLong(kind == Kind.MUTE ? "muteMax" : "banMax");
            Ui.caps(g, t, "Срок", ix, cy + 3);
            if (max != Term.FOREVER) Ui.right(g, "модератору до " + Term.label(max), ix + iw, cy + 3, 11, Weight.REGULAR, t.c("text.muted"));
            cy += 10;
            long[] presets = kind == Kind.MUTE ? new long[] {10 * Term.MINUTE, Term.HOUR, Term.DAY, 7 * Term.DAY} : new long[] {Term.DAY, 3 * Term.DAY, 7 * Term.DAY, Term.FOREVER};
            int cx = ix;
            for (long p : presets) {
                if (p > max && max != Term.FOREVER) continue;
                cx += chip(g, cx, cy, Term.label(p), !customMode && punishTerm == p, () -> {
                    customMode = false;
                    punishTerm = p;
                }) + 3;
            }
            int customW = chip(g, cx, cy, "Свой срок", customMode, () -> {
                customMode = true;
                setFocused(customTerm);
                customTerm.setFocused(true);
            });
            if (customMode) {
                Ui.input(g, t, customTerm, cx + customW + 4, cy, Math.max(40, ix + iw - cx - customW - 4), 15, mx, my);
                long parsed = Term.parse(customTerm.getValue(), 0);
                punishTerm = parsed > 0 && (max == Term.FOREVER || parsed <= max) ? parsed : 0;
            }
            cy += 24;
        }
        Ui.caps(g, t, "Причина", ix, cy + 3);
        Ui.right(g, "пункт правил или свой текст", ix + iw, cy + 3, 11, Weight.REGULAR, t.c("text.muted"));
        cy += 10;
        String ruleLabel = punishRule > 0 ? punishRule + ". " + ruleTitle(punishRule) : "Без пункта правил";
        int ddW = Kit.dropdown(g, t, hits, ruleDd, ix, cy, iw, ruleLabel, punishRule > 0, mx, my);
        if (ddW < iw) {
            // кнопка списка на всю ширину, как в макете
            Draw.roundBorder(g, ix, cy, iw, Kit.DROP_H, 4f, t.c("panel.border"));
            hits.add(ix, cy, iw, Kit.DROP_H, b -> ruleDd.open = !ruleDd.open);
            Icons.draw(g, Icons.Shape.CHEVRON_DOWN, ix + iw - 10.5f, cy + 3.75f, 6.5f, t.c("text.secondary"));
        }
        cy += 19;
        Ui.input(g, t, reason, ix, cy, iw, 26, mx, my);
        cy += 32;
        Draw.label(g, "Игрок видит причину", ix, cy + 3, 13, Weight.MEDIUM, t.c("text.primary"));
        Draw.label(g, kind == Kind.MUTE ? "в уведомлении и при попытке написать" : kind == Kind.BAN ? "на экране входа" : "в уведомлении", ix, cy + 11.5f, 11, Weight.REGULAR, t.c("text.muted"));
        boolean hotT = Kit.over(mx, my, ix + iw - 18, cy + 2, 18, 10);
        Kit.toggle(g, t, ix + iw - 18, cy + 2, punishShown, hotT);
        hits.add(ix + iw - 18, cy + 2, 18, 10, b -> punishShown = !punishShown);
        cy += 22;
        if (hint != null) cy += Ui.callout(g, t, hint.getString("ladder"), ix, cy, iw, true) + 6;
        String note = switch (kind) {
            case MUTE -> "Закрывает общий чат, каналы команды и союза, /msg, /tell, /w, /me, /teammsg. Снять может тот, кто дал, и оператор.";
            case BAN -> "Бан ложится в список игры banned-players.json: его видит /pardon. Снять может тот, кто дал, и оператор.";
            case KICK -> "Игрок отключится с причиной и сможет зайти снова.";
            case WARN -> "Запись в истории игрока и уведомление ему. Предупреждения считает лестница наказаний.";
        };
        cy += Ui.paragraph(g, note, ix, cy, iw, 11.5f, t.c("text.muted"), 3) + 6;
        // кнопки
        int by = y + h - 25;
        String go = switch (kind) {
            case WARN -> "Предупредить";
            case MUTE -> "Заглушить " + (punishTerm > 0 ? Term.onLabel(punishTerm) : "");
            case KICK -> "Выгнать";
            case BAN -> "Бан " + (punishTerm > 0 ? Term.onLabel(punishTerm) : "");
        };
        int gw = (int) Math.ceil(Draw.labelWidth(go.strip(), 13, Weight.SEMIBOLD)) + 26;
        boolean ready = !timed || punishTerm > 0;
        Ui.primary(g, t, hits, ix + iw - gw, by, gw, 17, kind == Kind.MUTE ? Icons.Shape.MUTE : kind == Kind.BAN ? Icons.Shape.BAN : kind == Kind.KICK ? Icons.Shape.EXIT : Icons.Shape.WARN,
            go.strip(), mx, my, ready ? this::sendPunish : null);
        float cw = Draw.labelWidth("Отмена", 12.5f, Weight.MEDIUM);
        boolean hotC = Kit.over(mx, my, (int) (ix + iw - gw - 14 - cw), by, (int) cw + 8, 17);
        Draw.label(g, "Отмена", ix + iw - gw - 10 - cw, by + 8.5f, 12.5f, Weight.MEDIUM, hotC ? t.c("text.primary") : t.c("text.secondary"));
        hits.add((int) (ix + iw - gw - 14 - cw), by, (int) cw + 8, 17, b -> closePunish());
        g.pose().popPose();
        if (ruleDd.open) dropdownLists(g);
    }

    private int chip(GuiGraphics g, int x, int y, String label, boolean active, Runnable action) {
        int w = (int) Math.ceil(Draw.labelWidth(label, 12.5f, Weight.MEDIUM)) + 14, h = 15;
        boolean hot = Kit.over(mx, my, x, y, w, h);
        if (active) Draw.roundBox(g, x, y, w, h, 4f, t.c("filter.active.bg"), t.c("text.muted"));
        else Draw.roundBox(g, x, y, w, h, 4f, hot ? Draw.mix(t.c("button.bg"), t.c("text.primary"), 0.06f) : t.c("panel.bg"), t.c("panel.border"));
        Draw.label(g, label, x + 7, y + h / 2f, 12.5f, Weight.MEDIUM, active ? t.c("text.primary") : t.c("text.secondary"));
        hits.add(x, y, w, h, b -> action.run());
        return w;
    }

    private void sendPunish() {
        if (punishKind == null || punishTarget == null) return;
        CompoundTag a = new CompoundTag();
        a.putUUID("target", punishTarget);
        a.putString("kind", punishKind.id());
        a.putLong("term", punishTerm);
        a.putInt("rule", punishRule);
        a.putString("reason", reason.getValue().strip());
        a.putBoolean("shown", punishShown);
        HelmClient.action("punish", a);
        closePunish();
    }

    // ---- правила ----

    private void rules(GuiGraphics g) {
        CompoundTag r = View.rules;
        int x = px + 18, top = py + HEAD + 10, w = 370;
        String server = r.getString("server").isEmpty() ? HelmClient.server : r.getString("server");
        Draw.label(g, r.getString("title").isEmpty() ? "Правила сервера" : r.getString("title"), x, top + 7, 18, Weight.DISPLAY, t.c("text.primary"));
        long date = r.getLong("date");
        Draw.label(g, (date > 0 ? "от " + Words.dayMonth(date) + " · " : "") + server, x, top + 19, 12, Weight.REGULAR, t.c("text.muted"));
        int listTop = top + 30, listBottom = py + H - 10;
        ListTag items = r.getList("items", Tag.TAG_COMPOUND);
        int content = 0;
        for (Tag xi : items) content += 9 + 8 * Math.max(1, Draw.wrapLabel(((CompoundTag) xi).getString("detail"), w - 22, 12, Weight.REGULAR, 4).size()) + 8;
        int scroll = Math.round(rulesScroll.update(Math.max(0, content - (listBottom - listTop))));
        hits.clip(listTop, listBottom);
        Draw.scissor(g, px + 1, listTop, px + SPLIT - 1, listBottom);
        int y = listTop + 6 - scroll;
        if (items.isEmpty()) Draw.label(g, HelmClient.present ? "Правил пока нет." : "На сервере нет RYSHelm.", x, y + 5, 12.5f, Weight.REGULAR, t.c("text.muted"));
        for (Tag xi : items) {
            CompoundTag i = (CompoundTag) xi;
            Draw.label(g, Integer.toString(i.getInt("n")), x, y + 4, 13, Weight.SEMIBOLD, t.c("text.primary"));
            Draw.label(g, i.getString("title"), x + 15, y + 4, 13, Weight.MEDIUM, t.c("text.primary"));
            y += 9;
            var lines = Draw.wrapLabel(i.getString("detail"), w - 22, 12, Weight.REGULAR, 4);
            for (String line : lines) {
                Draw.label(g, line, x + 15, y + 4, 12, Weight.REGULAR, t.c("text.muted"));
                y += 8;
            }
            if (lines.isEmpty()) y += 8;
            y += 8;
        }
        g.disableScissor();
        hits.unclip();
        Draw.round(g, px + SPLIT, py + HEAD, 0.5f, H - HEAD - 1, 0.02f, t.c("panel.border"));
        int sx = px + SPLIT + 9, sw = W - SPLIT - 18, sy = top;
        if (r.getBoolean("pending")) {
            Draw.label(g, "Правила не приняты", sx, sy + 7, 16, Weight.DISPLAY, t.c("state.warn"));
            int h = Ui.paragraph(g, "Пока правила не приняты, можно ходить и читать чат. Строить, открывать, писать и телепортироваться нельзя.", sx, sy + 18, sw, 12.5f, t.c("text.body"), 5);
            Ui.primary(g, t, hits, sx, sy + 26 + h, sw, 20, Icons.Shape.CHECK, "Принимаю", mx, my, () -> HelmClient.action("rules_accept"));
        } else if (r.contains("accepted")) {
            Draw.label(g, "Вы приняли правила", sx, sy + 7, 16, Weight.DISPLAY, t.c("text.primary"));
            Draw.label(g, Words.dayMonth(r.getLong("accepted")) + " в " + Words.time(r.getLong("accepted")), sx, sy + 19, 12, Weight.REGULAR, t.c("text.muted"));
        } else {
            Draw.label(g, "Правила сервера", sx, sy + 7, 16, Weight.DISPLAY, t.c("text.primary"));
            Draw.label(g, r.getBoolean("onJoin") ? "окно при входе включено" : "окно при входе выключено", sx, sy + 19, 12, Weight.REGULAR, t.c("text.muted"));
        }
        int cy = sy + 70;
        String info = "Номера пунктов те же, что в жалобах и наказаниях.";
        if (HelmClient.can("ryshelm.admin")) info += " Текст лежит в config/ryshelm/rules.md: после правки /rys admin reload, и все примут правила заново.";
        Ui.callout(g, t, info, sx, cy, sw, false);
    }

    // ---- журнал (макет 12) ----

    private void journal(GuiGraphics g) {
        CompoundTag j = View.journal;
        int left = px + 10, top = py + HEAD, listW = 422;
        int[] counts = j.getIntArray("counts");
        String[][] cats = {{"", "Все"}, {"PUNISH", "Наказания"}, {"WATCH", "Инвентарь и наблюдение"}, {"PLACES", "Места"}, {"ROLES", "Роли и настройки"}, {"REPORTS", "Жалобы"}};
        int x = left, fy = top + 9;
        for (int i = 0; i < cats.length; i++) {
            String[] c = cats[i];
            int n = counts.length > i ? counts[i] : 0;
            x += Kit.pill(g, t, hits, x, fy, c[1] + " · " + n, journalCat.equals(c[0]), mx, my, () -> {
                journalCat = c[0];
                journalScroll.reset();
                request();
            }) + 2;
        }
        ListTag entries = j.getList("entries", Tag.TAG_COMPOUND);
        String q = search.getValue().strip().toLowerCase(Locale.ROOT);
        List<CompoundTag> shown = new ArrayList<>();
        for (Tag e : entries) {
            CompoundTag c = (CompoundTag) e;
            if (!q.isEmpty() && !(c.getString("a") + " " + c.getString("p") + " " + c.getString("n") + " " + c.getString("d")).toLowerCase(Locale.ROOT).contains(q)) continue;
            shown.add(c);
        }
        int listTop = top + 29, rowH = 19, listBottom = py + H - 10;
        int boxH = Math.min(listBottom - listTop, Math.max(rowH, shown.size() * rowH));
        Draw.roundBox(g, left, listTop, listW, boxH, 5f, t.c("card.bg"), t.c("panel.border"));
        int scroll = Math.round(journalScroll.update(Math.max(0, shown.size() * rowH - boxH)));
        hits.clip(listTop, listTop + boxH);
        Draw.scissor(g, left + 1, listTop + 1, left + listW - 1, listTop + boxH - 1);
        int y = listTop - scroll;
        if (shown.isEmpty()) Draw.label(g, "Записей нет.", left + 6, listTop + 9.5f, 12.5f, Weight.REGULAR, t.c("text.muted"));
        for (int i = 0; i < shown.size(); i++) {
            if (y + rowH >= listTop && y <= listTop + boxH) entry(g, shown.get(i), left, y, listW, rowH, i > 0);
            y += rowH;
        }
        g.disableScissor();
        hits.unclip();
        // справа отбор и хранение
        int sx = px + 444, sw = W - 444 - 10;
        Ui.caps(g, t, "Отбор", sx, top + 13);
        Ui.input(g, t, who, sx, top + 22, sw, 16, mx, my);
        if (who.getValue().isEmpty() && !who.isFocused()) Icons.draw(g, Icons.Shape.USER, sx + 4, top + 27, 6, t.c("text.muted"));
        Draw.label(g, "За", sx, top + 53, 13, Weight.MEDIUM, t.c("text.primary"));
        String days = journalDays == 0 ? "всё время" : Term.plural(journalDays, "день", "дня", "дней");
        Kit.dropdown(g, t, hits, daysDd, sx + sw - 65, top + 46, 65, days, journalDays != 7, mx, my);
        Ui.caps(g, t, "Хранение", sx, top + 71);
        Draw.label(g, "Журнал хранит", sx, top + 89, 13, Weight.MEDIUM, t.c("text.primary"));
        int keep = j.getInt("keep");
        String keepText = Term.plural(keep, "день", "дня", "дней");
        if (HelmClient.can("ryshelm.admin")) Kit.dropdown(g, t, hits, keepDd, sx + sw - 65, top + 82, 65, keepText, false, mx, my);
        else Ui.right(g, keepText, sx + sw, top + 89, 12.5f, Weight.REGULAR, t.c("text.secondary"));
        String month = java.time.YearMonth.now().toString();
        Ui.callout(g, t, "Журнал видят модераторы и операторы. Из окна запись не удаляется. Файлы по месяцам: world/ryshelm/journal-" + month + ".jsonl.", sx, top + 102, sw, false);
    }

    private void entry(GuiGraphics g, CompoundTag e, int x, int y, int w, int h, boolean divider) {
        if (divider) Draw.round(g, x + 1, y, w - 2, 0.5f, 0.02f, t.c("panel.border"));
        float cy = y + h / 2f;
        Draw.label(g, Words.when(e.getLong("t")), x + 6, cy, 11.5f, Weight.REGULAR, t.c("text.muted"));
        String type = e.getString("type");
        Draw.round(g, x + 44, y + 3.5f, 12, 12, 3f, t.c("filter.active.bg"));
        Icons.draw(g, Words.icon(type), x + 47, y + 6.5f, 6, t.c("text.secondary"));
        String note = Words.place(e.getString("n"));
        float noteW = Math.min(150, Draw.labelWidth(note, 11, Weight.REGULAR));
        float tx = x + 62, end = x + w - 8 - noteW - 8;
        for (String[] seg : line(e)) {
            if (seg[0].isEmpty() || tx >= end) continue;
            Weight wt = seg[1].equals("b") ? Weight.SEMIBOLD : Weight.REGULAR;
            String s = Draw.ellipsize(seg[0], end - tx, 12.5f, wt);
            Draw.label(g, s, tx, cy, 12.5f, wt, seg[1].equals("b") ? t.c("text.primary") : t.c("text.body"));
            tx += Draw.labelWidth(s, 12.5f, wt);
        }
        if (!note.isEmpty()) {
            String n = Draw.ellipsize(note, 150, 11, Weight.REGULAR);
            Ui.right(g, n, x + w - 8, cy, 11, Weight.REGULAR, t.c("text.muted"));
            if (!n.equals(note) && Kit.over(mx, my, x + w - 158, y, 150, h)) tooltip = List.of(note);
        }
    }

    /** Строка записи частями: жирное имя, глагол, жирная цель, продолжение (макет 12). */
    static List<String[]> line(CompoundTag e) {
        String type = e.getString("type"), a = e.getString("a"), p = e.getString("p"), d = e.getString("d");
        List<String[]> out = new ArrayList<>();
        switch (type) {
            case "report" -> {
                out.add(new String[] {"Жалоба ", ""});
                out.add(new String[] {a, "b"});
                out.add(new String[] {" на ", ""});
                out.add(new String[] {p, "b"});
            }
            case "join_banned" -> {
                out.add(new String[] {p, "b"});
                out.add(new String[] {" вошёл, прошлый бан закончился " + d, ""});
            }
            case "fly", "god" -> {
                out.add(new String[] {a, "b"});
                out.add(new String[] {" " + (d.isEmpty() ? "переключил" : d) + (p.isEmpty() ? " себе" : " игроку ") , ""});
                if (!p.isEmpty()) out.add(new String[] {p, "b"});
                out.add(new String[] {type.equals("fly") ? " полёт" : " бессмертие", ""});
            }
            case "kit_create", "kit_delete" -> {
                out.add(new String[] {a, "b"});
                out.add(new String[] {" " + Words.verb(type) + " ", ""});
                out.add(new String[] {d, "b"});
            }
            case "kit_give" -> {
                out.add(new String[] {a, "b"});
                out.add(new String[] {" выдал набор " + d + " ", ""});
                out.add(new String[] {p, "b"});
            }
            case "settings", "perm", "caps", "whitelist", "afk_kick" -> {
                out.add(new String[] {a, "b"});
                out.add(new String[] {" " + Words.verb(type) + " " + d, ""});
            }
            default -> {
                out.add(new String[] {a, "b"});
                out.add(new String[] {" " + Words.verb(type) + (p.isEmpty() ? "" : " "), ""});
                if (!p.isEmpty()) out.add(new String[] {p, "b"});
                if (!d.isEmpty()) out.add(new String[] {" " + d, ""});
            }
        }
        return out;
    }

    // ---- роли (макет 11) ----

    private void roles(GuiGraphics g) {
        CompoundTag r = View.roles;
        boolean edit = r.getBoolean("edit"), lp = r.getBoolean("luckperms");
        int left = px + 10, top = py + HEAD;
        // модераторы
        int y = top + 9;
        Draw.label(g, "Модераторы", left, y + 7, 12.5f, Weight.REGULAR, t.c("text.muted"));
        int x = left + (int) Draw.labelWidth("Модераторы", 12.5f, Weight.REGULAR) + 6;
        for (Tag xm : r.getList("moderators", Tag.TAG_COMPOUND)) {
            CompoundTag m = (CompoundTag) xm;
            String name = m.getString("name");
            int cw = (int) Draw.labelWidth(name, 12.5f, Weight.MEDIUM) + 22 + (edit ? 9 : 0);
            Draw.roundBox(g, x, y, cw, 14, 4f, t.c("card.bg"), t.c("panel.border"));
            Ui.face(g, m.getUUID("id"), x + 3, y + 3, 8);
            Draw.label(g, name, x + 15, y + 7, 12.5f, Weight.MEDIUM, t.c("text.primary"));
            if (edit) {
                int bx = x + cw - 10;
                boolean hot = Kit.over(mx, my, bx - 2, y + 2, 10, 10);
                Draw.cross(g, bx - 1, y + 4, hot ? t.c("state.bad") : t.c("text.muted"));
                hits.add(bx - 2, y + 2, 10, 10, b -> {
                    CompoundTag a = new CompoundTag();
                    a.putUUID("target", m.getUUID("id"));
                    a.putBoolean("on", false);
                    HelmClient.action("moderator", a);
                });
            }
            x += cw + 4;
        }
        if (edit) {
            int aw = (int) Draw.labelWidth("Добавить", 12.5f, Weight.MEDIUM) + 20;
            boolean hot = Kit.over(mx, my, x, y, aw, 14);
            Draw.roundBox(g, x, y, aw, 14, 4f, hot ? Draw.mix(t.c("button.bg"), t.c("text.primary"), 0.08f) : t.c("button.bg"), t.c("button.border"));
            Icons.draw(g, Icons.Shape.PLUS, x + 5, y + 4.5f, 5, t.c("button.fg"));
            Draw.label(g, "Добавить", x + 13, y + 7, 12.5f, Weight.MEDIUM, t.c("button.fg"));
            hits.add(x, y, aw, 14, b -> addModDd.open = !addModDd.open);
            // список кандидатов встаёт под кнопкой
            Kit.dropdown(g, t, hits, addModDd, x, y + 16, 0, "", false, -1000, -1000);
            x += aw + 6;
        }
        List<String> ops = new ArrayList<>();
        for (Tag o : r.getList("operators", Tag.TAG_STRING)) ops.add(o.getAsString());
        Draw.label(g, Draw.ellipsize("Операторы: " + (ops.isEmpty() ? "нет" : String.join(", ", ops)) + " · уровень оператора игры", px + W - 10 - x, 12, Weight.REGULAR),
            x, y + 7, 12, Weight.REGULAR, t.c("text.muted"));
        // таблицы
        List<CompoundTag> mod = new ArrayList<>(), other = new ArrayList<>();
        int mods = r.getList("moderators", Tag.TAG_COMPOUND).size();
        for (Tag xn : r.getList("nodes", Tag.TAG_COMPOUND)) {
            CompoundTag n = (CompoundTag) xn;
            (n.getBoolean("moderation") ? mod : other).add(n);
        }
        int tableTop = top + 30, bottom = py + H - 8;
        int step = 16;
        int content = Math.max(20 + mod.size() * step, 20 + other.size() * step + 90);
        int scroll = Math.round(rolesScroll.update(Math.max(0, content - (bottom - tableTop))));
        hits.clip(tableTop, bottom);
        Draw.scissor(g, px + 1, tableTop, px + W - 1, bottom);
        int ty = tableTop - scroll;
        table(g, "Модерация", mod, left, ty, 320, edit && !lp, mods, ops.size(), step, r);
        int rx = px + 340;
        int endY = table(g, "Места, приват, оператор", other, rx, ty, W - 350, edit && !lp, mods, ops.size(), step, r);
        int iy = endY + 8;
        String info = lp ? "На сервере стоит LuckPerms: права решает он. Таблица показывает узлы и наше умолчание, клик по узлу копирует его имя. Узел, который в LuckPerms не задан, решает умолчание таблицы."
            : "LuckPerms на сервере нет: права решает эта таблица. Поставите LuckPerms, он станет главным, а таблица покажет узлы, которые туда переписать. Моды семьи добавляют сюда свои строки сами.";
        iy += Ui.callout(g, t, info, rx, iy, W - 350, false) + 6;
        Ui.callout(g, t, "Столбец оператора не меняется: оператор может всё. Модератору уровень оператора игры не нужен.", rx, iy, W - 350, false);
        g.disableScissor();
        hits.unclip();
    }

    /** Таблица прав; возвращает низ. */
    private int table(GuiGraphics g, String title, List<CompoundTag> nodes, int x, int y, int w, boolean edit, int mods, int ops, int step, CompoundTag r) {
        int c1 = x + w - 118, c2 = x + w - 66, c3 = x + w - 18;
        Ui.caps(g, t, title, x + 2, y + 9);
        column(g, "Игрок", "все", c1, y);
        column(g, "Модератор", Term.plural(mods, "игрок", "игрока", "игроков"), c2, y);
        column(g, "Оператор", Term.plural(Math.max(ops, 1), "игрок", "игрока", "игроков"), c3, y);
        int ry = y + 20;
        Draw.round(g, c3 - 18, ry, 36, nodes.size() * step, 0.02f, Draw.alpha(t.c("filter.active.bg"), 0.45f));
        for (CompoundTag n : nodes) {
            Draw.round(g, x, ry, w, 0.5f, 0.02f, t.c("panel.border"));
            String id = n.getString("id");
            String label = n.contains("key") ? Component.translatable(n.getString("key")).getString() : n.getString("label");
            if (label.equals(n.getString("key"))) label = n.getString("label");
            Icons.draw(g, nodeIcon(id), x + 3, ry + 3, 6, t.c("text.secondary"));
            float room = c1 - x - 32;
            Draw.label(g, Draw.ellipsize(label, room, 12.5f, Weight.MEDIUM), x + 14, ry + 5, 12.5f, Weight.MEDIUM, t.c("text.primary"));
            Draw.label(g, id, x + 14, ry + 12.5f, 10, Weight.REGULAR, t.c("text.muted"));
            if (r.getBoolean("luckperms") && Kit.over(mx, my, x + 14, ry + 9, (int) Draw.labelWidth(id, 10, Weight.REGULAR), 7)) {
                tooltip = List.of("Клик копирует узел: " + id);
                hits.add(x + 14, ry + 9, (int) Draw.labelWidth(id, 10, Weight.REGULAR), 7, b -> Minecraft.getInstance().keyboardHandler.setClipboard(id));
            }
            cell(g, n, "player", c1, ry, edit);
            cell(g, n, "moderator", c2, ry, edit);
            Kit.checkbox(g, t, hits, c3 - 4, ry + 4, true, mx, my, null);
            // потолок модератора у заглушения и бана на срок
            if (id.equals("ryshelm.mute") || id.equals("ryshelm.ban.temp")) {
                boolean mute = id.equals("ryshelm.mute");
                long cap = r.getLong(mute ? "muteMax" : "banMax");
                String text = "до " + Term.label(cap);
                float tw = Draw.labelWidth(text, 10, Weight.REGULAR);
                int tx = c2 + 7;
                boolean hot = edit && Kit.over(mx, my, tx, ry + 2, (int) tw + 6, 12);
                Draw.label(g, text, tx, ry + 7, 10, Weight.REGULAR, hot ? t.c("text.primary") : t.c("text.muted"));
                if (edit) {
                    Icons.draw(g, Icons.Shape.CHEVRON_DOWN, tx + tw + 1, ry + 5, 4, t.c("text.muted"));
                    hits.add(tx, ry + 2, (int) tw + 6, 12, b -> {
                        long[] presets = mute ? new long[] {Term.HOUR, 6 * Term.HOUR, Term.DAY, 3 * Term.DAY} : new long[] {Term.DAY, 3 * Term.DAY, 7 * Term.DAY, 30 * Term.DAY};
                        int i = 0;
                        while (i < presets.length && presets[i] != cap) i++;
                        CompoundTag a = new CompoundTag();
                        a.putLong(mute ? "mute" : "ban", presets[(i + 1) % presets.length]);
                        HelmClient.action("caps", a);
                    });
                    if (hot) tooltip = List.of("Потолок модератора: клик меняет");
                }
            }
            ry += step;
        }
        return ry;
    }

    private void column(GuiGraphics g, String title, String sub, int cx, int y) {
        float tw = Draw.labelWidth(title, 12, Weight.SEMIBOLD);
        Draw.label(g, title, cx - tw / 2f, y + 5, 12, Weight.SEMIBOLD, t.c("text.primary"));
        float sw = Draw.labelWidth(sub, 10.5f, Weight.REGULAR);
        Draw.label(g, sub, cx - sw / 2f, y + 13, 10.5f, Weight.REGULAR, t.c("text.muted"));
    }

    private void cell(GuiGraphics g, CompoundTag n, String role, int cx, int ry, boolean edit) {
        boolean on = n.getBoolean(role);
        Kit.checkbox(g, t, hits, cx - 4, ry + 4, on, mx, my, edit ? () -> {
            CompoundTag a = new CompoundTag();
            a.putString("node", n.getString("id"));
            a.putString("role", role);
            a.putBoolean("value", !on);
            HelmClient.action("perm", a);
        } : null);
    }

    static Icons.Shape nodeIcon(String id) {
        return HelmClient.shape(switch (id) {
            case "ryshelm.warn" -> "WARN";
            case "ryshelm.mute" -> "MUTE";
            case "ryshelm.kick" -> "EXIT";
            case "ryshelm.ban.temp", "ryshelm.ban" -> "BAN";
            case "ryshelm.reports", "ryshelm.report" -> "FLAG";
            case "ryshelm.invsee", "ryshelm.invsee.edit", "ryshelm.enderchest", "ryshelm.enderchest.other" -> "CHEST";
            case "ryshelm.spectate" -> "EYE";
            case "ryshelm.freeze" -> "SNOW";
            case "ryshelm.journal" -> "LIST";
            case "ryshelm.tp" -> "TELEPORT";
            case "ryshelm.note" -> "NOTE";
            case "ryshelm.staffchat" -> "CHAT";
            case "ryshelm.players" -> "USERS";
            case "ryshelm.trash" -> "TRASH";
            case "ryshelm.kickme" -> "EXIT";
            case "ryshelm.cheat" -> "HEART";
            case "ryshelm.move" -> "MOVE";
            case "ryshelm.admin" -> "GEAR";
            default -> id.startsWith("rysmap.place") ? "SERVER" : id.startsWith("rysmap.cooldown") ? "CLOCK" : id.startsWith("rysmap") ? "TELEPORT"
                : id.startsWith("rysclaims") ? "SHIELD" : "KEY";
        });
    }

    // ---- настройки ----

    private boolean settingsLoaded;

    private void settings(GuiGraphics g) {
        CompoundTag s = View.settings;
        if (!settingsLoaded && s.contains("ladder")) {
            settingsLoaded = true;
            welcome.setValue(s.getString("welcome"));
            serverName.setValue(s.getString("serverName"));
            ladder.setValue(s.getString("ladder"));
        }
        int x = px + 18, w = 360, y = py + HEAD + 12;
        Ui.caps(g, t, "Публичный вход", x, y + 3);
        y += 12;
        y = toggleRow(g, x, y, w, "Правила при входе", "окно при первом входе и после правки правил · " + s.getString("rulesFile") + " · " + Term.plural(s.getInt("rulesCount"), "пункт", "пункта", "пунктов"),
            s.getBoolean("rulesOnJoin"), v -> set("rulesOnJoin", v));
        y = stepperRow(g, x, y, w, "Новичок, часов игры", "нельзя звать к себе кроме своей команды; 0 выключено", s.getInt("newbieHours"), 0, 100, 1, v -> setInt("newbieHours", v));
        y = fieldRow(g, x, y, w, "Приветствие", welcome, "welcome");
        y = fieldRow(g, x, y, w, "Имя сервера", serverName, "serverName");
        y += 6;
        Ui.caps(g, t, "Наказания и жалобы", x, y + 3);
        y += 12;
        y = fieldRow(g, x, y, w, "Лестница", ladder, "ladder");
        y = stepperRow(g, x, y, w, "Лестница считает, дней", "наказания старше не считаются", s.getInt("ladderDays"), 1, 365, 5, v -> setInt("ladderDays", v));
        y = stepperRow(g, x, y, w, "Пауза между жалобами, с", "одного игрока", s.getInt("reportCooldown"), 0, 3600, 30, v -> setInt("reportCooldown", v));
        y = toggleRow(g, x, y, w, "Антиспам чата", "не больше " + s.getInt("spamMessages") + " строк за " + s.getInt("spamSeconds") + " с, повтор 3 раза; персонал не проверяется",
            s.getBoolean("antiSpam"), v -> set("antiSpam", v));
        if (s.getBoolean("antiSpam")) y = stepperRow(g, x, y, w, "Антиспам глушит, мин", "после трёх отказов за 2 минуты; 0 только отказывать", s.getInt("spamMuteMinutes"), 0, 120, 5, v -> setInt("spamMuteMinutes", v));
        y = stepperRow(g, x, y, w, "Журнал хранит, дней", "старые месяцы уходят при запуске сервера", s.getInt("journalDays"), 7, 3650, 30, v -> setInt("journalDays", v));
        Draw.round(g, px + SPLIT, py + HEAD, 0.5f, H - HEAD - 1, 0.02f, t.c("panel.border"));
        int sx = px + SPLIT + 9, sw = W - SPLIT - 18;
        kits(g, s, sx, py + HEAD + 150, sw);
        Ui.callout(g, t, "Настройки лежат в world/serverconfig/ryshelm-server.json. Каждая правка ложится в журнал. Потолки сроков модератора меняются во вкладке «Роли».", sx, py + HEAD + 12, sw, false);
        Ui.callout(g, t, "Лестница: шаги через запятую, например warn, mute 1h, ban 1d, ban 7d, ban perm. Окно наказания подсказывает следующий шаг по числу наказаний игрока.", sx, py + HEAD + 70, sw, false);
    }

    /** Наборы (план 9): список, стартовый набор кликом. Собирает оператор командой из своего инвентаря. */
    private void kits(GuiGraphics g, CompoundTag s, int x, int y, int w) {
        Ui.caps(g, t, "Наборы", x, y + 3);
        String starter = s.getString("starterKit");
        ListTag kits = s.getList("kits", Tag.TAG_COMPOUND);
        y += 10;
        if (kits.isEmpty()) {
            Ui.paragraph(g, "Наборов нет. Соберите из своего инвентаря: /rys admin kits create start once", x, y, w, 11.5f, t.c("text.muted"), 3);
            return;
        }
        for (Tag xk : kits) {
            CompoundTag k = (CompoundTag) xk;
            String name = k.getString("name");
            boolean on = name.equals(starter);
            works.repka.ryscore.ui.Kit.radio(g, t, hits, x, y + 2, 7, on, mx, my, () -> {
                CompoundTag a = new CompoundTag();
                a.putString("name", on ? "" : name);
                HelmClient.action("kit_starter", a);
            });
            Draw.label(g, name, x + 11, y + 5.5f, 12.5f, Weight.MEDIUM, t.c("text.primary"));
            Ui.right(g, Term.plural(k.getInt("items"), "предмет", "предмета", "предметов") + " · " + k.getString("rule"), x + w, y + 5.5f, 11, Weight.REGULAR, t.c("text.muted"));
            y += 12;
        }
        Draw.label(g, starter.isEmpty() ? "стартовый набор выключен" : "стартовый: «" + starter + "», при первом входе", x, y + 5, 11, Weight.REGULAR, t.c("text.muted"));
    }

    interface BoolSet {
        void set(boolean v);
    }

    interface IntSet {
        void set(int v);
    }

    private int toggleRow(GuiGraphics g, int x, int y, int w, String title, String sub, boolean on, BoolSet set) {
        Draw.label(g, title, x, y + 5, 13, Weight.MEDIUM, t.c("text.primary"));
        Draw.label(g, Draw.ellipsize(sub, w - 30, 11, Weight.REGULAR), x, y + 13.5f, 11, Weight.REGULAR, t.c("text.muted"));
        boolean hot = Kit.over(mx, my, x + w - 18, y + 3, 18, 10);
        Kit.toggle(g, t, x + w - 18, y + 3, on, hot);
        hits.add(x + w - 18, y + 3, 18, 10, b -> set.set(!on));
        return y + 22;
    }

    private int stepperRow(GuiGraphics g, int x, int y, int w, String title, String sub, int value, int min, int max, int stepBy, IntSet set) {
        Draw.label(g, title, x, y + 5, 13, Weight.MEDIUM, t.c("text.primary"));
        Draw.label(g, Draw.ellipsize(sub, w - 90, 11, Weight.REGULAR), x, y + 13.5f, 11, Weight.REGULAR, t.c("text.muted"));
        Kit.stepper(g, t, hits, x + w - 70, y, 70, Integer.toString(value), mx, my, () -> set.set(Math.max(min, value - stepBy)), () -> set.set(Math.min(max, value + stepBy)));
        return y + 23;
    }

    private int fieldRow(GuiGraphics g, int x, int y, int w, String title, Field f, String key) {
        Draw.label(g, title, x, y + 8, 13, Weight.MEDIUM, t.c("text.primary"));
        Ui.input(g, t, f, x + 110, y, w - 110 - 58, 16, mx, my);
        Kit.button(g, t, hits, x + w - 54, y, 54, 16, "Сохранить", false, mx, my, () -> {
            CompoundTag a = new CompoundTag();
            a.putString(key, f.getValue().strip());
            HelmClient.action("settings", a);
            settingsLoaded = false;
        });
        return y + 22;
    }

    private void set(String key, boolean v) {
        CompoundTag a = new CompoundTag();
        a.putBoolean(key, v);
        HelmClient.action("settings", a);
    }

    private void setInt(String key, int v) {
        CompoundTag a = new CompoundTag();
        a.putInt(key, v);
        HelmClient.action("settings", a);
    }

    // ---- ввод ----

    @Override
    protected boolean onClick(double x, double y, int button) {
        if (punishKind == null && menuFor != null && !hits.click(x, y, button)) {
            menuFor = null;
            return true;
        }
        if (hits.click(x, y, button)) return true;
        for (Kit.Dropdown d : new Kit.Dropdown[] {sortDd, ruleDd, daysDd, keepDd, addModDd}) d.close();
        reports.closeDropdowns();
        boolean used = super.onClick(x, y, button);
        if (!used) {
            setFocused(null);
            for (Field f : fields) f.setFocused(false);
        }
        return used;
    }

    @Override
    protected boolean onScroll(double x, double y, double dx, double dy) {
        for (Kit.Dropdown d : new Kit.Dropdown[] {sortDd, ruleDd, daysDd, keepDd, addModDd}) if (d.scrolled(x, y, dy)) return true;
        if (reports.scrolled(x, y, dy)) return true;
        float by = -(float) dy * 18;
        switch (tab) {
            case PLAYERS -> {
                if (x >= px + SPLIT) cardScroll.by(by);
                else listScroll.by(by);
            }
            case RULES -> rulesScroll.by(by);
            case JOURNAL -> journalScroll.by(by);
            case ROLES -> rolesScroll.by(by);
            case REPORTS -> reports.scroll(x, by);
            default -> {}
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        for (Kit.Dropdown d : new Kit.Dropdown[] {sortDd, ruleDd, daysDd, keepDd, addModDd}) if (d.keyPressed(key)) return true;
        if (reports.keyPressed(key)) return true;
        for (Field f : fields) {
            if (f.isFocused() && f.isVisible()) {
                if (key == 256) {
                    f.setFocused(false);
                    setFocused(null);
                    return true;
                }
                if (key == 257 || key == 335) {
                    if (f == noteBox && selected != null) saveNote(selected);
                    if (f == who) request();
                    if (f == reportText) reports.send();
                    return true;
                }
                f.keyPressed(key, scan, mods);
                if (f == who) request();
                return true;
            }
        }
        if (key == 256) {
            if (punishKind != null) {
                closePunish();
                return true;
            }
            if (menuFor != null) {
                menuFor = null;
                return true;
            }
            if (reports.formTarget != null) {
                reports.formTarget = null;
                return true;
            }
        }
        if (key == 47 && punishKind == null) {
            // «/» ставит курсор в поиск, как подсказка в макете
            setFocused(search);
            search.setFocused(true);
            return true;
        }
        if (ClientEntry.OPEN.matches(key, scan) || Minecraft.getInstance().options.keyInventory.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        for (Field f : fields) {
            if (f.isFocused() && f.isVisible()) {
                boolean r = f.charTyped(c, mods);
                if (f == who) request();
                return r;
            }
        }
        return super.charTyped(c, mods);
    }
}
