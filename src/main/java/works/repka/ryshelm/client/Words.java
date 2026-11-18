package works.repka.ryshelm.client;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import works.repka.ryshelm.logic.Term;

/** Слова окна «Сервер»: измерения, «в сети 2 ч», «3 дня назад», глаголы журнала. */
final class Words {
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZONE);
    private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("dd.MM").withZone(ZONE);
    private static final String[] MONTHS = {"января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"};

    private Words() {}

    /** «Верхний мир», «Нижний мир», «Край», иначе имя без пространства с заглавной. */
    static String dim(String id) {
        return switch (id) {
            case "minecraft:overworld" -> "Верхний мир";
            case "minecraft:the_nether" -> "Нижний мир";
            case "minecraft:the_end" -> "Край";
            default -> {
                String p = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
                p = p.replace('_', ' ');
                yield p.isEmpty() ? id : Character.toUpperCase(p.charAt(0)) + p.substring(1);
            }
        };
    }

    /** «80 м», «1,4 км». */
    static String distance(int m) {
        if (m < 1000) return m + " м";
        return String.format(Locale.ROOT, "%.1f км", m / 1000.0).replace('.', ',');
    }

    /** Длительность словами: «42 мин», «2 ч», «3 дня». */
    static String span(long millis) {
        if (millis < Term.MINUTE) return "минуту";
        if (millis < Term.HOUR) return millis / Term.MINUTE + " мин";
        if (millis < Term.DAY) return millis / Term.HOUR + " ч";
        return Term.plural(millis / Term.DAY, "день", "дня", "дней");
    }

    /** «в сети 42 мин». */
    static String online(long since) {
        return "в сети " + span(Math.max(0, System.currentTimeMillis() - since));
    }

    /** «3 дня назад», «сегодня в 14:05», «давно». */
    static String ago(long t) {
        if (t <= 0) return "давно";
        long d = System.currentTimeMillis() - t;
        if (d < Term.HOUR) return Math.max(1, d / Term.MINUTE) + " мин назад";
        if (d < Term.DAY) return d / Term.HOUR + " ч назад";
        return Term.plural(d / Term.DAY, "день", "дня", "дней") + " назад";
    }

    /** Время строки списка: «14:05» сегодня, «вчера», иначе «16.11». */
    static String when(long t) {
        LocalDate day = Instant.ofEpochMilli(t).atZone(ZONE).toLocalDate(), today = LocalDate.now(ZONE);
        if (day.equals(today)) return HM.format(Instant.ofEpochMilli(t));
        if (day.equals(today.minusDays(1))) return "вчера";
        return DM.format(Instant.ofEpochMilli(t));
    }

    static String date(long t) {
        return DM.format(Instant.ofEpochMilli(t));
    }

    static String time(long t) {
        return HM.format(Instant.ofEpochMilli(t));
    }

    /** «2 ноября», «18 ноября». */
    static String dayMonth(long t) {
        LocalDate d = Instant.ofEpochMilli(t).atZone(ZONE).toLocalDate();
        return d.getDayOfMonth() + " " + MONTHS[d.getMonthValue() - 1];
    }

    /** «сегодня 14:05», «вчера 09:12», «16.11 14:05». */
    static String dayTime(long t) {
        LocalDate day = Instant.ofEpochMilli(t).atZone(ZONE).toLocalDate(), today = LocalDate.now(ZONE);
        String head = day.equals(today) ? "сегодня" : day.equals(today.minusDays(1)) ? "вчера" : DM.format(Instant.ofEpochMilli(t));
        return head + " " + HM.format(Instant.ofEpochMilli(t));
    }

    static String role(String role) {
        return switch (role) {
            case "operator" -> "оператор";
            case "moderator" -> "модератор";
            default -> "игрок";
        };
    }

    /** Глагол записи журнала (макет 12): «заглушил», «открыл инвентарь». */
    static String verb(String type) {
        return switch (type) {
            case "warn" -> "предупредил";
            case "mute" -> "заглушил";
            case "unmute" -> "снял заглушение у";
            case "kick" -> "выгнал";
            case "ban" -> "забанил";
            case "unban" -> "снял бан у";
            case "note" -> "оставил заметку о";
            case "invsee" -> "открыл инвентарь";
            case "invsee_edit" -> "открыл и мог менять инвентарь";
            case "enderchest" -> "открыл сундук Края";
            case "spectate" -> "наблюдал за";
            case "spectate_start" -> "начал наблюдать за";
            case "freeze" -> "заморозил";
            case "unfreeze" -> "разморозил";
            case "tp" -> "переместился к";
            case "tphere" -> "позвал к себе";
            case "move" -> "переставил";
            case "fly" -> "переключил полёт";
            case "god" -> "переключил бессмертие";
            case "heal" -> "вылечил";
            case "feed" -> "накормил";
            case "speed" -> "поменял скорость";
            case "extinguish" -> "потушил";
            case "moderator_add" -> "назначил модератором";
            case "moderator_remove" -> "снял с модераторов";
            case "perm" -> "изменил право";
            case "caps" -> "изменил сроки модератора";
            case "settings" -> "изменил настройки:";
            case "report" -> "пожаловался на";
            case "report_take" -> "взял жалобу на";
            case "report_close" -> "закрыл жалобу на";
            case "join_banned" -> "вошёл, прошлый бан закончился";
            default -> type;
        };
    }

    /** Значок записи журнала. */
    static works.repka.ryscore.ui.Icons.Shape icon(String type) {
        return HelmClient.shape(switch (type) {
            case "warn" -> "WARN";
            case "mute", "unmute" -> "MUTE";
            case "kick" -> "EXIT";
            case "ban", "unban" -> "BAN";
            case "note" -> "NOTE";
            case "invsee", "invsee_edit", "enderchest" -> "CHEST";
            case "spectate", "spectate_start" -> "EYE";
            case "freeze", "unfreeze" -> "SNOW";
            case "tp", "tphere" -> "TELEPORT";
            case "move" -> "MOVE";
            case "fly" -> "FEATHER";
            case "god" -> "SHIELD";
            case "heal" -> "HEART";
            case "feed" -> "FORK";
            case "speed" -> "SPEED";
            case "moderator_add", "moderator_remove", "perm" -> "KEY";
            case "caps", "settings" -> "GEAR";
            case "report", "report_take", "report_close" -> "FLAG";
            case "join_banned" -> "USER";
            default -> "INFO";
        });
    }

    /** «@minecraft:overworld 1 402 · 58 · −418» из журнала: измерение словами. */
    static String place(String s) {
        if (!s.startsWith("@")) return s;
        int sp = s.indexOf(' ');
        if (sp < 0) return dim(s.substring(1));
        return dim(s.substring(1, sp)) + " · " + s.substring(sp + 1);
    }
}
