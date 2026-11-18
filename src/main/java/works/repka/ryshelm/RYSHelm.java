package works.repka.ryshelm;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.repka.ryscore.perm.Perms;
import works.repka.ryscore.perm.Roles;
import works.repka.ryshelm.network.HelmNet;
import works.repka.ryshelm.server.Chat;
import works.repka.ryshelm.server.Commands;
import works.repka.ryshelm.server.Gate;
import works.repka.ryshelm.server.Helm;

/**
 * RYSHelm: окно «Сервер» для больших и публичных серверов (план Essentials, разделы 5 и 6). Роли и права через контракты
 * ядра {@code perm.Roles} и {@code perm.Perms}, наказания, жалобы, журнал персонала, правила при входе, команды оператора.
 */
@Mod(RYSHelm.MODID)
public final class RYSHelm {
    public static final String MODID = "ryshelm";
    public static final Logger LOG = LoggerFactory.getLogger(RYSHelm.class);

    public RYSHelm(IEventBus modBus, ModContainer container) {
        // узлы прав до запуска сервера: позже объявленный узел не попадёт в PermissionAPI
        HelmPerms.init();
        Roles.provide(Helm::roleOf);
        Perms.policy(Helm::decide);
        modBus.addListener(HelmNet::register);
        if (FMLEnvironment.dist.isClient()) works.repka.ryshelm.client.ClientEntry.init(modBus);

        var bus = NeoForge.EVENT_BUS;
        bus.addListener((ServerStartedEvent e) -> Helm.start(e.getServer()));
        bus.addListener((ServerStoppingEvent e) -> Helm.stop());
        bus.addListener((ServerTickEvent.Post e) -> Helm.tick());
        bus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p && Helm.running()) Helm.login(p);
        });
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p && Helm.running()) Helm.logout(p);
        });
        // первыми: заглушение и чат персонала гасят строку раньше каналов RYSTeams
        bus.addListener(EventPriority.HIGHEST, false, ServerChatEvent.class, Chat::onChat);
        bus.addListener(EventPriority.HIGHEST, false, CommandEvent.class, Chat::onCommand);
        bus.addListener((RegisterCommandsEvent e) -> Commands.register(e.getDispatcher()));

        // правила не приняты: строить, ломать, открывать, бить, телепортироваться нельзя (план 5.7)
        bus.addListener(EventPriority.HIGH, false, BlockEvent.BreakEvent.class, e -> Gate.deny(e.getPlayer(), e));
        bus.addListener(EventPriority.HIGH, false, BlockEvent.EntityPlaceEvent.class, e -> Gate.deny(e.getEntity(), e));
        bus.addListener(EventPriority.HIGH, false, PlayerInteractEvent.RightClickBlock.class, e -> Gate.deny(e.getEntity(), e));
        bus.addListener(EventPriority.HIGH, false, PlayerInteractEvent.RightClickItem.class, e -> Gate.deny(e.getEntity(), e));
        bus.addListener(EventPriority.HIGH, false, PlayerInteractEvent.EntityInteract.class, e -> Gate.deny(e.getEntity(), e));
        bus.addListener(EventPriority.HIGH, false, PlayerInteractEvent.EntityInteractSpecific.class, e -> Gate.deny(e.getEntity(), e));
        bus.addListener(EventPriority.HIGH, false, PlayerInteractEvent.LeftClickBlock.class, e -> Gate.deny(e.getEntity(), e));
        bus.addListener(EventPriority.HIGH, false, AttackEntityEvent.class, e -> Gate.deny(e.getEntity(), e));
        bus.addListener(EventPriority.HIGH, false, EntityTeleportEvent.TeleportCommand.class, e -> Gate.deny(e.getEntity(), e));
        bus.addListener(EventPriority.HIGH, false, EntityTeleportEvent.EnderPearl.class, e -> Gate.deny(e.getEntity(), e));
        bus.addListener(EventPriority.HIGH, false, EntityTeleportEvent.ChorusFruit.class, e -> Gate.deny(e.getEntity(), e));
        LOG.info("RYSHelm загружен");
    }
}
