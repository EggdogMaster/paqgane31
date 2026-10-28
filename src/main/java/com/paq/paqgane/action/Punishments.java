package com.paq.paqgane.action;

import java.util.List;
import java.util.Random;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;

public class Punishments {
    private static final Random RNG = new Random();
    public static final List<String> KINDS = List.of("throw", "levitate", "nausea", "slow", "glow", "blind");
    private static final List<String> LINES = List.of(
            "bro really did that. on foenem. get thrown",
            "the real paq said no. so i said no too",
            "js gonna help you reflect on that one",
            "ggs. that was not it",
            "caught you foenem. enjoy the trip",
            "rules exist bro. fly little guy",
            "i saw that. on foenem");

    public static String randomKind() {
        return KINDS.get(RNG.nextInt(KINDS.size()));
    }

    public static String randomLine() {
        return LINES.get(RNG.nextInt(LINES.size()));
    }

    public static boolean apply(ServerPlayer p, String kind) {
        switch (kind == null ? "" : kind.toLowerCase()) {
            case "throw": {
                double angle = RNG.nextDouble() * Math.PI * 2.0;
                p.setDeltaMovement(new Vec3(Math.cos(angle) * 1.3, 1.1, Math.sin(angle) * 1.3));
                p.hurtMarked = true;
                p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 160, 0));
                break;
            }
            case "levitate":
                p.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 60, 0));
                p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 200, 0));
                break;
            case "nausea":
                p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 160, 0));
                break;
            case "slow":
                p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 120, 2));
                break;
            case "glow":
                p.addEffect(new MobEffectInstance(MobEffects.GLOWING, 600, 0));
                break;
            case "blind":
                p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0));
                break;
            default:
                return false;
        }
        return true;
    }
}
