package io.z23illucia.ae2_ftbquest_detector.client;

import com.mojang.logging.LogUtils;
import io.z23illucia.ae2_ftbquest_detector.network.DetectorOwnerPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/** 仅在客户端加载：把服务端同步来的队伍名显示成动作栏消息。 */
public final class DetectorClientNetworkHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    private DetectorClientNetworkHandler() {
    }

    public static void handleOwnerPayload(DetectorOwnerPayload payload) {
        try {
            String teamName = DetectorOwnerPayload.resolveDisplayName(payload);
            if (teamName != null && Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(
                        Component.translatable("ae2-ftbquests-detector.detector.owner_is", teamName), true);
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to display detector owner information", exception);
        }
    }
}
