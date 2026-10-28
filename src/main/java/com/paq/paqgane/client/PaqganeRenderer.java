package com.paq.paqgane.client;

import com.paq.paqgane.PaqganeEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class PaqganeRenderer extends GeoEntityRenderer<PaqganeEntity> {
    public PaqganeRenderer(EntityRendererProvider.Context context) {
        super(context, new PaqganeModel());
        this.shadowRadius = 0.2F;
    }
}
