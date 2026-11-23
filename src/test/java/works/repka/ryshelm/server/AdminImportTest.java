package works.repka.ryshelm.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import works.repka.ryscore.migrate.Migrations;
import works.repka.ryshelm.data.HelmData;
import works.repka.ryshelm.logic.Kind;
import works.repka.ryshelm.logic.Lang;
import works.repka.ryshelm.logic.Term;

/** Шаг admin без игры: копия файлов FTB Essentials сервера RepYS (18.11) и свои заготовки с мутами. */
class AdminImportTest {
    static final long NOW = 1_800_000_000_000L;
    static final UUID A = UUID.fromString("216ea99a-d6eb-45be-8a5b-3da7852588a5");

    @Test
    void repysFixtures() throws Exception {
        Path fixtures = Path.of(System.getProperty("ryshelm.fixtures", "missing")).resolve("world");
        assumeTrue(Files.isDirectory(fixtures.resolve("ftbessentials")), "нет копии файлов FTB");
        assertTrue(AdminImport.hasData(fixtures));
        AdminImport.Data src = AdminImport.read(fixtures);
        assertEquals(2, src.players.size());
        assertTrue(src.players.stream().allMatch(p -> p.lastSeen != null && !p.muted));
        HelmData dst = new HelmData();
        Migrations.Report r = Migrations.Report.create();
        AdminImport.apply(src, dst, id -> "", NOW, r);
        assertEquals(2L, r.counts().get("players"));
        assertEquals(2L, r.counts().get("last_seen"));
        assertEquals(0L, r.counts().get("muted"));
        HelmData.PlayerInfo info = dst.players.get(A);
        assertNotNull(info);
        assertEquals("minecraft:overworld", info.lastPos.dim);
        assertEquals(5371.5, info.lastPos.x, 0.01);
        assertEquals(23477.5, info.lastPos.z, 0.01);
        assertTrue(dst.punishments.isEmpty());
    }

    static void write(Path f, String text) throws Exception {
        Files.createDirectories(f.getParent());
        Files.writeString(f, text, StandardCharsets.UTF_8);
    }

    @Test
    void mutesWithTimeoutsAndRepeat(@TempDir Path world) throws Exception {
        UUID timed = UUID.randomUUID(), forever = UUID.randomUUID(), expired = UUID.randomUUID(), clean = UUID.randomUUID();
        Path d = world.resolve("ftbessentials");
        write(d.resolve("data.snbt"), "{\n\twarps: { }\n\tmute_timeouts: {\n\t\t\"" + timed + "\": " + (NOW + Term.HOUR) + "L\n\t\t\"" + expired + "\": "
            + (NOW - Term.HOUR) + "L\n\t}\n\tkits: { starter: { } }\n}\n");
        write(d.resolve("playerdata/" + timed + ".snbt"), "{\n\tmuted: true\n\tnick: \"Boss\"\n\tlast_seen: { dim: \"minecraft:the_nether\", x: 10, y: 70, z: -5 }\n}\n");
        write(d.resolve("playerdata/" + forever + ".snbt"), "{\n\tmuted: true\n\tfly: true\n}\n");
        write(d.resolve("playerdata/" + expired + ".snbt"), "{\n\tmuted: true\n}\n");
        write(d.resolve("playerdata/" + clean + ".snbt"), "{\n\tmuted: false\n\trecording: \"none\"\n}\n");
        write(d.resolve("playerdata/not-a-uuid.snbt"), "{ }");

        AdminImport.Data src = AdminImport.read(world);
        assertEquals(4, src.players.size());
        assertEquals(1, src.kits);
        HelmData dst = new HelmData();
        Migrations.Report r = Migrations.Report.create();
        AdminImport.apply(src, dst, id -> id.equals(timed) ? "Grib_ok" : "", NOW, r, Lang.ru());
        assertEquals(2L, r.counts().get("muted"));
        HelmData.Punishment t = dst.active(timed, Kind.MUTE, NOW);
        assertNotNull(t);
        assertEquals(NOW + Term.HOUR, t.expires);
        assertEquals("Grib_ok", t.targetName);
        assertEquals(AdminImport.FROM, t.byName);
        HelmData.Punishment f = dst.active(forever, Kind.MUTE, NOW);
        assertNotNull(f);
        assertTrue(f.permanent());
        assertFalse(dst.active(expired, Kind.MUTE, NOW) != null);
        assertEquals("minecraft:the_nether", dst.players.get(timed).lastPos.dim);
        assertTrue(r.skipped().stream().anyMatch(s -> s.contains("nick")));
        assertTrue(r.skipped().stream().anyMatch(s -> s.contains("kits")));
        assertTrue(r.skipped().stream().anyMatch(s -> s.contains("срок истёк")));

        // повтор: ничего нового, снятое модератором не возвращается
        t.revoked = NOW + 1;
        Migrations.Report again = Migrations.Report.create();
        AdminImport.apply(AdminImport.read(world), dst, id -> "", NOW + 2, again);
        assertEquals(0L, again.counts().get("muted"));
        assertEquals(0L, again.counts().get("last_seen"));
        assertEquals(2, dst.punishments.size());
    }

    @Test
    void noData(@TempDir Path world) {
        assertFalse(AdminImport.hasData(world));
        assertFalse(AdminImport.hasData(null));
    }
}
