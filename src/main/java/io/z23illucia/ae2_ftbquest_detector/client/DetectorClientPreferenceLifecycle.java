package io.z23illucia.ae2_ftbquest_detector.client;

import io.z23illucia.ae2_ftbquest_detector.Ae2_ftbquest_detector;
import io.z23illucia.ae2_ftbquest_detector.Config;
import io.z23illucia.ae2_ftbquest_detector.network.DetectorClientPreferencesPayload;
import io.z23illucia.ae2_ftbquest_detector.network.DetectorNetwork;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 仅在客户端加载：登录时把自己的 Jade 偏好同步给服务端。 */
@Mod.EventBusSubscriber(modid = Ae2_ftbquest_detector.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DetectorClientPreferenceLifecycle {
    private DetectorClientPreferenceLifecycle() {
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        DetectorNetwork.sendToServer(new DetectorClientPreferencesPayload(
                Config.CLIENT_JADE_SHOW_TASK_PROGRESS.get()));
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        DetectorClientPreferencesPayload.clearAll();
    }
}
