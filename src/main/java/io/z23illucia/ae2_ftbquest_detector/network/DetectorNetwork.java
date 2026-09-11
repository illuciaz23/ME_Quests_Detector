package io.z23illucia.ae2_ftbquest_detector.network;

import io.z23illucia.ae2_ftbquest_detector.Ae2_ftbquest_detector;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class DetectorNetwork {
    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(Ae2_ftbquest_detector.MODID, "main"))
            .networkProtocolVersion(() -> PROTOCOL_VERSION)
            .clientAcceptedVersions(PROTOCOL_VERSION::equals)
            .serverAcceptedVersions(PROTOCOL_VERSION::equals)
            .simpleChannel();

    private DetectorNetwork() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(DetectorOwnerPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(DetectorOwnerPayload::encode)
                .decoder(DetectorOwnerPayload::decode)
                .consumerMainThread(DetectorOwnerPayload::handle)
                .add();
        CHANNEL.messageBuilder(DetectorClientPreferencesPayload.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(DetectorClientPreferencesPayload::encode)
                .decoder(DetectorClientPreferencesPayload::decode)
                .consumerMainThread(DetectorClientPreferencesPayload::handle)
                .add();
    }

    public static void sendToPlayer(ServerPlayer player, Object message) {
        if (player != null) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
        }
    }

    public static void sendToServer(Object message) {
        CHANNEL.sendToServer(message);
    }
}
