package works.repka.ryshelm.data;

import works.repka.ryshelm.logic.Caps;
import works.repka.ryshelm.logic.Ladder;
import works.repka.ryshelm.logic.Term;

/**
 * Настройки сервера RYSHelm, файл {@code world/serverconfig/ryshelm-server.json} (как правила карты). Правит оператор во
 * вкладке «Настройки», можно и руками при остановленном сервере. Умолчания по решениям автора 19.11: правила при входе и
 * новичок выключены, модератору заглушить до 1 дня и бан до 7 дней.
 */
public final class HelmSettings {
    public int v = 1;
    /** Имя сервера в шапке окна и в правилах; пустое: первая строка MOTD или имя мира. */
    public String serverName = "";
    /** Окно правил при первом входе и после их правки (вопрос 5: выключено). */
    public boolean rulesOnJoin = false;
    /** Новичок первые N часов: нельзя звать к себе кроме своей команды. 0 выключено. */
    public int newbieHours = 0;
    /** Приветствие при входе уведомлением семьи; пустое не показывается. */
    public String welcome = "";
    public String ladder = Ladder.DEFAULT;
    public int ladderDays = Ladder.DEFAULT_WINDOW_DAYS;
    public String moderatorMuteMax = Term.code(Caps.DEFAULT_MUTE);
    public String moderatorBanMax = Term.code(Caps.DEFAULT_BAN);
    /** Антиспам чата (план 9): по умолчанию выключен, серверам друзей не нужен. */
    public boolean antiSpam = false;
    public int spamMessages = 5;
    public int spamSeconds = 8;
    /** Три отказа за 2 минуты: заглушить на столько минут; 0 только отказывать. */
    public int spamMuteMinutes = 5;
    /** Стартовый набор при первом входе; пустой выключен. */
    public String starterKit = "";
    /** Сколько дней хранится журнал. */
    public int journalDays = 90;
    /** Пауза между жалобами одного игрока, секунды. */
    public int reportCooldown = 120;
    /** Сколько строк чата нарушителя приложить к жалобе. */
    public int reportChatLines = 20;

    public Ladder ladder() {
        return Ladder.parse(ladder, ladderDays);
    }

    public Caps caps() {
        return new Caps(Term.parse(moderatorMuteMax, Caps.DEFAULT_MUTE), Term.parse(moderatorBanMax, Caps.DEFAULT_BAN));
    }

    /** Значения в допустимых пределах после чтения файла. */
    public HelmSettings sane() {
        newbieHours = Math.max(0, Math.min(1000, newbieHours));
        ladderDays = Math.max(1, Math.min(3650, ladderDays));
        journalDays = Math.max(7, Math.min(3650, journalDays));
        reportCooldown = Math.max(0, Math.min(86400, reportCooldown));
        reportChatLines = Math.max(0, Math.min(100, reportChatLines));
        spamMessages = Math.max(2, Math.min(50, spamMessages));
        spamSeconds = Math.max(2, Math.min(120, spamSeconds));
        spamMuteMinutes = Math.max(0, Math.min(1440, spamMuteMinutes));
        if (welcome == null) welcome = "";
        if (starterKit == null) starterKit = "";
        if (ladder == null || ladder.isBlank()) ladder = Ladder.DEFAULT;
        if (moderatorMuteMax == null) moderatorMuteMax = Term.code(Caps.DEFAULT_MUTE);
        if (moderatorBanMax == null) moderatorBanMax = Term.code(Caps.DEFAULT_BAN);
        return this;
    }
}
