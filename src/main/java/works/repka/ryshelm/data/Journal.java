package works.repka.ryshelm.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

/**
 * Журнал персонала (план 5.6): всё, что делает персонал, и важные события. Файлы по месяцам
 * {@code world/ryshelm/journal-2026-11.jsonl}, одна запись на строку. Из окна запись не удаляется; месяцы старше срока
 * хранения убираются при запуске сервера. В памяти последние записи за срок, не больше {@link #KEEP}.
 */
public final class Journal {
    public static final int KEEP = 20_000;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Раздел фильтра окна (макет 12). */
    public enum Category { PUNISH, WATCH, PLACES, ROLES, REPORTS, OTHER }

    /** Запись. Поля короткие: файл растёт месяцами. */
    public static final class Entry {
        public long t;
        /** Вид: {@code mute}, {@code invsee}, {@code fly}, {@code report}, {@code join_banned}… */
        public String type = "";
        public @Nullable UUID a;
        public String an = "";
        public @Nullable UUID p;
        public String pn = "";
        /** Продолжение строки: «на 1 ч», «4 мин». */
        public String d = "";
        /** Справа серым: «п. 3 · мат в общем чате». */
        public String n = "";

        public Category category() {
            return Journal.category(type);
        }
    }

    public static Category category(String type) {
        return switch (type) {
            case "warn", "mute", "unmute", "kick", "ban", "unban", "join_banned", "note" -> Category.PUNISH;
            case "invsee", "invsee_edit", "enderchest", "spectate", "spectate_start", "freeze", "unfreeze", "tp", "tphere", "move",
                 "fly", "god", "heal", "feed", "speed", "extinguish", "kit_give" -> Category.WATCH;
            case "place", "place_set", "place_del" -> Category.PLACES;
            case "moderator_add", "moderator_remove", "perm", "settings", "caps", "kit_create", "kit_delete", "whitelist" -> Category.ROLES;
            case "report", "report_take", "report_close" -> Category.REPORTS;
            default -> Category.OTHER;
        };
    }

    private final Path dir;
    private final ZoneId zone;
    private final Deque<Entry> recent = new ArrayDeque<>();
    private int retentionDays;
    // открытый файл месяца (разбор логики 19.11, п. 6): запись в буфер, на диск раз в секунду и при остановке
    private @Nullable BufferedWriter out;
    private @Nullable Path outFile;

    public Journal(Path dir, int retentionDays, ZoneId zone) {
        this.dir = dir;
        this.retentionDays = retentionDays;
        this.zone = zone;
    }

    public void retention(int days) {
        retentionDays = days;
    }

    public Path dir() {
        return dir;
    }

    public static String fileName(YearMonth month) {
        return String.format(Locale.ROOT, "journal-%04d-%02d.jsonl", month.getYear(), month.getMonthValue());
    }

    /** Читает месяцы в сроке; месяцы целиком старше срока убирает. Возвращает число прочитанных записей. */
    public int load(long now) throws IOException {
        recent.clear();
        if (!Files.isDirectory(dir)) return 0;
        LocalDate cutoff = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(retentionDays);
        YearMonth oldest = YearMonth.from(cutoff);
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(f -> f.getFileName().toString().matches("journal-\\d{4}-\\d{2}\\.jsonl")).sorted().forEach(files::add);
        }
        long from = cutoff.atStartOfDay(zone).toInstant().toEpochMilli();
        int n = 0;
        for (Path f : files) {
            String name = f.getFileName().toString();
            YearMonth m = YearMonth.of(Integer.parseInt(name.substring(8, 12)), Integer.parseInt(name.substring(13, 15)));
            if (m.isBefore(oldest)) {
                // срок хранения задаёт оператор сервера: старый месяц уходит
                Files.deleteIfExists(f);
                continue;
            }
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    Entry e = GSON.fromJson(line, Entry.class);
                    if (e == null || e.t < from) continue;
                    remember(e);
                    n++;
                } catch (JsonParseException ignored) {
                    // битая строка (сервер упал посреди записи) не мешает остальным
                }
            }
        }
        return n;
    }

    private void remember(Entry e) {
        recent.addLast(e);
        while (recent.size() > KEEP) recent.removeFirst();
    }

    /** Дописать запись в буфер файла месяца и в память. На диск она попадёт при {@link #flush} или {@link #close}. */
    public Entry add(Entry e) throws IOException {
        if (e.t == 0) e.t = System.currentTimeMillis();
        remember(e);
        Path file = dir.resolve(fileName(YearMonth.from(Instant.ofEpochMilli(e.t).atZone(zone))));
        if (out == null || !file.equals(outFile)) {
            close();
            Files.createDirectories(dir);
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            outFile = file;
        }
        out.write(GSON.toJson(e));
        out.write('\n');
        return e;
    }

    /** Сбросить буфер на диск. */
    public void flush() throws IOException {
        if (out != null) out.flush();
    }

    /** Сбросить и закрыть файл месяца; следующая запись откроет его снова. */
    public void close() throws IOException {
        BufferedWriter w = out;
        out = null;
        outFile = null;
        if (w != null) w.close();
    }

    /** Записи по фильтру, новые сверху, не больше {@code limit}. */
    public List<Entry> query(Predicate<Entry> filter, int limit) {
        List<Entry> out = new ArrayList<>();
        var it = recent.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            Entry e = it.next();
            if (filter.test(e)) out.add(e);
        }
        return out;
    }

    /** Счётчики разделов для пилюль макета 12 («Наказания · 31»). */
    public int[] counts(Predicate<Entry> filter) {
        int[] c = new int[Category.values().length + 1];
        for (Entry e : recent) {
            if (!filter.test(e)) continue;
            c[0]++;
            c[e.category().ordinal() + 1]++;
        }
        return c;
    }

    public int size() {
        return recent.size();
    }

    public static Entry entry(String type, @Nullable UUID actor, String actorName, @Nullable UUID target, String targetName, String detail, String note) {
        Entry e = new Entry();
        e.type = type;
        e.a = actor;
        e.an = actorName == null ? "" : actorName;
        e.p = target;
        e.pn = targetName == null ? "" : targetName;
        e.d = detail == null ? "" : detail;
        e.n = note == null ? "" : note;
        return e;
    }
}
