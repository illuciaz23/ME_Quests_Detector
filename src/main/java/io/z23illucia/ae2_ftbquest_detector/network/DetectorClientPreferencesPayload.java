package io.z23illucia.ae2_ftbquest_detector.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 客户端把自己的 Jade 偏好告诉服务端，服务端据此跳过无用的统计计算。
 */
public record DetectorClientPreferencesPayload(boolean showTaskProgress) {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, Boolean> CLIENT_PREFERENCES = new ConcurrentHashMap<>();

    public static void encode(DetectorClientPreferencesPayload payload, FriendlyByteBuf buffer) {
        buffer.writeBoolean(payload.showTaskProgress());
    }

    public static DetectorClientPreferencesPayload decode(FriendlyByteBuf buffer) {
        return new DetectorClientPreferencesPayload(buffer.readBoolean());
    }

    public static void handle(DetectorClientPreferencesPayload payload,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        Player sender = context.getSender();
        if (sender != null) {
            CLIENT_PREFERENCES.put(sender.getUUID(), payload.showTaskProgress());
        }
        context.setPacketHandled(true);
    }

    /** 未上报偏好的玩家按“需要统计”处理。 */
    public static boolean shouldComputeTaskProgress(Player player) {
        if (player == null) {
            return true;
        }
        Boolean preference = CLIENT_PREFERENCES.get(player.getUUID());
        return preference == null || preference;
    }

    public static void clear(UUID playerId) {
        if (playerId != null) {
            CLIENT_PREFERENCES.remove(playerId);
        }
    }

    public static void clearAll() {
        CLIENT_PREFERENCES.clear();
    }

    public static void logIgnored(RuntimeException exception) {
        LOGGER.debug("Ignored detector client preference update", exception);
    }
}
