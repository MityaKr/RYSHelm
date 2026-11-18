package works.repka.ryshelm.dev;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import works.repka.ryscore.migrate.Migrations;
import works.repka.ryshelm.RYSHelm;
import works.repka.ryshelm.client.HelmClient;
import works.repka.ryshelm.server.AdminMigration;
import works.repka.ryshelm.server.Commands;
import works.repka.ryshelm.server.Helm;

/**
 * Шаг {@code admin} в игре (этап А4): копия {@code world/ftbessentials} сервера RepYS кладётся в новый мир, шаг идёт как
 * {@code /rys migrate run admin}, потом ещё раз. {@code gradlew runAutotest -Pautotest=migrate}.
 */
@EventBusSubscriber(modid = RYSHelm.MODID, value = Dist.CLIENT)
public final class MigrateAutoTest {
    private static final boolean ON = "migrate".equals(System.getProperty("ryshelm.autotest"));
    private static final UUID PLAYER = UUID.fromString("216ea99a-d6eb-45be-8a5b-3da7852588a5");
    private static int stage, wait, failures;

    private MigrateAutoTest() {}

    static Minecraft mc() {
        return Minecraft.getInstance();
    }

    static void check(String what, boolean ok) {
        if (!ok) failures++;
        RYSHelm.LOG.info("[autotest] {} {}", ok ? "OK  " : "FAIL", what);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post e) {
        if (!ON) return;
        if (wait > 0) {
            wait--;
            return;
        }
        switch (stage) {
            case 0 -> {
                mc().options.pauseOnLostFocus = false;
                mc().options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0);
                if (!(mc().screen instanceof TitleScreen)) mc().setScreen(new TitleScreen());
                wait = 30;
                stage++;
            }
            case 1 -> {
                String world = "ryshelm_migrate_" + Long.toHexString(System.currentTimeMillis());
                LevelSettings settings = new LevelSettings(world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
                mc().createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(42L, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc().screen);
                stage++;
            }
            case 2 -> {
                if (mc().player == null || !HelmClient.present) return;
                var server = mc().getSingleplayerServer();
                server.execute(() -> {
                    try {
                        check("FTB Essentials в разработке не загружен: корни команд наши", !Commands.ftbEssentials());
                        Path src = Path.of(System.getProperty("ryshelm.autotest.fixtures")).resolve("world").resolve("ftbessentials");
                        Path dst = server.getWorldPath(LevelResource.ROOT).normalize().resolve("ftbessentials");
                        copy(src, dst);
                        Migrations.Outcome first = Migrations.runManual(AdminMigration.ID, server);
                        check("шаг admin прошёл: " + first, first.ran());
                        var info = Helm.data().players.get(PLAYER);
                        check("место «где видели» перенесено", info != null && info.lastPos != null && "minecraft:overworld".equals(info.lastPos.dim));
                        int punishments = Helm.data().punishments.size();
                        Migrations.Outcome second = Migrations.runManual(AdminMigration.ID, server);
                        check("повтор безопасен: " + second, Helm.data().punishments.size() == punishments);
                        check("копия FTB осталась на месте", Files.exists(dst.resolve("data.snbt")));
                    } catch (Throwable t) {
                        failures++;
                        RYSHelm.LOG.error("[autotest] шаг admin упал", t);
                    }
                    stage = 4;
                });
                stage = 3;
            }
            case 4 -> {
                RYSHelm.LOG.info(failures == 0 ? "[autotest] PASS" : "[autotest] FAILED: {}", failures);
                mc().stop();
                stage = 5;
            }
            default -> {}
        }
    }

    static void copy(Path src, Path dst) throws Exception {
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path to = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else Files.copy(p, to, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
