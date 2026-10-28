package com.paq.paqgane.monitor;

import com.paq.paqgane.PaqganeConfig;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** Alert-only griefing signals: lots of TNT, lots of lava, or a burst of built blocks being broken. */
@Mod.EventBusSubscriber(modid = "paqgane")
public class GriefMonitor {
    private static final Map<UUID, Deque<Long>> TNT = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<Long>> LAVA = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<Long>> BURST = new ConcurrentHashMap<>();

    private static final List<String> BUILT = List.of(
            "planks", "brick", "glass", "wool", "concrete", "glazed_terracotta", "stairs", "slab", "fence",
            "door", "carpet", "lantern", "torch", "sign", "bed", "bookshelf", "chiseled", "smooth_", "cut_");

    public static void forget(UUID id) {
        TNT.remove(id);
        LAVA.remove(id);
        BURST.remove(id);
    }

    /** Adds a hit to the player's window and returns the number of hits inside it. */
    private static int hit(Map<UUID, Deque<Long>> map, UUID id, long windowMs) {
        long now = System.currentTimeMillis();
        Deque<Long> dq = map.computeIfAbsent(id, k -> new ArrayDeque<>());
        synchronized (dq) {
            dq.addLast(now);
            while (!dq.isEmpty() && now - dq.peekFirst() > windowMs) {
                dq.pollFirst();
            }
            return dq.size();
        }
    }

    @SubscribeEvent
    public static void tnt(EntityJoinLevelEvent event) {
        if (!PaqganeConfig.GRIEF_ENABLED.get() || event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof PrimedTnt tnt && tnt.getOwner() instanceof ServerPlayer p && RuleMonitor.watched(p)) {
            int n = hit(TNT, p.getUUID(), 60_000L);
            if (n >= PaqganeConfig.GRIEF_TNT.get()) {
                TNT.remove(p.getUUID());
                com.paq.paqgane.sentient.Social.onGrief(p);
                RuleMonitor.flag(p, "grief_tnt", "possible griefing (tnt)",
                        "lit " + n + " tnt in the last minute", RuleMonitor.Severity.REVIEW);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void lava(FillBucketEvent event) {
        if (!PaqganeConfig.GRIEF_ENABLED.get()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer p) || !RuleMonitor.watched(p)) {
            return;
        }
        if (!event.getEmptyBucket().is(Items.LAVA_BUCKET)) {
            return;
        }
        HitResult target = event.getTarget();
        if (target == null || target.getType() != HitResult.Type.BLOCK) {
            return;
        }
        int n = hit(LAVA, p.getUUID(), 60_000L);
        if (n >= PaqganeConfig.GRIEF_LAVA.get()) {
            LAVA.remove(p.getUUID());
            com.paq.paqgane.sentient.Social.onGrief(p);
            RuleMonitor.flag(p, "grief_lava", "possible griefing (lava)",
                    "emptied " + n + " lava buckets in the last minute", RuleMonitor.Severity.REVIEW);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void burst(BlockEvent.BreakEvent event) {
        if (!PaqganeConfig.GRIEF_ENABLED.get() || !(event.getPlayer() instanceof ServerPlayer p) || !RuleMonitor.watched(p)) {
            return;
        }
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(event.getState().getBlock());
        if (key == null) {
            return;
        }
        String path = key.getPath();
        boolean built = false;
        for (String s : BUILT) {
            if (path.contains(s)) {
                built = true;
                break;
            }
        }
        if (!built) {
            return;
        }
        int n = hit(BURST, p.getUUID(), PaqganeConfig.GRIEF_BURST_SECONDS.get() * 1000L);
        if (n >= PaqganeConfig.GRIEF_BURST_COUNT.get()) {
            BURST.remove(p.getUUID());
            com.paq.paqgane.sentient.Social.onGrief(p);
            RuleMonitor.flag(p, "grief_burst", "possible griefing (mass break)",
                    n + " built blocks (planks, bricks, glass etc, last one was " + path + ") broken in "
                            + PaqganeConfig.GRIEF_BURST_SECONDS.get() + "s. could also be their own build, or a bastion",
                    RuleMonitor.Severity.REVIEW);
        }
    }
}
