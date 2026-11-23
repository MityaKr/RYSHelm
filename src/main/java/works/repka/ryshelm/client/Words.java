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
    private Words() {}

    /** «Верхний мир», «Нижний мир», «Край», иначе имя без пространства с заглавной. */
    static String dim(String id) {
        return switch (id) {
            case "minecraft:overworld" -> Tr.tr("dim.overworld");
            case "minecraft:the_nether" -> Tr.tr("dim.nether");
            case "minecraft:the_end" -> Tr.tr("dim.end");
            default -> {
                String p = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
                p = p.replace('_', ' ');
                yield p.isEmpty() ? id : Character.toUpperCase(p.charAt(0)) + p.substring(1);
            }
        };
    }

    /** «80 м», «1,4 км». */
    static String distance(int m) {
        if (m < 1000) return Tr.tr("dist.m", m);
        String km = String.format(Locale.ROOT, "%.1f", m / 1000.0);
        return Tr.tr("dist.km", Tr.lang().russian() ? km.replace('.', ',') : km);
    }

    /** Длительность словами: «42 мин», «2 ч», «3 дня». */
    static String span(long millis) {
        if (millis < Term.MINUTE) return Tr.tr("span.minute");
        if (millis < Term.HOUR) return Tr.tr("term.minutes", millis / Term.MINUTE);
        if (millis < Term.DAY) return Tr.tr("term.hours", millis / Term.HOUR);
        return Tr.n("term.days", millis / Term.DAY);
    }

    /** «в сети 42 мин». */
    static String online(long since) {
        return Tr.tr("span.online", span(Math.max(0, System.currentTimeMillis() - since)));
    }

    /** «3 дня назад», «сегодня в 14:05», «давно». */
    static String ago(long t) {
        if (t <= 0) return Tr.tr("span.long_ago");
        long d = System.currentTimeMillis() - t;
        if (d < Term.HOUR) return Tr.tr("span.ago", Tr.tr("term.minutes", Math.max(1, d / Term.MINUTE)));
        if (d < Term.DAY) return Tr.tr("span.ago", Tr.tr("term.hours", d / Term.HOUR));
        return Tr.tr("span.ago", Tr.n("term.days", d / Term.DAY));
    }

    /** Время строки списка: «14:05» сегодня, «вчера», иначе «16.11». */
    static String when(long t) {
        LocalDate day = Instant.ofEpochMilli(t).atZone(ZONE).toLocalDate(), today = LocalDate.now(ZONE);
        if (day.equals(today)) return HM.format(Instant.ofEpochMilli(t));
        if (day.equals(today.minusDays(1))) return Tr.tr("span.yesterday");
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
        return Tr.tr("month." + d.getMonthValue(), d.getDayOfMonth());
    }

    /** «сегодня 14:05», «вчера 09:12», «16.11 14:05». */
    static String dayTime(long t) {
        LocalDate day = Instant.ofEpochMilli(t).atZone(ZONE).toLocalDate(), today = LocalDate.now(ZONE);
        String head = day.equals(today) ? Tr.tr("span.today") : day.equals(today.minusDays(1)) ? Tr.tr("span.yesterday") : DM.format(Instant.ofEpochMilli(t));
        return head + " " + HM.format(Instant.ofEpochMilli(t));
    }

    static String role(String role) {
        return switch (role) {
            case "operator" -> Tr.tr("ui.role.operator");
            case "moderator" -> Tr.tr("ui.role.moderator");
            default -> Tr.tr("ui.role.player");
        };
    }

    /** Глагол записи журнала (макет 12): «заглушил», «открыл инвентарь». */
    static String verb(String type) {
        String key = "ryshelm.journal.verb." + type;
        return net.minecraft.locale.Language.getInstance().has(key) ? Tr.lang().t(key) : type;
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
            case "kit_create", "kit_delete", "kit_give" -> "CHEST";
            case "whitelist" -> "LIST";
            case "afk_kick" -> "CLOCK";
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
