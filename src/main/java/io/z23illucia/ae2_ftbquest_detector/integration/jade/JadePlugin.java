package io.z23illucia.ae2_ftbquest_detector.integration.jade;

import dev.ftb.mods.ftbquests.events.ClearFileCacheEvent;
import io.z23illucia.ae2_ftbquest_detector.Ae2_ftbquest_detector;
import io.z23illucia.ae2_ftbquest_detector.block.DetectorBlock;
import io.z23illucia.ae2_ftbquest_detector.blockentity.DetectorBlockEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

@WailaPlugin(Ae2_ftbquest_detector.MODID)
public class JadePlugin implements IWailaPlugin {
    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(DetectorProvider.INSTANCE, DetectorBlockEntity.class);
        ClearFileCacheEvent.EVENT.register(ignored -> DetectorProvider.invalidateTaskStatsCache());
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(DetectorProvider.INSTANCE, DetectorBlock.class);
    }
}
