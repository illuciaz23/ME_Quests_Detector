package io.z23illucia.ae2_ftbquest_detector.network;

import io.z23illucia.ae2_ftbquest_detector.Ae2_ftbquest_detector;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 玩家离线后清理其上报的 Jade 偏好，避免服务端长期保留过期记录。 */
@Mod.EventBusSubscriber(modid = Ae2_ftbquest_detector.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DetectorServerPreferenceLifecycle {
    private DetectorServerPreferenceLifecycle() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        DetectorClientPreferencesPayload.clear(event.getEntity().getUUID());
    }
}
