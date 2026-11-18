package works.repka.ryshelm.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Клиентская часть RYSHelm: сброс состояния при выходе с сервера. */
public final class ClientEntry {
    private ClientEntry() {}

    public static void init(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> HelmClient.reset());
    }
}
