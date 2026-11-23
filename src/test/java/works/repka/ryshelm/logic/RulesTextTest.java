package works.repka.ryshelm.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RulesTextTest {
    @Test
    void defaultRules() {
        RulesText r = RulesText.parse(RulesText.defaults(true));
        assertEquals("Правила сервера", r.title());
        assertEquals(6, r.rules().size());
        assertEquals("Не ломать и не брать чужое", r.rule(1).title());
        assertTrue(r.rule(1).detail().startsWith("Чужая земля закрыта приватом"));
        assertEquals("п. 3 «Не оскорблять игроков»", r.ref(Lang.ru(), 3));
        assertNull(r.rule(7));
        assertEquals("", r.ref(Lang.ru(), 7));
    }

    @Test
    void englishDefaultRules() {
        RulesText ru = RulesText.parse(RulesText.defaults(true)), en = RulesText.parse(RulesText.defaults(false));
        assertEquals("Server rules", en.title());
        assertEquals(ru.rules().size(), en.rules().size());
        for (int i = 0; i < en.rules().size(); i++) {
            assertEquals(ru.rules().get(i).number(), en.rules().get(i).number());
            assertTrue(!en.rules().get(i).detail().isEmpty());
            assertTrue(en.rules().get(i).title().chars().noneMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC));
        }
        assertEquals("No cheats or X-ray", en.rule(2).title());
    }

    @Test
    void versionIgnoresLineEndingsAndIndent() {
        String unix = "1. A\n   detail\n2. B\n";
        String win = "1. A\r\n detail  \r\n\r\n2. B\r\n";
        assertEquals(RulesText.version(unix), RulesText.version(win));
        assertNotEquals(RulesText.version(unix), RulesText.version("1. A\n   detail changed\n2. B\n"));
    }

    @Test
    void parenthesesAndBullets() {
        RulesText r = RulesText.parse("## Свод\n3) Третий\n- первая строка\n* вторая\n10. Десятый");
        assertEquals("Свод", r.title());
        assertEquals(2, r.rules().size());
        assertEquals(3, r.rules().get(0).number());
        assertEquals("первая строка вторая", r.rules().get(0).detail());
        assertEquals(10, r.rules().get(1).number());
    }

    @Test
    void empty() {
        assertTrue(RulesText.parse("# только заголовок\nтекст без пунктов").isEmpty());
    }
}
