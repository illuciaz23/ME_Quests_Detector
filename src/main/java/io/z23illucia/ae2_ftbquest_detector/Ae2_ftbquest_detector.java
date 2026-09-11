package io.z23illucia.ae2_ftbquest_detector;

import appeng.api.ids.AECreativeTabIds;
import io.z23illucia.ae2_ftbquest_detector.blockentity.DetectorTeamSyncHandler;
import io.z23illucia.ae2_ftbquest_detector.network.DetectorNetwork;
import io.z23illucia.ae2_ftbquest_detector.registry.ModBlockEntities;
import io.z23illucia.ae2_ftbquest_detector.registry.ModBlocks;
import io.z23illucia.ae2_ftbquest_detector.registry.ModItems;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(Ae2_ftbquest_detector.MODID)
@SuppressWarnings("null")
public class Ae2_ftbquest_detector {

    public static final String MODID = "ae2_ftbquest_detector";

    @SuppressWarnings("removal")
    public Ae2_ftbquest_detector() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlockEntities.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(Config::onLoad);

        DetectorNetwork.register();
        DetectorTeamSyncHandler.register();

        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC,
                "ae2_ftbquest_detector-client.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, Config.SERVER_SPEC,
                "ae2_ftbquest_detector-server.toml");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == AECreativeTabIds.MAIN) {
            event.accept(ModItems.DETECTOR_BLOCK_ITEM.get());
        }
    }
}
