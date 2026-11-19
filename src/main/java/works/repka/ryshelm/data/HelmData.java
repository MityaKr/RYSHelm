package works.repka.ryshelm.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import works.repka.ryshelm.logic.Kind;
import works.repka.ryshelm.logic.Ladder;

/**
 * Всё, что RYSHelm помнит о сервере, одним файлом {@code world/ryshelm/helm.json} (Gson). Поле {@code v} это версия
 * формата. Журнал лежит отдельно по месяцам ({@code Journal}), бан ещё и в списке игры {@code banned-players.json}.
 */
public final class HelmData {
    public static final int VERSION = 1;

    public int v = VERSION;
    /** Модераторы: UUID и последний известный ник. Оператор игры модератором не записывается. */
    public Map<UUID, String> moderators = new LinkedHashMap<>();
    /** Таблица ролей: узел → роль ({@code player}, {@code moderator}) → разрешено. Нет записи: умолчание узла. */
    public Map<String, Map<String, Boolean>> table = new LinkedHashMap<>();
    public List<Punishment> punishments = new ArrayList<>();
    public List<Note> notes = new ArrayList<>();
    public List<Report> reports = new ArrayList<>();
    public Map<UUID, PlayerInfo> players = new LinkedHashMap<>();
    /** Наблюдатели: куда и в каком режиме вернуть (переживает падение сервера). */
    public Map<UUID, Spot> spectating = new LinkedHashMap<>();
    /** Уведомления игрокам вне сети, уходят при входе. */
    public Map<UUID, List<String>> mail = new LinkedHashMap<>();
    public long nextId = 1;

    // индекс наказаний по игроку и номеру (разбор логики 19.11, п. 3): в файл не пишется, строится заново, когда список вырос
    private transient Map<UUID, List<Punishment>> byTarget;
    private transient Map<Long, Punishment> byId;
    private transient List<Punishment> indexedList;
    private transient int indexedSize = -1;

    /** Наказания добавляются только в конец списка; снятие меняет запись на месте, индекс это не ломает. */
    private void index() {
        if (indexedList == punishments && indexedSize == punishments.size()) return;
        Map<UUID, List<Punishment>> t = new java.util.HashMap<>();
        Map<Long, Punishment> ids = new java.util.HashMap<>();
        for (Punishment p : punishments) {
            if (p.target != null) t.computeIfAbsent(p.target, k -> new ArrayList<>(2)).add(p);
            ids.put(p.id, p);
        }
        byTarget = t;
        byId = ids;
        indexedList = punishments;
        indexedSize = punishments.size();
    }

    /** Все наказания игрока в порядке выдачи (живой список индекса, не менять). */
    public List<Punishment> history(UUID target) {
        index();
        List<Punishment> l = byTarget.get(target);
        return l == null ? List.of() : l;
    }

    public long id() {
        return nextId++;
    }

    public PlayerInfo player(UUID id, String name) {
        PlayerInfo p = players.computeIfAbsent(id, k -> new PlayerInfo());
        p.id = id;
        if (name != null && !name.isEmpty()) p.name = name;
        return p;
    }

    public @Nullable PlayerInfo byName(String name) {
        for (PlayerInfo p : players.values()) if (p.name != null && p.name.equalsIgnoreCase(name)) return p;
        return null;
    }

    public String name(UUID id) {
        PlayerInfo p = players.get(id);
        if (p != null && p.name != null) return p.name;
        String m = moderators.get(id);
        return m != null ? m : id.toString().substring(0, 8);
    }

    /** Наказания игрока, новые сверху. */
    public List<Punishment> of(UUID target) {
        List<Punishment> h = history(target);
        List<Punishment> out = new ArrayList<>(h.size());
        for (int i = h.size() - 1; i >= 0; i--) out.add(h.get(i));
        return out;
    }

    /** Действующее наказание вида или null. */
    public @Nullable Punishment active(UUID target, Kind kind, long now) {
        List<Punishment> h = history(target);
        for (int i = h.size() - 1; i >= 0; i--) {
            Punishment p = h.get(i);
            if (p.kind == kind && p.active(now)) return p;
        }
        return null;
    }

    public @Nullable Punishment punishment(long id) {
        index();
        return byId.get(id);
    }

    public @Nullable Report report(long id) {
        for (Report r : reports) if (r.id == id) return r;
        return null;
    }

    /** История для лестницы. */
    public List<Ladder.Past> past(UUID target) {
        List<Ladder.Past> out = new ArrayList<>();
        for (Punishment p : history(target)) out.add(new Ladder.Past(p.kind, p.created, p.revoked > 0));
        return out;
    }

    /** Наказание: предупреждение, заглушить, выгнать, бан. */
    public static final class Punishment {
        public long id;
        public Kind kind;
        public UUID target;
        public String targetName = "";
        /** Кто дал; у консоли null. */
        public @Nullable UUID by;
        public String byName = "";
        /** Номер пункта правил или 0. */
        public int rule;
        public String reason = "";
        /** Игрок видит причину. */
        public boolean shown = true;
        public long created;
        /** Когда кончается; 0 навсегда или без срока. */
        public long expires;
        /** Когда сняли, 0 не снимали. */
        public long revoked;
        public String revokedBy = "";

        public boolean active(long now) {
            if (revoked > 0) return false;
            if (!kind.timed()) return false;
            return expires == 0 || expires > now;
        }

        public boolean permanent() {
            return kind.timed() && expires == 0;
        }
    }

    /** Заметка персонала об игроке. */
    public static final class Note {
        public long id;
        public UUID target;
        public @Nullable UUID by;
        public String byName = "";
        public String text = "";
        public long time;
    }

    /** Место: измерение, координаты, взгляд и режим игры (для наблюдения). */
    public static final class Spot {
        public String dim = "minecraft:overworld";
        public double x, y, z;
        public float yRot, xRot;
        public String mode = "";
        public long since;
        public @Nullable UUID target;
        public String targetName = "";

        public static Spot of(String dim, double x, double y, double z) {
            Spot s = new Spot();
            s.dim = dim;
            s.x = x;
            s.y = y;
            s.z = z;
            return s;
        }

        /** «Верхний мир · 1 402 · 58 · −418» без названия измерения: только числа. */
        public String coords() {
            return group((long) Math.floor(x)) + " · " + (long) Math.floor(y) + " · " + group((long) Math.floor(z));
        }

        static String group(long n) {
            String s = Long.toString(Math.abs(n));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                if (i > 0 && (s.length() - i) % 3 == 0) b.append(' ');
                b.append(s.charAt(i));
            }
            return (n < 0 ? "−" : "") + b;
        }
    }

    /** Что сервер знает об игроке, в том числе вне сети. */
    public static final class PlayerInfo {
        public UUID id;
        public String name = "";
        public long firstSeen, lastSeen;
        /** Наиграно на момент последнего выхода, мс. */
        public long played;
        public @Nullable Spot lastPos;
        /** Версия принятых правил и когда. */
        public String rules = "";
        public long rulesAt;
        /** «Переставить» вне сети: место при следующем входе. */
        public @Nullable Spot moveTo;
        public String moveBy = "";
        /** Наборы: имя → когда брал. */
        public Map<String, Long> kits = new LinkedHashMap<>();
        /** Последняя жалоба этого игрока: не чаще раза в 2 минуты. */
        public long lastReport;
    }

    /** Жалоба игрока (план 5.5). */
    public static final class Report {
        public enum State { NEW, TAKEN, CLOSED }

        public long id;
        public UUID from;
        public String fromName = "";
        public UUID target;
        public String targetName = "";
        public int rule;
        public String text = "";
        public long time;
        public State state = State.NEW;
        public @Nullable UUID takenBy;
        public String takenByName = "";
        /** Итог: {@code punished}, {@code rejected}. */
        public String outcome = "";
        public long closedAt;
        public @Nullable Spot fromPos, targetPos;
        /** Чья земля под нарушителем в момент жалобы. */
        public String land = "";
        public List<ChatLine> chat = new ArrayList<>();
    }

    public record ChatLine(long time, String text) {}
}
