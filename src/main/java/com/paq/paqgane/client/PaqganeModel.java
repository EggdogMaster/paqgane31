package com.paq.paqgane.client;

import com.paq.paqgane.PaqganeEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class PaqganeModel extends GeoModel<PaqganeEntity> {
    @Override
    public ResourceLocation getModelResource(PaqganeEntity animatable) {
        return new ResourceLocation("paqgane", "geo/paqgane.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(PaqganeEntity animatable) {
        return new ResourceLocation("paqgane", "textures/entity/paqgane.png");
    }

    @Override
    public ResourceLocation getAnimationResource(PaqganeEntity animatable) {
        return new ResourceLocation("paqgane", "animations/paqgane.animation.json");
    }
}
