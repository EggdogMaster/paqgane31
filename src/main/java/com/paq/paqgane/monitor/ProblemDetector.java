package com.paq.paqgane.monitor;

import com.google.gson.JsonArray;
import com.paq.paqgane.PaqganeConfig;
import com.paq.paqgane.PaqganeManager;
import com.paq.paqgane.PaqganeMod;
import com.paq.paqgane.sentient.SentientManager;
import com.paq.paqgane.ai.Brain;
import com.paq.paqgane.ai.GroqClient;
import com.paq.paqgane.ai.Prompts;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;

/** Notices when a player is stuck, in danger, dying a lot, starving or asking for help. */
@Mod.EventBusSubscriber(modid = "paqgane")
public class ProblemDetector {

    private static class PState {
        Vec3 lastPos;
        long lastMoveMs = System.currentTimeMillis();
        final Map<String, Long> lastTip = new HashMap<>();
        final Deque<Long> wallHits = new ArrayDeque<>();
        final Deque<String> deathCauses = new ArrayDeque<>();
        final Deque<Long> deathTimes = new ArrayDeque<>();
    }

    private static final Map<UUID, PState> STATE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_HELP = new ConcurrentHashMap<>();
    private static final Deque<Long> HELP_USES = new ArrayDeque<>();
    private static final Pattern HELP = Pattern.compile(
            "\\b(help|stuck|trapped|lost|cant (find|get|leave|move|break|log)|can't|how (do|to|can) i|how does|what do i do|idk (how|what)|im confused|i'm confused)\\b",
            Pattern.CASE_INSENSITIVE);

    public static void forget(UUID id) {
        STATE.remove(id);
        LAST_HELP.remove(id);
    }

    private static PState state(ServerPlayer p) {
        return STATE.computeIfAbsent(p.getUUID(), k -> new PState());
    }

    /**
     * Gives one tip. Per player and per kind there is a cooldown so Paqgane never nags.
     * serious problems also go to ops and Discord.
     */
    private static void problem(ServerPlayer p, String kind, String tip, String adminText, boolean serious, long cooldownMs) {
        if (!PaqganeConfig.PROBLEMS_ENABLED.get()) {
            return;
        }
        PState st = state(p);
        long now = System.currentTimeMillis();
        Long last = st.lastTip.get(kind);
        if (last != null && now - last < cooldownMs) {
            return;
        }
        st.lastTip.put(kind, now);

        if (PaqganeConfig.PROBLEMS_SPEAK.get() && tip != null) {
            PaqganeManager.whisper(p, tip);
        }
        if (serious) {
            String where = p.level().dimension().location() + " " + p.blockPosition().getX() + " "
                    + p.blockPosition().getY() + " " + p.blockPosition().getZ();
            RuleMonitor.notifyOps(p, "[Paqgane help] " + p.getGameProfile().getName() + ": " + adminText + " at " + where);
            if (PaqganeConfig.PROBLEMS_DISCORD.get()) {
                Discord.send("**player might need help**\nplayer: " + p.getGameProfile().getName()
                        + "\nproblem: " + adminText + "\nwhere: " + where);
            }
        }
        PaqganeMod.LOGGER.info("[Paqgane help] {} {}", p.getGameProfile().getName(), adminText);
    }

    @SubscribeEvent
    public static void tick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER) {
            return;
        }
        if (!(event.player instanceof ServerPlayer p) || !PaqganeConfig.PROBLEMS_ENABLED.get() || SentientManager.isBot(p)) {
            return;
        }
        if (p.tickCount % 20 != 0 || p.isCreative() || p.isSpectator() || !p.isAlive()) {
            return;
        }
        check(p);
    }

    private static void check(ServerPlayer p) {
        long now = System.currentTimeMillis();
        PState st = state(p);

        // stuck: has not moved and is boxed in by solid blocks
        Vec3 pos = p.position();
        if (st.lastPos == null || pos.distanceToSqr(st.lastPos) > 2.25 || p.isSleeping() || p.isPassenger()) {
            st.lastPos = pos;
            st.lastMoveMs = now;
        } else if (PaqganeConfig.PROBLEM_STUCK.get()
                && now - st.lastMoveMs >= PaqganeConfig.STUCK_SECONDS.get() * 1000L
                && enclosed(p)) {
            st.lastMoveMs = now;
            problem(p, "stuck", "bro are you stuck? dig up, not down. if you cant get out say \"paq help\" or ping the real paq",
                    "looks stuck, has not moved for " + PaqganeConfig.STUCK_SECONDS.get() + "s and is boxed in by solid blocks",
                    true, 600_000L);
        }

        if (PaqganeConfig.PROBLEM_DANGER.get()) {
            float hp = p.getHealth();
            boolean low = hp <= PaqganeConfig.DANGER_HEALTH.get();
            if (low && p.isInLava()) {
                problem(p, "lava", "bro ur in lava. get out now. on foenem", null, false, 20_000L);
            } else if (low && p.isOnFire() && !p.fireImmune()) {
                problem(p, "fire", "ur on fire and low. water or get out of the fire bro", null, false, 20_000L);
            }
            if (p.isUnderWater() && p.getAirSupply() <= 60 && !p.canBreatheUnderwater()) {
                problem(p, "air", "ur about to drown bro. swim up", null, false, 20_000L);
            }
            if (p.getY() < p.level().getMinBuildHeight() - 8) {
                problem(p, "void", "ur falling into the void bro. thats js gg", null, false, 15_000L);
            }
        }

        if (PaqganeConfig.PROBLEM_STARVING.get() && p.getFoodData().getFoodLevel() == 0 && !hasFood(p)) {
            problem(p, "starving", "ur starving and u got no food bro. go kill something or grab crops", null, false, 300_000L);
        }
    }

    private static boolean hasFood(ServerPlayer p) {
        for (ItemStack s : p.getInventory().items) {
            if (!s.isEmpty() && s.isEdible()) {
                return true;
            }
        }
        ItemStack off = p.getOffhandItem();
        return !off.isEmpty() && off.isEdible();
    }

    private static boolean enclosed(ServerPlayer p) {
        Level level = p.level();
        BlockPos feet = p.blockPosition();
        BlockPos head = feet.above();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!solid(level, feet.relative(d)) || !solid(level, head.relative(d))) {
                return false;
            }
        }
        return solid(level, head.above());
    }

    private static boolean solid(Level level, BlockPos pos) {
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    @SubscribeEvent
    public static void hurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !PaqganeConfig.PROBLEMS_ENABLED.get()
                || !PaqganeConfig.PROBLEM_SUFFOCATE.get() || SentientManager.isBot(p)) {
            return;
        }
        if (!event.getSource().is(DamageTypes.IN_WALL)) {
            return;
        }
        PState st = state(p);
        long now = System.currentTimeMillis();
        st.wallHits.addLast(now);
        while (!st.wallHits.isEmpty() && now - st.wallHits.peekFirst() > 15_000L) {
            st.wallHits.pollFirst();
        }
        if (st.wallHits.size() >= 3) {
            st.wallHits.clear();
            problem(p, "suffocate", "ur suffocating in a block bro. break out of it or say \"paq help\"",
                    "is suffocating inside blocks", true, 60_000L);
        }
    }

    @SubscribeEvent
    public static void death(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !PaqganeConfig.PROBLEMS_ENABLED.get()
                || !PaqganeConfig.PROBLEM_DEATHS.get() || SentientManager.isBot(p)) {
            return;
        }
        PState st = state(p);
        long now = System.currentTimeMillis();
        long window = PaqganeConfig.DEATHS_WINDOW_MINUTES.get() * 60_000L;
        st.deathTimes.addLast(now);
        st.deathCauses.addLast(event.getSource().getMsgId());
        while (!st.deathTimes.isEmpty() && now - st.deathTimes.peekFirst() > window) {
            st.deathTimes.pollFirst();
            st.deathCauses.pollFirst();
        }
        int n = st.deathTimes.size();
        if (n >= PaqganeConfig.DEATHS_COUNT.get()) {
            String causes = String.join(", ", st.deathCauses);
            String lastCause = st.deathCauses.peekLast();
            st.deathTimes.clear();
            st.deathCauses.clear();
            problem(p, "deaths", "thats " + n + " deaths in a few minutes bro. " + tipFor(lastCause),
                    "died " + n + " times in " + PaqganeConfig.DEATHS_WINDOW_MINUTES.get() + " minutes (" + causes + ")",
                    true, 120_000L);
        }
    }

    private static String tipFor(String cause) {
        String c = cause == null ? "" : cause.toLowerCase();
        if (c.contains("lava")) {
            return "stay off lava, carry a water bucket";
        }
        if (c.contains("fall")) {
            return "watch your falls, a water bucket clutch saves you";
        }
        if (c.contains("drown")) {
            return "watch your air bar when ur under water";
        }
        if (c.contains("fire") || c.contains("burn")) {
            return "water or a fire resistance potion fixes that";
        }
        if (c.contains("explosion")) {
            return "creepers hiss before they blow, back off";
        }
        if (c.contains("starve")) {
            return "eat something bro";
        }
        if (c.contains("outofworld") || c.contains("void")) {
            return "stay away from the void edge";
        }
        if (c.contains("mob") || c.contains("player") || c.contains("arrow")) {
            return "armor up and eat before you fight";
        }
        return "slow down and gear up";
    }

    /** Called for chat that was not addressed to Paqgane. */
    public static void onChat(ServerPlayer p, String text) {
        if (!PaqganeConfig.PROBLEMS_ENABLED.get() || !PaqganeConfig.PROBLEM_HELP_CHAT.get() || !GroqClient.isConfigured()) {
            return;
        }
        if (text.length() < 6 || text.length() > 200 || !HELP.matcher(text).find()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_HELP.get(p.getUUID());
        if (last != null && now - last < PaqganeConfig.HELP_COOLDOWN_SECONDS.get() * 1000L) {
            return;
        }
        synchronized (HELP_USES) {
            while (!HELP_USES.isEmpty() && now - HELP_USES.peekFirst() > 3_600_000L) {
                HELP_USES.pollFirst();
            }
            if (HELP_USES.size() >= PaqganeConfig.HELP_MAX_PER_HOUR.get()) {
                return;
            }
            HELP_USES.addLast(now);
        }
        LAST_HELP.put(p.getUUID(), now);

        MinecraftServer server = p.getServer();
        UUID id = p.getUUID();
        String name = p.getGameProfile().getName();
        JsonArray messages = GroqClient.single(Prompts.helpScan(name), name + ": " + text);
        GroqClient.chat(messages, GroqClient.Priority.LOW).whenComplete((reply, err) -> {
            if (err != null) {
                return; // stay quiet, this was a background check
            }
            Brain.Parsed parsed = Brain.parse(reply);
            String say = parsed.say();
            if (say == null || say.isBlank()) {
                return;
            }
            final String out = say.length() > 240 ? say.substring(0, 240) : say;
            server.execute(() -> {
                ServerPlayer again = server.getPlayerList().getPlayer(id);
                if (again != null) {
                    PaqganeManager.whisper(again, out);
                }
            });
        });
    }
}
