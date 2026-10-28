package com.paq.paqgane.monitor;

import com.paq.paqgane.PaqganeConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Looks at each player's recent block breaks and alerts admins (never punishes) when the pattern of
 * rare ore finds looks like x-ray. All numbers come from the config.
 */
@Mod.EventBusSubscriber(modid = "paqgane")
public class XrayDetector {

    private record Rec(long t, BlockPos pos, boolean rare, boolean diamond, boolean debris, boolean hidden) {
    }

    private static final Map<UUID, Deque<Rec>> RECS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_ALERT = new ConcurrentHashMap<>();

    public static void forget(UUID id) {
        RECS.remove(id);
        LAST_ALERT.remove(id);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!PaqganeConfig.XRAY_ENABLED.get() || !(event.getPlayer() instanceof ServerPlayer p) || !RuleMonitor.watched(p)) {
            return;
        }
        BlockState state = event.getState();
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (key == null) {
            return;
        }
        String id = key.toString();
        Set<String> rareSet = new HashSet<>();
        for (String s : PaqganeConfig.XRAY_ORES.get()) {
            rareSet.add(s.toLowerCase(Locale.ROOT));
        }
        boolean diamond = id.endsWith("diamond_ore");
        boolean debris = id.equals("minecraft:ancient_debris");
        boolean rare = rareSet.contains(id) || diamond || debris;

        long now = System.currentTimeMillis();
        BlockPos pos = event.getPos().immutable();
        int maxBlocks = PaqganeConfig.XRAY_WINDOW_BLOCKS.get();
        long maxAge = PaqganeConfig.XRAY_WINDOW_MINUTES.get() * 60_000L;

        Deque<Rec> dq = RECS.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>());
        String alert = null;
        synchronized (dq) {
            while (dq.size() >= maxBlocks) {
                dq.pollFirst();
            }
            while (!dq.isEmpty() && now - dq.peekFirst().t() > maxAge) {
                dq.pollFirst();
            }
            boolean hidden = rare && isHiddenHit(event.getLevel(), pos, dq, now);
            dq.addLast(new Rec(now, pos, rare, diamond, debris, hidden));
            if (rare) {
                alert = evaluate(dq, now);
            }
        }

        if (alert != null) {
            Long last = LAST_ALERT.get(p.getUUID());
            long cooldown = PaqganeConfig.XRAY_COOLDOWN_MINUTES.get() * 60_000L;
            if (last == null || now - last >= cooldown) {
                LAST_ALERT.put(p.getUUID(), now);
                RuleMonitor.flag(p, "xray", "possible xray (not proven)", alert, RuleMonitor.Severity.REVIEW);
            }
        }
    }

    /**
     * A hidden hit: no cave, tunnel, lava or water was next to the ore before this player dug toward it,
     * and the player either broke it with no open side at all or turned their tunnel straight into it.
     */
    private static boolean isHiddenHit(LevelAccessor level, BlockPos pos, Deque<Rec> recs, long now) {
        Set<BlockPos> dug = new HashSet<>();
        for (Rec r : recs) {
            dug.add(r.pos());
        }
        int dugNeighbours = 0;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (dug.contains(n)) {
                dugNeighbours++;
                continue;
            }
            BlockState ns = level.getBlockState(n);
            if (ns.isAir() || !ns.canOcclude()) {
                return false; // it was open to a cave, tunnel or fluid before the player got there
            }
        }
        if (dugNeighbours == 0) {
            return true; // completely enclosed
        }
        if (dugNeighbours != 1) {
            return false;
        }

        // exactly one of the player's own tunnel blocks touches it: did the tunnel turn into the ore?
        List<Rec> last = new ArrayList<>(3);
        java.util.Iterator<Rec> it = recs.descendingIterator();
        while (it.hasNext() && last.size() < 2) {
            last.add(it.next());
        }
        if (last.size() < 2) {
            return false;
        }
        Rec r1 = last.get(0);
        Rec r2 = last.get(1);
        if (now - r1.t() > 15_000L || r1.pos().distManhattan(pos) != 1 || r2.pos().distManhattan(r1.pos()) != 1) {
            return false;
        }
        Vec3i d1 = pos.subtract(r1.pos());
        Vec3i d0 = r1.pos().subtract(r2.pos());
        return !d1.equals(d0);
    }

    private static String evaluate(Deque<Rec> dq, long now) {
        int diamonds = 0;
        int debris = 0;
        int hiddenHits = 0;
        int speedRare = 0;
        int speedHidden = 0;
        long speedWindow = PaqganeConfig.XRAY_SPEED_SECONDS.get() * 1000L;
        for (Rec r : dq) {
            if (r.diamond()) {
                diamonds++;
            }
            if (r.debris()) {
                debris++;
            }
            if (r.hidden()) {
                hiddenHits++;
            }
            if (r.rare() && now - r.t() <= speedWindow) {
                speedRare++;
                if (r.hidden()) {
                    speedHidden++;
                }
            }
        }
        List<String> why = new ArrayList<>();
        if (diamonds >= PaqganeConfig.XRAY_DIAMOND_ALERT.get()) {
            why.add(diamonds + " diamond ore");
        }
        if (debris >= PaqganeConfig.XRAY_DEBRIS_ALERT.get()) {
            why.add(debris + " ancient debris");
        }
        if (hiddenHits >= PaqganeConfig.XRAY_HIDDEN_ALERT.get()) {
            why.add(hiddenHits + " hidden ore hits (no cave or tunnel was next to them)");
        }
        if (speedRare >= PaqganeConfig.XRAY_SPEED_ORES.get() && speedHidden >= PaqganeConfig.XRAY_SPEED_HIDDEN.get()) {
            why.add(speedRare + " rare ores in " + PaqganeConfig.XRAY_SPEED_SECONDS.get() + "s, " + speedHidden + " of them hidden");
        }
        if (why.isEmpty()) {
            return null;
        }
        return String.join("; ", why) + " within their last " + dq.size() + " broken blocks";
    }
}
