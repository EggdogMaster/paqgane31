package com.paq.paqgane.sentient;

import com.google.gson.JsonObject;
import com.paq.paqgane.PaqganeConfig;
import com.paq.paqgane.PaqganeMod;
import com.paq.paqgane.action.ActionExecutor;
import com.paq.paqgane.monitor.Discord;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Who the sentient body likes and who it hates. Every real player has a score from -100 to 100.
 *
 * <p>The score moves in two ways. Code moves it for concrete things that happen (gifts, hitting it, killing it,
 * hanging around, hitting its allies). The AI moves it through the sentient_feel and sentient_declare actions, but
 * only in small, rate-limited, budgeted steps. The AI never decides who actually gets attacked: the thresholds and
 * the fighting are in code. The owner (whoever turned sentient mode on) is never auto-targeted and always counts as
 * somebody to defend.
 */
@Mod.EventBusSubscriber(modid = "paqgane")
public class Social {
    public static final double ALLY_AT = 40.0;
    public static final double FRIEND_AT = 15.0;
    public static final double DISLIKE_AT = -15.0;
    public static final double ENEMY_AT = -40.0;

    private static final String DATA_NAME = "paqgane_relations";
    private static final double AI_BUDGET_PER_HOUR = 40.0;
    private static final long HOUR_MS = 3_600_000L;
    private static final long FEEL_GAP_MS = 20_000L;
    private static final long DECLARE_GAP_MS = 600_000L;
    private static final double GIFT_BUDGET_PER_HOUR = 25.0;

    public enum Stance {
        ENEMY("enemy", -60.0), DISLIKE("dislike", -25.0), NEUTRAL("neutral", 0.0), FRIEND("friend", 25.0), ALLY("ally", 60.0);

        public final String label;
        public final double center;

        Stance(String label, double center) {
            this.label = label;
            this.center = center;
        }

        @Nullable
        public static Stance byName(@Nullable String raw) {
            if (raw == null) {
                return null;
            }
            String n = raw.toLowerCase(Locale.ROOT).trim();
            if (n.equals("enemy") || n.equals("hate") || n.equals("hated") || n.equals("foe")) {
                return ENEMY;
            }
            if (n.equals("dislike") || n.equals("disliked") || n.equals("wary")) {
                return DISLIKE;
            }
            if (n.equals("neutral") || n.equals("none") || n.equals("stranger")) {
                return NEUTRAL;
            }
            if (n.equals("friend") || n.equals("like") || n.equals("liked")) {
                return FRIEND;
            }
            if (n.equals("ally") || n.equals("partner") || n.equals("teammate")) {
                return ALLY;
            }
            return null;
        }
    }

    private static final class Budget {
        long windowStart;
        double pos;
        double neg;
        long lastFeel;
        long lastDeclare;
        double gift;
        long giftWindowStart;
    }

    private static final Map<UUID, Budget> BUDGETS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_CHAT_BONUS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_ALLY_HIT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_SHARE = new ConcurrentHashMap<>();
    private static final Deque<String> EVENTS = new ArrayDeque<>();
    private static int secs = 0;
    /** Free will switch (mirrors SentientData.freeWill). When true none of the safety rules below apply. */
    public static volatile boolean free = false;

    // ------------------------------------------------------------------ data

    public static Relations data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(Relations::load, Relations::new, DATA_NAME);
    }

    public static boolean enabled() {
        return PaqganeConfig.SENTIENT_SOCIAL.get();
    }

    public static double score(MinecraftServer server, UUID id) {
        Relations.Entry e = data(server).map.get(id);
        return e == null ? 0.0 : e.score;
    }

    public static Stance stanceOf(double score) {
        if (score >= ALLY_AT) {
            return Stance.ALLY;
        }
        if (score >= FRIEND_AT) {
            return Stance.FRIEND;
        }
        if (score <= ENEMY_AT) {
            return Stance.ENEMY;
        }
        if (score <= DISLIKE_AT) {
            return Stance.DISLIKE;
        }
        return Stance.NEUTRAL;
    }

    public static Stance stance(MinecraftServer server, UUID id) {
        return stanceOf(score(server, id));
    }

    /** The owner and anyone who is a friend or better: the body will not hit them and will not hit things next to them. */
    public static boolean isProtected(MinecraftServer server, ServerPlayer p) {
        return (!free && SentientManager.isOwner(p)) || score(server, p.getUUID()) >= FRIEND_AT;
    }

    public static String label(MinecraftServer server, ServerPlayer p) {
        double sc = score(server, p.getUUID());
        return stanceOf(sc).label + " (" + (sc >= 0 ? "+" : "") + Math.round(sc) + ")";
    }

    public static synchronized void recordEvent(String text) {
        EVENTS.addLast(text);
        while (EVENTS.size() > 6) {
            EVENTS.pollFirst();
        }
    }

    // ------------------------------------------------------------------ changing scores

    /** Changes a score directly. Used by code, not budgeted. */
    public static void adjust(MinecraftServer server, UUID id, String name, double delta, String why) {
        if (!enabled() || delta == 0.0) {
            return;
        }
        Relations r = data(server);
        Relations.Entry e = r.map.computeIfAbsent(id, k -> new Relations.Entry());
        if (name != null && !name.isBlank()) {
            e.name = name;
        }
        Stance before = stanceOf(e.score);
        e.score = Math.max(-100.0, Math.min(100.0, e.score + delta));
        r.setDirty();
        Stance after = stanceOf(e.score);
        if (before != after) {
            recordEvent(e.name + " is now your " + after.label + " (" + why + ")");
            if (after == Stance.ENEMY || after == Stance.ALLY || before == Stance.ENEMY || before == Stance.ALLY) {
                Discord.send("**sentient social** Paqgane now sees " + e.name + " as: " + after.label + " (was " + before.label
                        + "). reason: " + why);
            }
        }
    }

    /** One AI-requested change. Clamped, rate limited, and limited to a budget per player per hour. Returns what was applied. */
    public static synchronized double aiChange(MinecraftServer server, UUID id, String name, double wanted, boolean declare, String reason) {
        long now = System.currentTimeMillis();
        Budget b = BUDGETS.computeIfAbsent(id, k -> new Budget());
        if (now - b.windowStart > HOUR_MS) {
            b.windowStart = now;
            b.pos = 0.0;
            b.neg = 0.0;
        }
        long last = declare ? b.lastDeclare : b.lastFeel;
        if (now - last < (declare ? DECLARE_GAP_MS : FEEL_GAP_MS)) {
            return 0.0;
        }
        double cap = declare ? 25.0 : 10.0;
        double d = Math.max(-cap, Math.min(cap, wanted));
        if (d > 0.0) {
            d = Math.min(d, AI_BUDGET_PER_HOUR - b.pos);
            if (d <= 0.0) {
                return 0.0;
            }
            b.pos += d;
        } else if (d < 0.0) {
            d = -Math.min(-d, AI_BUDGET_PER_HOUR - b.neg);
            if (d >= 0.0) {
                return 0.0;
            }
            b.neg += -d;
        } else {
            return 0.0;
        }
        if (declare) {
            b.lastDeclare = now;
        } else {
            b.lastFeel = now;
        }
        adjust(server, id, name, d, "ai: " + (reason == null || reason.isBlank() ? "no reason" : reason));
        return d;
    }

    @Nullable
    private static UUID resolve(MinecraftServer server, @Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        ServerPlayer online = server.getPlayerList().getPlayerByName(name.trim());
        if (online != null) {
            return SentientManager.isBot(online) ? null : online.getUUID();
        }
        for (Map.Entry<UUID, Relations.Entry> e : data(server).map.entrySet()) {
            if (e.getValue().name.equalsIgnoreCase(name.trim())) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * Runs sentient_feel / sentient_declare. When a player's chat started it, only the feelings about that
     * same player can change unless the speaker is the owner. From the body's own thinking loop speaker is null.
     */
    public static void applyAction(MinecraftServer server, @Nullable ServerPlayer speaker, String type, JsonObject a) {
        if (!enabled() || !SentientManager.isEnabled(server)) {
            return;
        }
        try {
            UUID id;
            String name;
            String asked = ActionExecutor.str(a, "player");
            if (speaker != null && !SentientManager.isOwner(speaker)) {
                id = speaker.getUUID();
                name = speaker.getGameProfile().getName();
            } else if (asked != null && !asked.isBlank()) {
                id = resolve(server, asked);
                name = asked.trim();
            } else if (speaker != null) {
                id = speaker.getUUID();
                name = speaker.getGameProfile().getName();
            } else {
                return;
            }
            if (id == null) {
                return;
            }
            Relations.Entry existing = data(server).map.get(id);
            if (existing != null) {
                name = existing.name;
            }
            String reason = ActionExecutor.str(a, "reason");
            if (reason != null && reason.length() > 80) {
                reason = reason.substring(0, 80);
            }
            double now = score(server, id);
            if (type.equalsIgnoreCase("sentient_declare")) {
                Stance st = Stance.byName(ActionExecutor.str(a, "stance"));
                if (st == null) {
                    return;
                }
                aiChange(server, id, name, st.center - now, true, reason);
            } else {
                double change = 0.0;
                try {
                    if (a.has("change") && !a.get("change").isJsonNull()) {
                        change = a.get("change").getAsDouble();
                    }
                } catch (Exception ignored) {
                    return;
                }
                aiChange(server, id, name, change, false, reason);
            }
        } catch (Exception e) {
            PaqganeMod.LOGGER.warn("sentient social action failed", e);
        }
    }

    // ------------------------------------------------------------------ concrete events

    public static void onSpokeTo(ServerPlayer p) {
        MinecraftServer server = p.getServer();
        if (!enabled() || server == null || !SentientManager.isActive(server) || SentientManager.isBot(p)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_CHAT_BONUS.get(p.getUUID());
        if (last != null && now - last < 60_000L) {
            return;
        }
        LAST_CHAT_BONUS.put(p.getUUID(), now);
        if (score(server, p.getUUID()) < 20.0) {
            adjust(server, p.getUUID(), p.getGameProfile().getName(), 0.5, "talked to it");
        }
    }

    public static void onGrief(ServerPlayer p) {
        MinecraftServer server = p.getServer();
        if (!enabled() || server == null || !SentientManager.isActive(server) || SentientManager.isBot(p)) {
            return;
        }
        adjust(server, p.getUUID(), p.getGameProfile().getName(), -3.0, "flagged for possible griefing");
        recordEvent(p.getGameProfile().getName() + " was flagged for possible griefing");
    }

    /** The body took damage from a player. Returns true if it should fight back. */
    public static boolean onHurtByPlayer(SentientEntity bot, ServerPlayer p, float amount) {
        MinecraftServer server = bot.getServer();
        if (server == null) {
            return false;
        }
        boolean owner = SentientManager.isOwner(p);
        double before = score(server, p.getUUID());
        double delta = -Math.min(8.0, 1.0 + amount * 0.8);
        adjust(server, p.getUUID(), p.getGameProfile().getName(), delta, "hit it");
        recordEvent(p.getGameProfile().getName() + " hit you for " + Math.round(amount));
        if (owner && !free) {
            return false;
        }
        if (!enabled() || free) {
            return true;
        }
        if (before >= FRIEND_AT) {
            // a friend hitting it by accident gets a pass until it adds up
            return score(server, p.getUUID()) <= DISLIKE_AT;
        }
        return true;
    }

    public static void onBotKilled(SentientEntity bot, DamageSource source) {
        MinecraftServer server = bot.getServer();
        if (server == null || !enabled()) {
            return;
        }
        if (source.getEntity() instanceof ServerPlayer p && !SentientManager.isBot(p)) {
            double d = SentientManager.isOwner(p) ? -15.0 : -60.0;
            adjust(server, p.getUUID(), p.getGameProfile().getName(), d, "killed it");
            recordEvent(p.getGameProfile().getName() + " killed you");
        }
    }

    /** A player hands the body an item (thrown at it or given by right click). */
    public static void onGift(SentientEntity bot, ServerPlayer giver, ItemStack stack) {
        MinecraftServer server = bot.getServer();
        if (server == null || !enabled() || SentientManager.isBot(giver) || stack.isEmpty()) {
            return;
        }
        double value = giftValue(stack);
        Budget b = BUDGETS.computeIfAbsent(giver.getUUID(), k -> new Budget());
        long now = System.currentTimeMillis();
        synchronized (b) {
            if (now - b.giftWindowStart > HOUR_MS) {
                b.giftWindowStart = now;
                b.gift = 0.0;
            }
            value = Math.min(value, Math.max(0.0, GIFT_BUDGET_PER_HOUR - b.gift));
            b.gift += value;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        String what = stack.getCount() + "x " + (id == null ? "item" : id.getPath());
        if (value > 0.0) {
            adjust(server, giver.getUUID(), giver.getGameProfile().getName(), value, "gift");
        }
        recordEvent(giver.getGameProfile().getName() + " gave you " + what);
    }

    static double giftValue(ItemStack s) {
        Item it = s.getItem();
        double base;
        if (it instanceof ArmorItem || it instanceof TieredItem || it instanceof TridentItem) {
            base = 3.0;
        } else if (s.isEdible()) {
            base = 1.5;
        } else if (s.is(Tags.Items.INGOTS) || s.is(Tags.Items.GEMS) || s.is(Tags.Items.ORES)) {
            base = 2.0;
        } else {
            base = 0.3;
        }
        double extra = 0.0;
        switch (s.getRarity()) {
            case UNCOMMON -> extra += 1.5;
            case RARE -> extra += 3.0;
            case EPIC -> extra += 5.0;
            default -> {
            }
        }
        if (s.isEnchanted()) {
            extra += 2.0;
        }
        double count = 1.0 + Math.min(s.getCount() - 1, 16) * 0.15;
        return Math.min(10.0, base * count + extra);
    }

    @SubscribeEvent
    public static void onPickup(PlayerEvent.ItemPickupEvent event) {
        try {
            if (!(event.getEntity() instanceof SentientEntity bot) || !enabled()) {
                return;
            }
            ItemEntity ie = event.getOriginalEntity();
            if (ie == null) {
                return;
            }
            CompoundTag t = ie.saveWithoutId(new CompoundTag());
            if (!t.hasUUID("Thrower")) {
                return;
            }
            UUID thrower = t.getUUID("Thrower");
            if (thrower.equals(SentientEntity.BOT_ID) || bot.getServer() == null) {
                return;
            }
            ServerPlayer giver = bot.getServer().getPlayerList().getPlayer(thrower);
            if (giver != null) {
                onGift(bot, giver, event.getStack());
            }
        } catch (Exception e) {
            PaqganeMod.LOGGER.warn("gift detection failed", e);
        }
    }

    /** Somebody hits the owner or an ally: the attacker loses standing (never more than once every few seconds). */
    @SubscribeEvent
    public static void onPlayerHurt(LivingHurtEvent event) {
        if (!enabled() || !(event.getEntity() instanceof ServerPlayer victim) || SentientManager.isBot(victim)) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof ServerPlayer att) || SentientManager.isBot(att) || att == victim) {
            return;
        }
        MinecraftServer server = victim.getServer();
        if (server == null || !SentientManager.isActive(server)) {
            return;
        }
        boolean victimProtected = SentientManager.isOwner(victim) || score(server, victim.getUUID()) >= ALLY_AT;
        if (!victimProtected || SentientManager.isOwner(att) || score(server, att.getUUID()) >= FRIEND_AT) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_ALLY_HIT.get(att.getUUID());
        if (last != null && now - last < 6000L) {
            return;
        }
        LAST_ALLY_HIT.put(att.getUUID(), now);
        adjust(server, att.getUUID(), att.getGameProfile().getName(), -3.0, "attacked " + victim.getGameProfile().getName());
    }

    // ------------------------------------------------------------------ behaviour helpers used by the body

    private static boolean nearSpawn(ServerLevel sl, ServerPlayer p) {
        if (free) {
            return false;
        }
        int r = PaqganeConfig.SENTIENT_SPAWN_SAFE_RADIUS.get();
        if (r <= 0 || sl.dimension() != Level.OVERWORLD) {
            return false;
        }
        return p.blockPosition().distSqr(sl.getSharedSpawnPos()) < (double) r * r;
    }

    private static boolean okToDefendAgainst(MinecraftServer server, LivingEntity a) {
        if (a instanceof ServerPlayer sp) {
            return !SentientManager.isBot(sp) && !sp.isCreative() && !sp.isSpectator() && !isProtected(server, sp)
                    && (free || PaqganeConfig.SENTIENT_HUNT_PLAYERS.get());
        }
        return !(a instanceof Player);
    }

    /** Something the body should go and fight because of how it feels about players. Defending allies comes before hunting enemies. */
    @Nullable
    public static LivingEntity priorityTarget(SentientEntity bot, ServerLevel sl) {
        if (!enabled() && !free) {
            return null;
        }
        MinecraftServer server = sl.getServer();
        ServerPlayer enemy = null;
        double enemyD = Double.MAX_VALUE;
        LivingEntity defend = null;
        double defendD = Double.MAX_VALUE;
        for (ServerPlayer p : SentientManager.realPlayers(server)) {
            if (p.level() != sl || !p.isAlive() || p.isSpectator()) {
                continue;
            }
            double d2 = bot.distanceToSqr(p);
            boolean owner = SentientManager.isOwner(p) && !free;
            double sc = score(server, p.getUUID());
            if (!owner && sc <= ENEMY_AT && (free || PaqganeConfig.SENTIENT_HUNT_PLAYERS.get()) && !p.isCreative()
                    && d2 < 40.0 * 40.0 && !nearSpawn(sl, p)) {
                if (d2 < enemyD) {
                    enemyD = d2;
                    enemy = p;
                }
            }
            if ((owner || sc >= ALLY_AT) && d2 < 32.0 * 32.0) {
                LivingEntity a = p.getLastHurtByMob();
                if (a != null && a != bot && a.isAlive() && a.level() == sl
                        && p.tickCount - p.getLastHurtByMobTimestamp() < 100 && okToDefendAgainst(server, a)) {
                    double d = bot.distanceToSqr(a);
                    if (d < 32.0 * 32.0 && d < defendD) {
                        defendD = d;
                        defend = a;
                    }
                }
            }
        }
        if (defend != null) {
            return defend;
        }
        return enemy;
    }

    /** True if a player the body protects is close to the point (so a big ability there could hit them). */
    public static boolean protectedNear(SentientEntity bot, LivingEntity at, double radius) {
        MinecraftServer server = bot.getServer();
        if (server == null || free) {
            return false;
        }
        for (ServerPlayer p : SentientManager.realPlayers(server)) {
            if (p != at && p.level() == at.level() && p.distanceToSqr(at) < radius * radius && isProtected(server, p)) {
                return true;
            }
        }
        return false;
    }

    /** The ally or friend it should hang around with when it has nothing else to do. */
    @Nullable
    public static ServerPlayer buddy(SentientEntity bot) {
        MinecraftServer server = bot.getServer();
        if (!enabled() || server == null) {
            return null;
        }
        ServerPlayer best = null;
        double bd = Double.MAX_VALUE;
        for (ServerPlayer p : SentientManager.realPlayers(server)) {
            if (p.level() != bot.level() || !p.isAlive() || p.isSpectator()) {
                continue;
            }
            double sc = score(server, p.getUUID());
            double max = sc >= ALLY_AT ? 48.0 : (sc >= FRIEND_AT ? 20.0 : 0.0);
            double d2 = bot.distanceToSqr(p);
            if (max <= 0.0 || d2 > max * max) {
                continue;
            }
            if (d2 < bd) {
                bd = d2;
                best = p;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ once a second

    public static void serverTick(MinecraftServer server, SentientEntity bot) {
        if (!enabled()) {
            return;
        }
        secs++;
        if (secs % 60 == 0) {
            decay(server);
        }
        if (secs % 30 == 0) {
            proximity(server, bot);
        }
        if (secs % 3 == 0) {
            share(server, bot);
        }
    }

    private static void decay(MinecraftServer server) {
        double step = PaqganeConfig.SENTIENT_SOCIAL_DECAY_PER_HOUR.get() / 60.0;
        if (step <= 0.0) {
            return;
        }
        Relations r = data(server);
        boolean changed = false;
        Iterator<Map.Entry<UUID, Relations.Entry>> it = r.map.entrySet().iterator();
        while (it.hasNext()) {
            Relations.Entry e = it.next().getValue();
            if (e.score > 0.0) {
                e.score = Math.max(0.0, e.score - step);
                changed = true;
            } else if (e.score < 0.0) {
                e.score = Math.min(0.0, e.score + step);
                changed = true;
            }
            if (Math.abs(e.score) < 0.01) {
                it.remove();
            }
        }
        if (changed) {
            r.setDirty();
        }
    }

    private static void proximity(MinecraftServer server, SentientEntity bot) {
        if (bot.currentTarget() != null) {
            return;
        }
        for (ServerPlayer p : SentientManager.realPlayers(server)) {
            if (p.level() != bot.level() || p.isSpectator() || bot.distanceToSqr(p) > 16.0 * 16.0) {
                continue;
            }
            double sc = score(server, p.getUUID());
            if (sc > DISLIKE_AT && sc < 25.0) {
                adjust(server, p.getUUID(), p.getGameProfile().getName(), 0.4, "hung out");
            }
        }
    }

    private static void share(MinecraftServer server, SentientEntity bot) {
        long now = System.currentTimeMillis();
        for (ServerPlayer p : SentientManager.realPlayers(server)) {
            if (p.level() != bot.level() || !p.isAlive() || bot.distanceToSqr(p) > 6.0 * 6.0) {
                continue;
            }
            boolean owner = SentientManager.isOwner(p);
            if ((!owner && score(server, p.getUUID()) < ALLY_AT) || p.getFoodData().getFoodLevel() > 8) {
                continue;
            }
            Long last = LAST_SHARE.get(p.getUUID());
            if (last != null && now - last < 90_000L) {
                continue;
            }
            for (ItemStack s : bot.getInventory().items) {
                if (!s.isEmpty() && s.isEdible() && s.getCount() >= 3) {
                    Item food = s.getItem();
                    if (bot.giveTo(p, food, 2) > 0) {
                        LAST_SHARE.put(p.getUUID(), now);
                        ResourceLocation id = ForgeRegistries.ITEMS.getKey(food);
                        recordEvent("you gave " + p.getGameProfile().getName() + " some " + (id == null ? "food" : id.getPath()));
                    }
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ text for the AI

    /** The lines about relationships that go into the body's observation. */
    public static String observe(MinecraftServer server) {
        StringBuilder sb = new StringBuilder();
        List<String> online = new ArrayList<>();
        Map<UUID, Boolean> seen = new HashMap<>();
        for (ServerPlayer p : SentientManager.realPlayers(server)) {
            seen.put(p.getUUID(), true);
            online.add(p.getGameProfile().getName() + ": " + label(server, p) + (SentientManager.isOwner(p) ? " [your owner]" : ""));
        }
        sb.append("- how you feel about players online: ").append(online.isEmpty() ? "nobody" : String.join(", ", online)).append("\n");
        List<String> others = new ArrayList<>();
        for (Map.Entry<UUID, Relations.Entry> e : data(server).map.entrySet()) {
            if (seen.containsKey(e.getKey()) || Math.abs(e.getValue().score) < FRIEND_AT) {
                continue;
            }
            others.add(e.getValue().name + " (" + stanceOf(e.getValue().score).label + ", offline)");
            if (others.size() >= 5) {
                break;
            }
        }
        if (!others.isEmpty()) {
            sb.append("- players you remember who are offline: ").append(String.join(", ", others)).append("\n");
        }
        synchronized (Social.class) {
            if (!EVENTS.isEmpty()) {
                sb.append("- recent things that happened between you and players:\n");
                for (String s : EVENTS) {
                    sb.append("  ").append(s).append("\n");
                }
            }
        }
        return sb.toString();
    }
}
