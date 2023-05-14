package org.teacon.gongdaobei;

import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

@Mod(GongdaobeiForge.ID)
public final class GongdaobeiForge {
    public static final String ID = "gongdaobei";
    private static final Logger LOGGER = LogUtils.getLogger();

    @Mod.EventBusSubscriber(modid = ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class EventListener {
        @SubscribeEvent
        public static void on(FMLCommonSetupEvent event) {
            LOGGER.info("HELLO FROM GONGDAOBEI FORGE SETUP");
        }
    }
}
