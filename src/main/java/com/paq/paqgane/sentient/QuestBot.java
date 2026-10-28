package com.paq.paqgane.sentient;

import com.paq.paqgane.PaqganeConfig;
import com.paq.paqgane.PaqganeMod;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Grinds FTB Quests for the body. It talks to FTB Quests only through reflection, so there is no compile dependency.
 * Only simple task types are attempted: item (collect), kill, location and checkmark. Everything else (advancement,
 * dimension, stat, xp, biome, structure, fluid, energy, observation, custom) is skipped and counted so status can say so.
 */
final class QuestBot {
    private static final String P = "dev.ftb.mods.ftbquests.quest.";
    private static boolean tried;
    private static boolean ok;
    private static Class<?> itemTaskC;
    private static Class<?> killTaskC;
    private static Class<?> locTaskC;
    private static Class<?> checkTaskC;
    private static final Map<Item, List<Block>> SOURCES = new HashMap<>();
    private static boolean sourcesBuilt;

    private enum Kind {
        CHECK, ITEM, KILL, LOCATION
    }

    private final SentientEntity bot;
    private Object curTask;
    private Kind curKind;
    private long curId;
    private String status = "idle";
    private int lastPlan = -100;
    private int nextClaim = 100;
    private boolean holding;
    private int exploreHops;
    private double exploreAngle = -1.0;
    private int fails;
    private double bestDist = Double.MAX_VALUE;
    private int bestDistTick;
    private final Map<Long, Integer> blacklist = new HashMap<>();
    private final Map<String, Integer> unsupported = new LinkedHashMap<>();
    private int doable;
    private int completedTasks;

    QuestBot(SentientEntity bot) {
        this.bot = bot;
    }

    static boolean loaded() {
        return ModList.get().isLoaded("ftbquests");
    }

    static synchronized boolean available() {
        if (!PaqganeConfig.SENTIENT_QUESTS.get() || !loaded()) {
            return false;
        }
        if (!tried) {
            tried = true;
            try {
                itemTaskC = Class.forName(P + "task.ItemTask");
                killTaskC = Class.forName(P + "task.KillTask");
                locTaskC = Class.forName(P + "task.LocationTask");
                checkTaskC = Class.forName(P + "task.CheckmarkTask");
                ok = Class.forName(P + "ServerQuestFile") != null;
            } catch (Throwable t) {
                PaqganeMod.LOGGER.warn("Paqgane: could not hook into FTB Quests", t);
            }
        }
        return ok && questFile() != null;
    }

    // ------------------------------------------------------------------ reflection helpers

    @Nullable
    private static Object questFile() {
        try {
            return Class.forName(P + "ServerQuestFile").getField("INSTANCE").get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean accepts(Class<?> t, Object a) {
        if (a == null) {
            return !t.isPrimitive();
        }
        if (t.isPrimitive()) {
            return (t == long.class && a instanceof Long) || (t == int.class && a instanceof Integer)
                    || (t == boolean.class && a instanceof Boolean) || (t == double.class && a instanceof Double);
        }
        return t.isInstance(a);
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) {
                continue;
            }
            Class<?>[] pt = m.getParameterTypes();
            boolean match = true;
            for (int i = 0; i < pt.length; i++) {
                if (!accepts(pt[i], args[i])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return m.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(name);
    }

    @Nullable
    private static Object fieldValue(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                // try the parent
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static boolean bool(Object o) {
        return o instanceof Boolean b && b;
    }

    private static long lng(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static String title(Object task) {
        try {
            Object t = call(task, "getTitle");
            return t instanceof Component c ? c.getString() : String.valueOf(t);
        } catch (Throwable e) {
            return task.getClass().getSimpleName();
        }
    }

    // ------------------------------------------------------------------ status

    String status() {
        StringBuilder sb = new StringBuilder();
        sb.append(this.bot.isQuestMode() ? "quest mode on" : "quest mode off");
        if (!loaded()) {
            return sb.append(", ftb quests is not installed").toString();
        }
        if (!available()) {
            return sb.append(", ftb quests is not ready (or disabled in the config)").toString();
        }
        sb.append(", doing: ").append(this.status);
        sb.append(", tasks done by me: ").append(this.completedTasks);
        sb.append(", doable tasks right now: ").append(this.doable);
        if (!this.unsupported.isEmpty()) {
            sb.append(", cannot do: ");
            int n = 0;
            for (Map.Entry<String, Integer> e : this.unsupported.entrySet()) {
                sb.append(n++ > 0 ? ", " : "").append(e.getValue()).append("x ").append(e.getKey());
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ driving

    /** Called when the body has nothing else to do. Returns true if quest work is keeping it busy. */
    boolean tick() {
        if (!this.bot.isQuestMode() || !available()) {
            return false;
        }
        int now = this.bot.age();
        if (this.bot.isWalking()) {
            return true;
        }
        if (now - this.lastPlan < 20) {
            return this.holding;
        }
        this.lastPlan = now;
        try {
            this.holding = this.plan(now);
        } catch (Throwable t) {
            PaqganeMod.LOGGER.warn("Paqgane: quest planning failed, blacklisting the current task", t);
            this.dropTask(now, 6000);
            this.holding = false;
        }
        return this.holding;
    }

    private void dropTask(int now, int forTicks) {
        if (this.curTask != null) {
            this.blacklist.put(this.curId, now + forTicks);
        }
        this.curTask = null;
        this.curKind = null;
        this.exploreHops = 0;
        this.exploreAngle = -1.0;
        this.fails = 0;
        this.bestDist = Double.MAX_VALUE;
    }

    private boolean plan(int now) throws Exception {
        Object file = questFile();
        Object data = call(file, "getOrCreateTeamData", this.bot);
        if (now >= this.nextClaim) {
            this.nextClaim = now + 200;
            this.claimRewards(file, data);
        }
        if (this.curTask != null) {
            if (bool(call(data, "isCompleted", this.curTask))) {
                this.completedTasks++;
                this.curTask = null;
                this.curKind = null;
                this.exploreHops = 0;
                this.fails = 0;
            }
        }
        if (this.curTask == null) {
            this.choose(file, data, now);
        }
        if (this.curTask == null) {
            this.status = "nothing doable right now";
            return false;
        }
        if (this.bot.consumeMoveFailed()) {
            this.fails++;
        }
        if (this.curKind == Kind.CHECK) {
            call(this.curTask, "submitTask", data, this.bot);
            this.status = "checkmark: " + title(this.curTask);
            return true;
        }
        if (this.curKind == Kind.ITEM) {
            return this.workItem(data, now);
        }
        if (this.curKind == Kind.KILL) {
            return this.workKill(data, now);
        }
        return this.workLocation(data, now);
    }

    private void choose(Object file, Object data, int now) throws Exception {
        this.unsupported.clear();
        this.blacklist.values().removeIf(t -> t < now);
        this.doable = 0;
        Object best = null;
        Kind bestKind = null;
        double bestScore = Double.MAX_VALUE;
        for (Object ch : (List<?>) call(file, "getAllChapters")) {
            for (Object q : (List<?>) call(ch, "getQuests")) {
                if (bool(call(data, "isCompleted", q)) || !bool(call(data, "canStartTasks", q))) {
                    continue;
                }
                for (Object t : (List<?>) call(q, "getTasksAsList")) {
                    if (bool(call(data, "isCompleted", t))) {
                        continue;
                    }
                    Kind k = this.classify(t);
                    if (k == null) {
                        this.unsupported.merge(t.getClass().getSimpleName().replace("Task", "").toLowerCase(), 1, Integer::sum);
                        continue;
                    }
                    long id = lng(call(t, "getId"));
                    if (this.blacklist.containsKey(id)) {
                        continue;
                    }
                    this.doable++;
                    double score = k.ordinal() * 1000.0;
                    if (k == Kind.LOCATION) {
                        score += this.locationDistance(t);
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        best = t;
                        bestKind = k;
                    }
                }
            }
        }
        if (best != null) {
            this.curTask = best;
            this.curKind = bestKind;
            this.curId = lng(call(best, "getId"));
            this.exploreHops = 0;
            this.fails = 0;
            this.bestDist = Double.MAX_VALUE;
            this.bestDistTick = now;
        }
    }

    @Nullable
    private Kind classify(Object t) {
        try {
            if (checkTaskC.isInstance(t)) {
                return Kind.CHECK;
            }
            if (itemTaskC.isInstance(t)) {
                if (bool(call(t, "isOnlyFromCrafting")) || bool(call(t, "isTaskScreenOnly"))) {
                    return null;
                }
                return Kind.ITEM;
            }
            if (killTaskC.isInstance(t)) {
                return Kind.KILL;
            }
            if (locTaskC.isInstance(t)) {
                return this.locationReachable(t) ? Kind.LOCATION : null;
            }
        } catch (Throwable ignored) {
            // treat as unsupported
        }
        return null;
    }

    // ------------------------------------------------------------------ item tasks

    private long countMatching(Object task) throws Exception {
        long n = 0;
        for (ItemStack s : this.bot.getInventory().items) {
            if (!s.isEmpty() && bool(call(task, "test", s))) {
                n += s.getCount();
            }
        }
        for (ItemStack s : this.bot.getInventory().offhand) {
            if (!s.isEmpty() && bool(call(task, "test", s))) {
                n += s.getCount();
            }
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private boolean workItem(Object data, int now) throws Exception {
        long max = lng(call(this.curTask, "getMaxProgress"));
        boolean consumes = bool(call(this.curTask, "consumesResources"));
        long target = consumes ? max - lng(call(data, "getProgress", this.curTask)) : max;
        long held = this.countMatching(this.curTask);
        this.status = "collecting for: " + title(this.curTask) + " (" + held + "/" + target + ")";
        if (held >= target) {
            call(this.curTask, "submitTask", data, this.bot);
            return true;
        }
        long missing = target - held;
        List<ItemStack> opts = new ArrayList<>((List<ItemStack>) call(this.curTask, "getValidDisplayItems"));
        if (opts.size() > 8) {
            opts = opts.subList(0, 8);
        }
        for (ItemStack o : opts) {
            if (!o.isEmpty() && this.bot.craftChain(o.getItem(), (int) Math.min(16, missing), 0) > 0) {
                return true;
            }
        }
        ensureSources();
        for (ItemStack o : opts) {
            List<Block> blocks = SOURCES.get(o.getItem());
            if (blocks == null) {
                continue;
            }
            for (Block b : blocks) {
                if (this.bot.queueMine(b, (int) Math.min(missing, 48)) > 0) {
                    return true;
                }
            }
        }
        return this.explore(now, "looking for a way to get " + title(this.curTask));
    }

    private void ensureSources() {
        if (sourcesBuilt || !(this.bot.level() instanceof ServerLevel sl)) {
            return;
        }
        sourcesBuilt = true;
        BlockPos at = this.bot.blockPosition();
        for (Block b : ForgeRegistries.BLOCKS) {
            try {
                BlockState st = b.defaultBlockState();
                if (!this.bot.canMine(st, at)) {
                    continue;
                }
                for (ItemStack drop : Block.getDrops(st, sl, at, null)) {
                    List<Block> l = SOURCES.computeIfAbsent(drop.getItem(), k -> new ArrayList<>());
                    if (!l.contains(b)) {
                        l.add(b);
                    }
                }
            } catch (Throwable ignored) {
                // odd loot table, skip this block
            }
        }
    }

    // ------------------------------------------------------------------ kill tasks

    private boolean workKill(Object data, int now) throws Exception {
        Object idObj = fieldValue(this.curTask, "entity");
        EntityType<?> type = idObj instanceof ResourceLocation rl ? ForgeRegistries.ENTITY_TYPES.getValue(rl) : null;
        if (type == null || type == EntityType.PLAYER || type == EntityType.WITHER || type == EntityType.ENDER_DRAGON
                || type == EntityType.WARDEN) {
            this.dropTask(now, 72000);
            return false;
        }
        long left = lng(call(this.curTask, "getMaxProgress")) - lng(call(data, "getProgress", this.curTask));
        this.status = "hunting " + left + "x " + type.getDescription().getString() + " for: " + title(this.curTask);
        if (this.bot.getHealth() < this.bot.getMaxHealth() * 0.6F) {
            this.status = "resting before the next fight";
            return true;
        }
        if (this.bot.currentTarget() != null) {
            return true;
        }
        ServerLevel sl = this.bot.serverLevel();
        LivingEntity nearest = null;
        double bd = Double.MAX_VALUE;
        for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, this.bot.getBoundingBox().inflate(40.0),
                x -> x.getType() == type && x.isAlive() && !(x instanceof Player))) {
            double d = this.bot.distanceToSqr(e);
            if (d < bd) {
                bd = d;
                nearest = e;
            }
        }
        if (nearest != null) {
            this.bot.setQuestTarget(nearest);
            return true;
        }
        return this.explore(now, "looking for " + type.getDescription().getString());
    }

    // ------------------------------------------------------------------ location tasks

    @Nullable
    private int[] box(Object t) {
        try {
            Object[] v = {fieldValue(t, "x"), fieldValue(t, "y"), fieldValue(t, "z"), fieldValue(t, "w"), fieldValue(t, "h"), fieldValue(t, "d")};
            int[] out = new int[6];
            for (int i = 0; i < 6; i++) {
                if (!(v[i] instanceof Integer n)) {
                    return null;
                }
                out[i] = n;
            }
            return out;
        } catch (Throwable e) {
            return null;
        }
    }

    private boolean sameDimension(Object t) {
        if (bool(fieldValue(t, "ignoreDimension"))) {
            return true;
        }
        Object dim = fieldValue(t, "dimension");
        return dim instanceof ResourceKey<?> k && k.equals(this.bot.level().dimension());
    }

    private double locationDistance(Object t) {
        int[] b = this.box(t);
        if (b == null) {
            return 1.0E9;
        }
        double dx = b[0] + b[3] / 2.0 - this.bot.getX();
        double dz = b[2] + b[5] / 2.0 - this.bot.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private boolean locationReachable(Object t) {
        return this.box(t) != null && this.sameDimension(t)
                && this.locationDistance(t) <= PaqganeConfig.SENTIENT_QUEST_MAX_TRAVEL.get();
    }

    private boolean workLocation(Object data, int now) throws Exception {
        int[] b = this.box(this.curTask);
        if (b == null || !this.sameDimension(this.curTask)) {
            this.dropTask(now, 72000);
            return false;
        }
        this.status = "walking to a location for: " + title(this.curTask);
        Vec3 p = this.bot.position();
        boolean inside = p.x >= b[0] && p.x <= b[0] + b[3] && p.y >= b[1] - 1 && p.y <= b[1] + b[4] + 1
                && p.z >= b[2] && p.z <= b[2] + b[5];
        if (inside) {
            call(this.curTask, "submitTask", data, this.bot);
            return true;
        }
        BlockPos goal = new BlockPos(b[0] + b[3] / 2, b[1], b[2] + b[5] / 2);
        double dist = Math.sqrt(this.bot.distanceToSqr(goal.getX() + 0.5, p.y, goal.getZ() + 0.5));
        if (dist < this.bestDist - 5.0) {
            this.bestDist = dist;
            this.bestDistTick = now;
        }
        if (now - this.bestDistTick > 900 || this.fails >= 5) {
            this.dropTask(now, 72000);
            return false;
        }
        if (dist < 4.0 && Math.abs(p.y - b[1]) > 4.0) {
            this.dropTask(now, 72000);
            return false;
        }
        this.travel(goal);
        return true;
    }

    // ------------------------------------------------------------------ moving around

    private void travel(BlockPos goal) {
        ServerLevel sl = this.bot.serverLevel();
        Vec3 p = this.bot.position();
        double dx = goal.getX() + 0.5 - p.x;
        double dz = goal.getZ() + 0.5 - p.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist <= 20.0) {
            this.bot.walkTo(goal, 1.0);
            return;
        }
        double k = 18.0 / dist;
        BlockPos hop = BlockPos.containing(p.x + dx * k, p.y, p.z + dz * k);
        if (!sl.hasChunkAt(hop)) {
            hop = BlockPos.containing(p.x + dx * k * 0.4, p.y, p.z + dz * k * 0.4);
        }
        BlockPos ground = sl.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, hop);
        this.bot.walkTo(ground, 1.5);
    }

    private boolean explore(int now, String why) {
        this.status = why;
        if (this.exploreHops >= 10 || this.fails >= 6) {
            this.dropTask(now, 6000);
            return false;
        }
        if (this.exploreAngle < 0.0 || this.fails > 0) {
            this.exploreAngle = this.bot.getRandom().nextDouble() * Math.PI * 2.0;
            this.fails = 0;
        }
        this.exploreHops++;
        Vec3 p = this.bot.position();
        BlockPos goal = BlockPos.containing(p.x + Math.cos(this.exploreAngle) * 30.0, p.y, p.z + Math.sin(this.exploreAngle) * 30.0);
        this.travel(goal);
        return true;
    }

    // ------------------------------------------------------------------ rewards

    private void claimRewards(Object file, Object data) {
        int claimed = 0;
        try {
            for (Object ch : (List<?>) call(file, "getAllChapters")) {
                for (Object q : (List<?>) call(ch, "getQuests")) {
                    if (claimed >= 5) {
                        return;
                    }
                    if (!bool(call(data, "isCompleted", q))) {
                        continue;
                    }
                    for (Object r : (Collection<?>) call(q, "getRewards")) {
                        String type = r.getClass().getSimpleName();
                        boolean skip = type.equals("ChoiceReward") || type.equals("CustomReward")
                                || (type.equals("CommandReward") && !PaqganeConfig.SENTIENT_QUEST_COMMAND_REWARDS.get());
                        if (skip || bool(call(data, "isRewardClaimed", this.bot.getUUID(), r))) {
                            continue;
                        }
                        call(data, "claimReward", this.bot, r, Boolean.FALSE);
                        claimed++;
                    }
                }
            }
        } catch (Throwable t) {
            PaqganeMod.LOGGER.warn("Paqgane: claiming a quest reward failed", t);
        }
    }
}
