package works.repka.ryshelm.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import works.repka.ryshelm.RYSHelm;
import works.repka.ryshelm.server.Requests;

/**
 * Два канала, как у RYSTeams: сервер шлёт клиенту состояние и данные окна, клиент серверу действия. Тело NBT, поле
 * {@code type} говорит, что внутри. Права проверяет сервер. Канал необязательный: клиент без мода заходит на сервер с ним.
 */
public final class HelmNet {
    private HelmNet() {}

    public record ToClient(CompoundTag data) implements CustomPacketPayload {
        public static final Type<ToClient> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RYSHelm.MODID, "to_client"));
        public static final StreamCodec<FriendlyByteBuf, ToClient> CODEC = ByteBufCodecs.COMPOUND_TAG.map(ToClient::new, ToClient::data).cast();

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Action(String action, CompoundTag args) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RYSHelm.MODID, "action"));
        public static final StreamCodec<FriendlyByteBuf, Action> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(64), Action::action, ByteBufCodecs.COMPOUND_TAG, Action::args, Action::new).cast();

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        // версия меняется вместе с форматом пакетов
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToClient(ToClient.TYPE, ToClient.CODEC, HelmNet::onClient);
        registrar.playToServer(Action.TYPE, Action.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) Requests.handle(player, payload.action(), payload.args());
        });
    }

    private static void onClient(ToClient payload, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient()) Client.accept(payload.data());
    }

    /** Отдельный класс: на выделенном сервере клиентские классы не грузятся. */
    private static final class Client {
        static void accept(CompoundTag data) {
            works.repka.ryshelm.client.HelmClient.accept(data);
        }

        /** Сервер без RYSHelm канала не знает: туда ничего не шлём. */
        static boolean serverHas() {
            var c = net.minecraft.client.Minecraft.getInstance().getConnection();
            return c != null && c.hasChannel(Action.TYPE);
        }
    }

    public static void send(ServerPlayer player, CompoundTag data) {
        if (player.connection != null && player.connection.hasChannel(ToClient.TYPE)) PacketDistributor.sendToPlayer(player, new ToClient(data));
    }

    public static void action(String action, CompoundTag args) {
        if (Client.serverHas()) PacketDistributor.sendToServer(new Action(action, args));
    }
}
