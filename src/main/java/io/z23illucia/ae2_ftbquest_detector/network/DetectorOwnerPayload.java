package io.z23illucia.ae2_ftbquest_detector.network;

import com.mojang.logging.LogUtils;
import io.z23illucia.ae2_ftbquest_detector.Config;
import io.z23illucia.ae2_ftbquest_detector.utility.TeamDisplayNameResolver;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import org.slf4j.Logger;

import java.util.UUID;

/** 把检测器所属队伍的显示名同步给客户端。 */
public record DetectorOwnerPayload(UUID teamId, String rawTeamName) {
    private static final int MAX_TEAM_NAME_LENGTH = 256;
    private static final Logger LOGGER = LogUtils.getLogger();

    public static void encode(DetectorOwnerPayload payload, FriendlyByteBuf buffer) {
        buffer.writeUUID(payload.teamId);
        buffer.writeUtf(payload.rawTeamName == null ? "" : payload.rawTeamName, MAX_TEAM_NAME_LENGTH);
    }

    public static DetectorOwnerPayload decode(FriendlyByteBuf buffer) {
        UUID teamId = buffer.readUUID();
        String rawTeamName = buffer.readUtf(MAX_TEAM_NAME_LENGTH);
        return new DetectorOwnerPayload(teamId, rawTeamName.isEmpty() ? null : rawTeamName);
    }

    public static void handle(DetectorOwnerPayload payload, java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
        try {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> io.z23illucia.ae2_ftbquest_detector.client.DetectorClientNetworkHandler.handleOwnerPayload(payload));
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to process detector owner payload", exception);
        }
    }

    public static String resolveDisplayName(DetectorOwnerPayload payload) {
        return TeamDisplayNameResolver.formatDisplayName(
                payload.rawTeamName(), payload.teamId(), Config.getTeamNameDisplayMode());
    }
}
