package works.repka.ryshelm.server;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;
import works.repka.ryscore.migrate.Migrations;
import works.repka.ryshelm.RYSHelm;
import works.repka.ryshelm.data.HelmData;
import works.repka.ryshelm.logic.Lang;

/**
 * Шаг переезда {@code admin} (порядок 60, сервер, план 6): муты и место «где видели» из {@code world/ftbessentials}. Идёт
 * сам по флагу сборки {@code config/ryscore/migrate.json} или командой {@code /rys migrate run admin}; копию FTB, отметку и
 * отчёт {@code [rys-migrate]} делает ядро. Пока FTB Essentials загружен, шаг не нужен (решение автора 18.11): муты ведёт он.
 *
 * <p>Ядро зовёт шаги в {@code ServerStartedEvent} с самым низким приоритетом, после запуска RYSHelm: шаг пишет в живые
 * данные и сразу на диск. Без запущенного RYSHelm (проверка без игры) пишет файл {@code world/ryshelm/helm.json} сам.
 */
public final class AdminMigration implements Migrations.Step {
    public static final String ID = "admin";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public Migrations.Side side() {
        return Migrations.Side.SERVER;
    }

    @Override
    public String modId() {
        return RYSHelm.MODID;
    }

    @Override
    public java.util.List<String> backup() {
        return java.util.List.of("ftbessentials");
    }

    @Override
    public boolean needed(Migrations.Context c) {
        return !c.isLoaded("ftbessentials") && AdminImport.hasData(c.worldDir());
    }

    @Override
    public Migrations.Report run(Migrations.Context c) throws Exception {
        Path world = Objects.requireNonNull(c.worldDir(), "no world folder");
        Migrations.Report report = Migrations.Report.create();
        if (c.isLoaded("ftbessentials")) report.line(Lang.en().t("ryshelm.migrate.ftb_loaded"));
        AdminImport.Data src = AdminImport.read(world);
        MinecraftServer server = c.server();
        Function<UUID, String> names = id -> {
            if (server == null || server.getProfileCache() == null) return "";
            return server.getProfileCache().get(id).map(com.mojang.authlib.GameProfile::getName).orElse("");
        };
        long now = System.currentTimeMillis();
        if (Helm.running() && server != null && Helm.server() == server) {
            AdminImport.apply(src, Helm.data(), names, now, report);
            Helm.dirty();
            Helm.flush();
            Sync.staffChanged();
        } else {
            HelmData data = Helm.readData(world);
            AdminImport.apply(src, data, names, now, report);
            Helm.writeData(world, data);
        }
        return report;
    }
}
