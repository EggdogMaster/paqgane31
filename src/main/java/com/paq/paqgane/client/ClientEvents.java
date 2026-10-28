package com.paq.paqgane.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.paq.paqgane.ModEntities;
import com.paq.paqgane.PaqNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

public class ClientEvents {
    public static final KeyMapping SUMMON_KEY = new KeyMapping("key.paqgane.summon", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, 80, "key.categories.paqgane");

    @Mod.EventBusSubscriber(modid = "paqgane", value = Dist.CLIENT)
    public static class ForgeBus {
        @SubscribeEvent
        public static void tick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                while (SUMMON_KEY.consumeClick()) {
                    PaqNetwork.CHANNEL.sendToServer(new PaqNetwork.SummonPacket());
                }
            }
        }
    }

    @Mod.EventBusSubscriber(modid = "paqgane", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ModBus {
        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent event) {
            event.register(SUMMON_KEY);
        }

        @SubscribeEvent
        public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(ModEntities.PAQGANE.get(), PaqganeRenderer::new);
        }
    }
}
