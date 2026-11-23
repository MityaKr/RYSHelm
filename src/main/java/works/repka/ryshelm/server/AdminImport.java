package works.repka.ryshelm.server;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.migrate.Migrations;
import works.repka.ryscore.migrate.Snbt;
import works.repka.ryshelm.data.HelmData;
import works.repka.ryshelm.logic.Kind;
import works.repka.ryshelm.logic.Lang;

/**
 * Чтение и перенос данных FTB Essentials для шага {@code admin} (план 6) без игры: файлы читает {@code migrate.Snbt} ядра,
 * файлы FTB не меняются. Из {@code playerdata/<uuid>.snbt} берутся {@code last_seen} (место «где видели») и
 * {@code muted}; срок заглушения из {@code data.snbt → mute_timeouts} (uuid → конец в мс, проверено CFR по
 * {@code FTBEWorldData} 2101.1.10). Ники, наборы, запись, полёт и бессмертие не переносятся. Дома, история «назад» и места
 * сервера переносит шаг {@code places} RYSMap.
 */
public final class AdminImport {
    /** Подпись выдавшего у перенесённого заглушения: по ней повтор шага узнаёт своё. */
    public static final String FROM = "FTB Essentials";

    /** Игрок из файлов FTB. */
    public static final class Player {
        public UUID id;
        public @Nullable HelmData.Spot lastSeen;
        public boolean muted;
        /** Конец заглушения в мс; 0 без срока. */
        public long muteUntil;
        public boolean nick, fly, god, recording, kitUses;
    }

    public static final class Data {
        public final List<Player> players = new ArrayList<>();
        public int kits;
    }

    private AdminImport() {}

    public static Path dir(Path worldDir) {
        return worldDir.resolve("ftbessentials");
    }

    public static boolean hasData(@Nullable Path worldDir) {
        if (worldDir == null) return false;
        Path d = dir(worldDir);
        return Files.isRegularFile(d.resolve("data.snbt")) || Files.isDirectory(d.resolve("playerdata"));
    }

    public static Data read(Path worldDir) throws IOException {
        Path d = dir(worldDir);
        Data out = new Data();
        Map<UUID, Long> timeouts = new LinkedHashMap<>();
        Path dataFile = d.resolve("data.snbt");
        if (Files.isRegularFile(dataFile)) {
            JsonObject root = Snbt.readObject(dataFile);
            JsonObject mt = Snbt.object(root, "mute_timeouts");
            for (String key : mt.keySet()) {
                try {
                    timeouts.put(UUID.fromString(key), Snbt.longValue(mt, key, 0));
                } catch (IllegalArgumentException ignored) {
                    // чужой ключ не мешает остальным
                }
            }
            out.kits = Snbt.object(root, "kits").size();
        }
        Path pd = d.resolve("playerdata");
        if (Files.isDirectory(pd)) {
            List<Path> files = new ArrayList<>();
            try (Stream<Path> s = Files.list(pd)) {
                s.filter(f -> f.getFileName().toString().endsWith(".snbt")).sorted().forEach(files::add);
            }
            for (Path f : files) {
                String name = f.getFileName().toString();
                UUID id;
                try {
                    id = UUID.fromString(name.substring(0, name.length() - 5));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                JsonObject o = Snbt.readObject(f);
                Player p = new Player();
                p.id = id;
                if (o.has("last_seen")) p.lastSeen = spot(Snbt.object(o, "last_seen"));
                p.muted = Snbt.bool(o, "muted", false);
                p.muteUntil = timeouts.getOrDefault(id, 0L);
                p.nick = !Snbt.string(o, "nick", "").isEmpty();
                p.fly = Snbt.bool(o, "fly", false);
                p.god = Snbt.bool(o, "god", false);
                String rec = Snbt.string(o, "recording", "none");
                p.recording = !rec.isEmpty() && !rec.equals("none");
                p.kitUses = Snbt.object(o, "kit_use_times").size() > 0;
                out.players.add(p);
            }
        }
        return out;
    }

    static @Nullable HelmData.Spot spot(JsonObject o) {
        if (!o.has("dim")) return null;
        HelmData.Spot s = HelmData.Spot.of(Snbt.string(o, "dim", "minecraft:overworld"), Snbt.integer(o, "x", 0) + 0.5, Snbt.integer(o, "y", 64), Snbt.integer(o, "z", 0) + 0.5);
        if (o.has("yRot")) s.yRot = o.get("yRot").getAsFloat();
        if (o.has("xRot")) s.xRot = o.get("xRot").getAsFloat();
        return s;
    }

    /**
     * Переносит в данные RYSHelm только то, чего у них ещё нет: место «где видели», если своего нет; заглушение, если
     * FTB его держит, срок не истёк и этот перенос его ещё не делал. Повтор безопасен.
     * @param names ник по UUID (кэш профилей сервера) или null
     */
    public static void apply(Data src, HelmData dst, Function<UUID, String> names, long now, Migrations.Report report) {
        apply(src, dst, names, now, report, Lang.en());
    }

    /**
     * То же; строки отчёта на языке {@code report}. Шаг идёт при запуске сервера без игрока, отчёт ложится в лог и файл
     * ядра, а они с ядра 1.17.1 по-английски, поэтому шаг зовёт с английским. Подпись перенесённого заглушения (её видит
     * персонал в истории игрока) на языке системы сервера ({@link Lang#server()}).
     */
    public static void apply(Data src, HelmData dst, Function<UUID, String> names, long now, Migrations.Report report, Lang lang) {
        int seen = 0, mutes = 0;
        for (Player p : src.players) {
            String name = names.apply(p.id);
            HelmData.PlayerInfo info = dst.player(p.id, name);
            if (info.lastPos == null && p.lastSeen != null) {
                info.lastPos = p.lastSeen;
                seen++;
            }
            if (p.muted) {
                boolean done = dst.history(p.id).stream().anyMatch(x -> x.kind == Kind.MUTE && FROM.equals(x.byName));
                if (done) {
                    report.skipped("mute " + p.id, lang.t("ryshelm.migrate.mute.done"));
                } else if (dst.active(p.id, Kind.MUTE, now) != null) {
                    report.skipped("mute " + p.id, lang.t("ryshelm.migrate.mute.own"));
                } else if (p.muteUntil > 0 && p.muteUntil <= now) {
                    report.skipped("mute " + p.id, lang.t("ryshelm.migrate.mute.expired"));
                } else {
                    HelmData.Punishment m = new HelmData.Punishment();
                    m.id = dst.id();
                    m.kind = Kind.MUTE;
                    m.target = p.id;
                    m.targetName = info.name == null || info.name.isEmpty() ? p.id.toString().substring(0, 8) : info.name;
                    m.byName = FROM;
                    m.reason = Lang.server().t("ryshelm.migrate.mute.reason");
                    m.created = now;
                    m.expires = p.muteUntil;
                    dst.punishments.add(m);
                    mutes++;
                }
            }
            if (p.nick) report.skipped("nick " + p.id, lang.t("ryshelm.migrate.skip.nick"));
            if (p.fly) report.skipped("fly " + p.id, lang.t("ryshelm.migrate.skip.fly"));
            if (p.god) report.skipped("god " + p.id, lang.t("ryshelm.migrate.skip.god"));
            if (p.recording) report.skipped("recording " + p.id, lang.t("ryshelm.migrate.skip.recording"));
            if (p.kitUses) report.skipped("kit_use_times " + p.id, lang.t("ryshelm.migrate.skip.kit_uses"));
        }
        if (src.kits > 0) report.skipped("kits", lang.n("ryshelm.migrate.skip.kits", src.kits));
        report.count("players", src.players.size());
        report.count("last_seen", seen);
        report.count("muted", mutes);
    }
}
