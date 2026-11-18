package works.repka.ryshelm.dev;

import java.util.UUID;
import works.repka.ryshelm.client.ServerScreen;
import works.repka.ryshelm.logic.Kind;

/** Помощники автотеста и сценарий этапа А3 (жалобы, правила при входе, глазами игрока). */
final class HelmAutoTestA3 {
    static final boolean ON = false;

    private HelmAutoTestA3() {}

    static void filter(String f) {
        ServerScreen.filter = f;
    }

    static void punish(ServerScreen s, Kind kind, UUID target, String name) {
        s.openPunish(kind, target, name);
    }

    static void closePunish(ServerScreen s) {
        s.closePunish();
    }

    static void build() {}
}
