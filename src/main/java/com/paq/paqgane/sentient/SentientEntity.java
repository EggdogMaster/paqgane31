package com.paq.paqgane.sentient;

import com.mojang.authlib.GameProfile;
import com.paq.paqgane.PaqganeConfig;
import com.paq.paqgane.PaqganeManager;
import com.paq.paqgane.ai.Brain;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;

/**
 * Paqgane as a real (server side) player. It is a ServerPlayer with a fake connection, so it shows up in the
 * player list, has a real inventory with armor and a held item, takes damage, eats, and is rendered by the
 * vanilla player renderer. All of its decisions are made here and by SentientBrain; nobody drives it by hand.
 *
 * Its Cursed Fate style moves are implemented by this mod and never call the Cursed Fate mod.
 */
public class SentientEntity extends ServerPlayer {

    public static final String NAME = "Paqgane";
    public static final UUID BOT_ID = UUIDUtil.createOfflinePlayerUUID(NAME);

    public enum Intent {
        WANDER, FOLLOW, STAY, GOTO, MINE
    }

    public enum Move {
        BLACK_FLASH("black_flash", 10, 80),
        HOLLOW_PURPLE("hollow_purple", 40, 300),
        REVERSE("reverse", 30, 400),
        DOMAIN("domain", 80, 2400);

        public final String id;
        public final int cost;
        public final int cooldownTicks;

        Move(String id, int cost, int cooldownTicks) {
            this.id = id;
            this.cost = cost;
            this.cooldownTicks = cooldownTicks;
        }

        @Nullable
        public static Move byName(@Nullable String name) {
            if (name == null) {
                return null;
            }
            String n = name.toLowerCase().replace(' ', '_');
            for (Move m : values()) {
                if (m.id.equals(n) || m.name().equalsIgnoreCase(n)) {
                    return m;
                }
            }
            return null;
        }
    }

    /** One hidden zombie per dimension. Only its pathfinder is used, to plan routes for the bot. */
    private static final Map<ResourceKey<Level>, Mob> NAV_DUMMIES = new HashMap<>();

    private int ticks;
    boolean dying;

    private Intent intent = Intent.WANDER;
    private UUID followId;
    private BlockPos gotoPos;
    private final ArrayDeque<BlockPos> mineQueue = new ArrayDeque<>();
    private BlockPos breaking;
    private float breakProgress;
    private int breakStage = -1;

    // movement
    private BlockPos moveGoal;
    private double moveStopSq;
    private Path path;
    private int pathAge;
    private Vec3 progressPos = Vec3.ZERO;
    private int progressTicks;
    private int stuckStrikes;
    private boolean moveFailed;
    private int wanderWait = 60;

    // combat
    private LivingEntity target;
    private int targetUntil;

    // cursed energy
    private float cursedEnergy = 100.0F;
    private final EnumMap<Move, Integer> readyAt = new EnumMap<>(Move.class);
    private int domainTicks;
    private BlockPos domainCenter;

    // social, quests, real cursed fate
    private boolean questMode;
    final CfDriver cf = new CfDriver(this);
    final QuestBot quests = new QuestBot(this);

    public SentientEntity(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile);
    }

    // ------------------------------------------------------------------ public control api

    public float cursedEnergy() {
        return this.cursedEnergy;
    }

    public Intent intent() {
        return this.intent;
    }

    @Nullable
    public LivingEntity currentTarget() {
        return this.target;
    }

    /** Ticks this body has lived since it was created (not saved). */
    public int age() {
        return this.ticks;
    }

    public boolean isQuestMode() {
        return this.questMode;
    }

    public void setQuestMode(boolean on) {
        this.questMode = on;
    }

    public String questStatus() {
        return this.quests.status();
    }

    public boolean isWalking() {
        return this.moveGoal != null;
    }

    /** True once if the last walk gave up (no route or stuck). */
    public boolean consumeMoveFailed() {
        boolean f = this.moveFailed;
        this.moveFailed = false;
        return f;
    }

    /** Makes the body go after this mob (used by quests). */
    public void setQuestTarget(LivingEntity e) {
        this.target = e;
        this.targetUntil = this.ticks + 400;
    }

    /** Free will: goes after a player by name, or else the nearest living thing whose type matches the text. */
    public boolean attackNamed(String what) {
        if (what == null || what.isBlank() || !(this.level() instanceof ServerLevel sl)) {
            return false;
        }
        String w = what.trim().toLowerCase();
        ServerPlayer p = sl.getServer().getPlayerList().getPlayerByName(what.trim());
        LivingEntity pick = null;
        if (p != null && p != this && p.isAlive() && !p.isSpectator() && !p.isCreative() && p.level() == this.level()) {
            pick = p;
        } else {
            double bd = Double.MAX_VALUE;
            for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, this.getBoundingBox().inflate(32.0),
                    x -> x != this && x.isAlive() && !(x instanceof Player))) {
                net.minecraft.resources.ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
                if (id != null && id.getPath().contains(w) && this.distanceToSqr(e) < bd) {
                    bd = this.distanceToSqr(e);
                    pick = e;
                }
            }
        }
        if (pick == null) {
            return false;
        }
        this.target = pick;
        this.targetUntil = this.ticks + 600;
        return true;
    }

    public boolean usesRealCursedFate() {
        return this.cf.active();
    }

    public String cursedFateStatus() {
        return this.cf.summary();
    }

    /** Presses a real Cursed Fate ability slot (1-7), or a random ready one for any other number. */
    public boolean castAbility(int slot) {
        return this.cf.cast(this.target, slot);
    }

    private boolean canHealSelf() {
        return !this.cf.active() && this.ready(Move.REVERSE);
    }

    public void followPlayer(UUID id) {
        this.followId = id;
        this.intent = Intent.FOLLOW;
        this.mineQueue.clear();
        this.resetBreak();
    }

    public void comeTo(BlockPos pos) {
        this.gotoPos = pos;
        this.intent = Intent.GOTO;
        this.mineQueue.clear();
        this.resetBreak();
        this.moveFailed = false;
        this.walkTo(pos, 1.5);
    }

    public void stay() {
        this.intent = Intent.STAY;
        this.mineQueue.clear();
        this.resetBreak();
        this.stopWalking();
    }

    public void wander() {
        this.intent = Intent.WANDER;
        this.mineQueue.clear();
        this.resetBreak();
        this.stopWalking();
    }

    public String describeIntent() {
        if (this.target != null) {
            return "fighting something";
        }
        switch (this.intent) {
            case FOLLOW:
                return "following a player";
            case STAY:
                return "standing still";
            case GOTO:
                return "walking to a spot";
            case MINE:
                return "mining (" + this.mineQueue.size() + " blocks left)";
            default:
                return this.questMode ? "working on quests (" + this.quests.status() + ")" : "wandering around";
        }
    }

    // ------------------------------------------------------------------ mining rules

    public boolean canMine(BlockState s, BlockPos pos) {
        if (!PaqganeConfig.SENTIENT_MINING.get() || s.isAir() || s.hasBlockEntity()) {
            return false;
        }
        if (s.getDestroySpeed(this.level(), pos) < 0.0F) {
            return false;
        }
        return s.is(BlockTags.LOGS) || s.is(Tags.Blocks.ORES) || s.is(BlockTags.BASE_STONE_OVERWORLD)
                || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL);
    }

    /** Blocks it may remove only because they are in the way of something it wants. */
    private boolean canMineObstruction(BlockState s, BlockPos pos) {
        return this.canMine(s, pos) || (PaqganeConfig.SENTIENT_MINING.get() && s.is(BlockTags.LEAVES));
    }

    private boolean exposed(BlockPos p) {
        for (Direction d : Direction.values()) {
            BlockPos n = p.relative(d);
            if (this.level().getBlockState(n).getCollisionShape(this.level(), n).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean standable(BlockPos q) {
        Level l = this.level();
        return l.getBlockState(q).getCollisionShape(l, q).isEmpty()
                && l.getBlockState(q.above()).getCollisionShape(l, q.above()).isEmpty()
                && !l.getBlockState(q.below()).getCollisionShape(l, q.below()).isEmpty();
    }

    /** A spot next to the block (or at the foot of its column) where the bot can stand while it mines it. */
    private BlockPos standSpot(BlockPos p) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = p.relative(d);
            for (int dy = 0; dy <= 8; dy++) {
                BlockPos q = n.below(dy);
                if (this.standable(q)) {
                    double dist = q.distSqr(this.blockPosition());
                    if (dist < bd) {
                        bd = dist;
                        best = q;
                    }
                    break;
                }
            }
        }
        return best != null ? best : p.above();
    }

    /** Queues up to count exposed blocks of that type near the body. Returns how many were queued, or -1 if it won't mine that. */
    public int queueMine(Block block, int count) {
        if (!this.canMine(block.defaultBlockState(), this.blockPosition())) {
            return -1;
        }
        BlockPos center = this.blockPosition();
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-16, -8, -16), center.offset(16, 8, 16))) {
            if (this.level().getBlockState(pos).getBlock() == block && this.exposed(pos)) {
                found.add(pos.immutable());
            }
        }
        found.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
        this.mineQueue.clear();
        this.resetBreak();
        int n = Math.min(count, found.size());
        for (int i = 0; i < n; i++) {
            this.mineQueue.add(found.get(i));
        }
        if (n > 0) {
            this.intent = Intent.MINE;
            this.moveFailed = false;
            this.stopWalking();
        }
        return n;
    }

    /** Hands items from the body's own inventory to a player. Returns how many were handed over. */
    public int giveTo(ServerPlayer to, Item item, int count) {
        int given = 0;
        Inventory inv = this.getInventory();
        for (int i = 0; i < inv.items.size() && given < count; i++) {
            ItemStack s = inv.items.get(i);
            if (!s.isEmpty() && s.getItem() == item) {
                int take = Math.min(s.getCount(), count - given);
                ItemStack out = s.split(take);
                inv.items.set(i, s.isEmpty() ? ItemStack.EMPTY : s);
                net.minecraftforge.items.ItemHandlerHelper.giveItemToPlayer(to, out);
                given += take;
            }
        }
        return given;
    }

    // ------------------------------------------------------------------ ticking

    @Override
    public void tick() {
        if (this.level().isClientSide || this.dying) {
            super.tick();
            return;
        }
        this.ticks++;
        ServerLevel sl = this.serverLevel();
        if (this.connection != null && this.ticks % 10 == 0) {
            this.connection.resetPosition();
            sl.getChunkSource().move(this);
        }
        this.think(sl);
        try {
            super.tick();
            this.doTick();
        } catch (NullPointerException ignored) {
            // parts of the vanilla player tick expect a real network connection
        }
    }

    private void think(ServerLevel sl) {
        this.xxa = 0.0F;
        this.zza = 0.0F;
        this.jumping = false;

        if (this.ticks % 20 == 0) {
            this.cursedEnergy = Math.min(100.0F, this.cursedEnergy + 1.5F);
            this.autoEat();
        }
        if (this.ticks % 40 == 0) {
            this.equipBest();
        }
        if (this.domainTicks > 0) {
            this.tickDomain(sl);
        }
        this.cf.tick(this.ticks);
        if (this.ticks % 10 == 0) {
            this.pickTarget(sl);
            this.autoMoves(sl);
        }

        if (this.target != null) {
            this.fight();
        } else {
            this.runIntent(sl);
        }
        this.driveMovement(sl);
    }

    // ------------------------------------------------------------------ intents

    private void runIntent(ServerLevel sl) {
        switch (this.intent) {
            case FOLLOW -> {
                ServerPlayer t = this.followId == null ? null : sl.getServer().getPlayerList().getPlayer(this.followId);
                if (t == null || t.level() != this.level() || !t.isAlive()) {
                    this.intent = Intent.WANDER;
                    return;
                }
                double d2 = this.distanceToSqr(t);
                if (d2 > 60.0 * 60.0) {
                    this.teleportTo(sl, t.getX() + 1.0, t.getY(), t.getZ() + 1.0, this.getYRot(), 0.0F);
                    this.stopWalking();
                } else if (d2 > 25.0) {
                    if (this.moveGoal == null || this.ticks % 20 == 0) {
                        this.walkTo(t.blockPosition(), 3.0);
                    }
                } else if (d2 < 9.0) {
                    this.stopWalking();
                    this.lookAt(EntityAnchorArgument.Anchor.EYES, t.getEyePosition());
                }
            }
            case GOTO -> {
                if (this.gotoPos == null || this.moveFailed) {
                    this.moveFailed = false;
                    this.intent = Intent.STAY;
                } else if (this.moveGoal == null) {
                    // arrived
                    this.intent = Intent.STAY;
                }
            }
            case MINE -> this.mineTick();
            case WANDER -> {
                if (this.grabNearbyItem(sl)) {
                    return;
                }
                if (this.questMode && this.quests.tick()) {
                    return;
                }
                ServerPlayer buddy = Social.buddy(this);
                if (buddy != null && this.moveGoal == null && this.distanceToSqr(buddy) > 64.0) {
                    this.walkTo(buddy.blockPosition(), 4.0);
                    return;
                }
                if (this.moveGoal == null && --this.wanderWait <= 0) {
                    this.wanderWait = 80 + this.random.nextInt(160);
                    int dx = this.random.nextInt(21) - 10;
                    int dz = this.random.nextInt(21) - 10;
                    BlockPos probe = this.blockPosition().offset(dx, 0, dz);
                    BlockPos ground = sl.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, probe);
                    if (Math.abs(ground.getY() - this.getY()) < 6.0) {
                        this.walkTo(ground, 1.5);
                    }
                }
            }
            default -> {
            }
        }
    }

    /** Walks to loose items close by. It also picks up anything it touches, like any player. */
    private boolean grabNearbyItem(ServerLevel sl) {
        if (!PaqganeConfig.SENTIENT_PICKUP.get() || this.moveGoal != null || this.ticks % 10 != 0) {
            return false;
        }
        ItemEntity best = null;
        double bd = Double.MAX_VALUE;
        for (ItemEntity ie : sl.getEntitiesOfClass(ItemEntity.class, this.getBoundingBox().inflate(8.0, 3.0, 8.0))) {
            if (ie.isRemoved() || ie.getItem().isEmpty() || ie.hasPickUpDelay()) {
                continue;
            }
            double d = this.distanceToSqr(ie);
            if (d < bd) {
                bd = d;
                best = ie;
            }
        }
        if (best != null && bd > 1.5) {
            this.walkTo(best.blockPosition(), 0.6);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ movement

    public void walkTo(BlockPos goal, double stopDist) {
        this.moveGoal = goal;
        this.moveStopSq = stopDist * stopDist;
        this.path = null;
        this.pathAge = 999;
        this.stuckStrikes = 0;
        this.progressTicks = 0;
        this.progressPos = this.position();
        this.moveFailed = false;
    }

    public void stopWalking() {
        this.moveGoal = null;
        this.path = null;
    }

    @Nullable
    private Path computePath(ServerLevel sl, BlockPos goal) {
        Mob dummy = NAV_DUMMIES.get(sl.dimension());
        if (dummy == null || dummy.level() != sl) {
            Zombie z = EntityType.ZOMBIE.create(sl);
            if (z == null) {
                return null;
            }
            if (z.getNavigation() instanceof GroundPathNavigation nav) {
                nav.setCanOpenDoors(true);
            }
            NAV_DUMMIES.put(sl.dimension(), z);
            dummy = z;
        }
        dummy.setPos(this.getX(), this.getY(), this.getZ());
        dummy.setOnGround(true);
        return dummy.getNavigation().createPath(goal, 1);
    }

    private void driveMovement(ServerLevel sl) {
        if (this.moveGoal == null) {
            this.setSprinting(false);
            return;
        }
        Vec3 goalC = Vec3.atBottomCenterOf(this.moveGoal);
        double dist2 = this.position().distanceToSqr(goalC);
        if (dist2 <= this.moveStopSq) {
            this.stopWalking();
            this.setSprinting(false);
            return;
        }

        // progress watchdog
        if (++this.progressTicks >= 20) {
            this.progressTicks = 0;
            if (this.position().distanceToSqr(this.progressPos) < 0.09) {
                if (++this.stuckStrikes >= 4) {
                    this.moveFailed = true;
                    this.stopWalking();
                    return;
                }
                this.path = null;
                this.pathAge = 999;
                if (this.onGround()) {
                    this.jumping = true;
                }
            } else {
                this.stuckStrikes = 0;
            }
            this.progressPos = this.position();
        }

        if (this.path == null || this.path.isDone() || ++this.pathAge > 60) {
            this.path = this.computePath(sl, this.moveGoal);
            this.pathAge = 0;
        }

        Vec3 aim;
        if (this.path != null && !this.path.isDone()) {
            for (int guard = 0; guard < 3 && !this.path.isDone(); guard++) {
                BlockPos np = this.path.getNextNodePos();
                double dx = this.getX() - (np.getX() + 0.5);
                double dz = this.getZ() - (np.getZ() + 0.5);
                if (dx * dx + dz * dz < 0.3 * 0.3 && Math.abs(this.getY() - np.getY()) < 1.2) {
                    this.path.advance();
                } else {
                    break;
                }
            }
            if (this.path.isDone()) {
                aim = goalC;
            } else {
                BlockPos np = this.path.getNextNodePos();
                aim = new Vec3(np.getX() + 0.5, np.getY(), np.getZ() + 0.5);
            }
        } else if (dist2 < 36.0) {
            aim = goalC;
        } else {
            // no route and too far to walk straight: give up
            this.moveFailed = true;
            this.stopWalking();
            return;
        }

        double ddx = aim.x - this.getX();
        double ddz = aim.z - this.getZ();
        float yaw = (float) (Mth.atan2(ddz, ddx) * (180.0D / Math.PI)) - 90.0F;
        this.setYRot(yaw);
        this.setYHeadRot(yaw);
        this.zza = 1.0F;
        this.setSprinting(dist2 > 64.0 && this.getFoodData().getFoodLevel() > 6 && this.target == null);

        if (this.onGround() && (aim.y > this.getY() + 0.6 || this.horizontalCollision)) {
            this.jumping = true;
        }
        if ((this.isInWater() || this.isInLava()) && aim.y >= this.getY() - 0.5) {
            this.jumping = true;
        }
        if (this.ticks % 5 == 0) {
            this.openDoorAhead(sl, ddx, ddz);
        }
    }

    private void openDoorAhead(ServerLevel sl, double dx, double dz) {
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-3) {
            return;
        }
        double fx = this.getX() + dx / len * 0.9;
        double fz = this.getZ() + dz / len * 0.9;
        for (int up = 0; up <= 1; up++) {
            BlockPos p = BlockPos.containing(fx, this.getY() + up, fz);
            BlockState s = sl.getBlockState(p);
            if ((s.getBlock() instanceof DoorBlock || s.getBlock() instanceof FenceGateBlock)
                    && s.hasProperty(BlockStateProperties.OPEN) && !s.getValue(BlockStateProperties.OPEN)) {
                s.use(sl, this, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(p), Direction.UP, p, false));
                return;
            }
        }
    }

    // ------------------------------------------------------------------ mining

    private void resetBreak() {
        if (this.breaking != null && !this.level().isClientSide) {
            this.level().destroyBlockProgress(this.getId(), this.breaking, -1);
        }
        this.breaking = null;
        this.breakProgress = 0.0F;
        this.breakStage = -1;
    }

    private void mineTick() {
        BlockPos p = this.mineQueue.peek();
        if (p == null) {
            this.resetBreak();
            this.intent = Intent.WANDER;
            this.wanderWait = 10;
            return;
        }
        BlockState s = this.level().getBlockState(p);
        if (s.isAir() || !this.canMine(s, p)) {
            this.mineQueue.poll();
            this.resetBreak();
            return;
        }
        Vec3 eye = this.getEyePosition();
        Vec3 c = Vec3.atCenterOf(p);
        if (eye.distanceToSqr(c) > 20.25) {
            this.resetBreak();
            if (this.moveGoal == null) {
                if (this.moveFailed) {
                    this.moveFailed = false;
                    this.mineQueue.poll();
                    return;
                }
                this.walkTo(this.standSpot(p), 0.8);
            }
            return;
        }
        this.stopWalking();
        BlockPos tgt = p;
        BlockHitResult hr = this.level().clip(new ClipContext(eye, c, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        if (hr.getType() == HitResult.Type.BLOCK && !hr.getBlockPos().equals(p)) {
            BlockPos ob = hr.getBlockPos();
            if (this.canMineObstruction(this.level().getBlockState(ob), ob)) {
                tgt = ob;
            } else {
                this.mineQueue.poll();
                this.resetBreak();
                return;
            }
        }
        this.breakBlock(tgt);
    }

    private void breakBlock(BlockPos tgt) {
        if (!tgt.equals(this.breaking)) {
            this.resetBreak();
            this.breaking = tgt;
        }
        BlockState s = this.level().getBlockState(tgt);
        if (s.isAir()) {
            this.resetBreak();
            return;
        }
        this.selectToolFor(s);
        this.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(tgt));
        if (this.ticks % 4 == 0) {
            this.swing(InteractionHand.MAIN_HAND);
        }
        this.breakProgress += s.getDestroyProgress(this, this.level(), tgt);
        int stage = Math.min(9, (int) (this.breakProgress * 10.0F));
        if (stage != this.breakStage) {
            this.level().destroyBlockProgress(this.getId(), tgt, stage);
            this.breakStage = stage;
        }
        if (this.breakProgress >= 1.0F) {
            this.level().destroyBlockProgress(this.getId(), tgt, -1);
            this.gameMode.destroyBlock(tgt);
            this.breaking = null;
            this.breakProgress = 0.0F;
            this.breakStage = -1;
            if (tgt.equals(this.mineQueue.peek())) {
                this.mineQueue.poll();
            }
        }
    }

    private void selectToolFor(BlockState s) {
        Inventory inv = this.getInventory();
        int best = inv.selected;
        float bestSpeed = inv.items.get(best).getDestroySpeed(s);
        for (int i = 0; i < inv.items.size(); i++) {
            float sp = inv.items.get(i).getDestroySpeed(s);
            if (sp > bestSpeed + 0.01F) {
                bestSpeed = sp;
                best = i;
            }
        }
        this.moveToHand(best);
    }

    /** Makes the item in that inventory slot the one in its hand (swapping it into the current hotbar slot if needed). */
    private void moveToHand(int slot) {
        Inventory inv = this.getInventory();
        if (slot == inv.selected) {
            return;
        }
        if (slot < 9) {
            inv.selected = slot;
        } else {
            ItemStack a = inv.items.get(slot);
            ItemStack b = inv.items.get(inv.selected);
            inv.items.set(slot, b);
            inv.items.set(inv.selected, a);
        }
    }

    // ------------------------------------------------------------------ gear and food

    private static double armorScore(ItemStack s) {
        if (s.getItem() instanceof ArmorItem a) {
            return a.getDefense() + a.getToughness();
        }
        return 0.0;
    }

    private static double weaponScore(ItemStack s) {
        if (s.isEmpty()) {
            return 0.0;
        }
        Item it = s.getItem();
        if (!(it instanceof SwordItem) && !(it instanceof AxeItem) && !(it instanceof TridentItem)) {
            return 0.0;
        }
        double sum = 0.0;
        for (AttributeModifier m : s.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE)) {
            sum += m.getAmount();
        }
        return sum;
    }

    private void equipBest() {
        Inventory inv = this.getInventory();
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack cur = this.getItemBySlot(slot);
            double best = armorScore(cur);
            int idx = -1;
            for (int i = 0; i < inv.items.size(); i++) {
                ItemStack s = inv.items.get(i);
                if (s.getItem() instanceof ArmorItem a && a.getEquipmentSlot() == slot && armorScore(s) > best) {
                    best = armorScore(s);
                    idx = i;
                }
            }
            if (idx >= 0) {
                ItemStack take = inv.items.get(idx);
                inv.items.set(idx, cur);
                this.setItemSlot(slot, take);
            }
        }
    }

    private void selectWeapon() {
        Inventory inv = this.getInventory();
        double bestW = weaponScore(inv.items.get(inv.selected));
        int best = -1;
        for (int i = 0; i < inv.items.size(); i++) {
            double sc = weaponScore(inv.items.get(i));
            if (sc > bestW) {
                bestW = sc;
                best = i;
            }
        }
        if (best >= 0) {
            this.moveToHand(best);
        }
    }

    /** Puts everything it wears back into its inventory (overflow is dropped). Used by the op command. */
    public void unequipAll() {
        Inventory inv = this.getInventory();
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.OFFHAND}) {
            ItemStack s = this.getItemBySlot(slot);
            if (!s.isEmpty()) {
                ItemStack copy = s.copy();
                this.setItemSlot(slot, ItemStack.EMPTY);
                if (!inv.add(copy)) {
                    this.drop(copy, false);
                }
            }
        }
    }

    private void autoEat() {
        boolean hungry = this.getFoodData().getFoodLevel() <= 14;
        boolean hurt = this.getHealth() < this.getMaxHealth() * 0.7F && this.getFoodData().needsFood();
        if (!hungry && !hurt) {
            return;
        }
        Inventory inv = this.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (!s.isEmpty() && s.isEdible() && s.getItem().getFoodProperties() != null) {
                ItemStack result = s.finishUsingItem(this.level(), this);
                inv.items.set(i, result.isEmpty() ? ItemStack.EMPTY : result);
                this.swing(InteractionHand.MAIN_HAND);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ crafting

    private boolean tableNearby() {
        BlockPos c = this.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-4, -2, -4), c.offset(4, 2, 4))) {
            if (this.level().getBlockState(p).is(Blocks.CRAFTING_TABLE)) {
                return true;
            }
        }
        return false;
    }

    private boolean placeTableFromInventory() {
        Inventory inv = this.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (!s.isEmpty() && s.is(Blocks.CRAFTING_TABLE.asItem())) {
                BlockPos c = this.blockPosition();
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos p = c.relative(d);
                    if (this.level().getBlockState(p).isAir()
                            && !this.level().getBlockState(p.below()).getCollisionShape(this.level(), p.below()).isEmpty()) {
                        this.level().setBlock(p, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
                        s.shrink(1);
                        inv.items.set(i, s.isEmpty() ? ItemStack.EMPTY : s);
                        this.swing(InteractionHand.MAIN_HAND);
                        return true;
                    }
                }
                return false;
            }
        }
        return false;
    }

    /** Crafts up to times of an item from what it carries. Recipes bigger than 2x2 need a crafting table nearby (it places its own if it has one). */
    public int craft(Item wanted, int times) {
        int made = 0;
        for (int n = 0; n < Math.max(1, Math.min(times, 16)); n++) {
            if (!this.craftOnce(wanted)) {
                break;
            }
            made++;
        }
        return made;
    }

    private boolean hasIngredient(Ingredient ing) {
        for (ItemStack s : this.getInventory().items) {
            if (!s.isEmpty() && ing.test(s)) {
                return true;
            }
        }
        return false;
    }

    /** Like craft, but if an ingredient is missing it first tries to craft that ingredient from what it carries (two levels deep). */
    public int craftChain(Item wanted, int times, int depth) {
        int made = this.craft(wanted, times);
        if (made > 0 || depth >= 2) {
            return made;
        }
        List<CraftingRecipe> recipes = this.level().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING);
        for (CraftingRecipe r : recipes) {
            ItemStack result = r.getResultItem(this.level().registryAccess());
            if (result.isEmpty() || result.getItem() != wanted || r.isSpecial()) {
                continue;
            }
            boolean any = false;
            for (Ingredient ing : r.getIngredients()) {
                if (ing.isEmpty() || this.hasIngredient(ing)) {
                    continue;
                }
                for (ItemStack opt : ing.getItems()) {
                    if (this.craftChain(opt.getItem(), 1, depth + 1) > 0) {
                        any = true;
                        break;
                    }
                }
            }
            if (any) {
                made = this.craft(wanted, times);
                if (made > 0) {
                    return made;
                }
            }
        }
        return 0;
    }

    private boolean craftOnce(Item wanted) {
        Inventory inv = this.getInventory();
        List<CraftingRecipe> recipes = this.level().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING);
        for (CraftingRecipe r : recipes) {
            ItemStack result = r.getResultItem(this.level().registryAccess());
            if (result.isEmpty() || result.getItem() != wanted || r.isSpecial() || r.getIngredients().isEmpty()) {
                continue;
            }
            Map<Integer, Integer> used = new HashMap<>();
            boolean ok = true;
            for (Ingredient ing : r.getIngredients()) {
                if (ing.isEmpty()) {
                    continue;
                }
                boolean found = false;
                for (int i = 0; i < inv.items.size(); i++) {
                    ItemStack s = inv.items.get(i);
                    if (!s.isEmpty() && ing.test(s) && s.getCount() - used.getOrDefault(i, 0) > 0) {
                        used.merge(i, 1, Integer::sum);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                continue;
            }
            if (!r.canCraftInDimensions(2, 2) && !this.tableNearby() && !this.placeTableFromInventory()) {
                continue;
            }
            for (Map.Entry<Integer, Integer> e : used.entrySet()) {
                ItemStack s = inv.items.get(e.getKey());
                s.shrink(e.getValue());
                inv.items.set(e.getKey(), s.isEmpty() ? ItemStack.EMPTY : s);
            }
            ItemStack out = result.copy();
            if (!inv.add(out)) {
                this.drop(out, false);
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ fighting

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean r = super.hurt(source, amount);
        if (r && !this.level().isClientSide && source.getEntity() instanceof LivingEntity a && a != this && a.isAlive()) {
            boolean fightBack = true;
            if (a instanceof ServerPlayer sp && !SentientManager.isBot(sp)) {
                fightBack = Social.onHurtByPlayer(this, sp, amount);
            }
            if (fightBack) {
                this.target = a;
                this.targetUntil = this.ticks + 200;
            }
        }
        return r;
    }

    private void pickTarget(ServerLevel sl) {
        LivingEntity prio = Social.priorityTarget(this, sl);
        if (prio != null) {
            this.target = prio;
            this.targetUntil = this.ticks + 100;
            return;
        }
        if (this.target != null) {
            double maxD = this.target instanceof Player ? 48.0 : 30.0;
            if (!this.target.isAlive() || this.target.isRemoved() || this.target.level() != this.level()
                    || this.distanceToSqr(this.target) > maxD * maxD || this.ticks > this.targetUntil) {
                this.target = null;
            } else {
                return;
            }
        }
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        for (Monster m : sl.getEntitiesOfClass(Monster.class, this.getBoundingBox().inflate(14.0))) {
            if (m instanceof NeutralMob || !m.isAlive() || !this.hasLineOfSight(m)) {
                continue;
            }
            double d = this.distanceToSqr(m);
            if (d < bd) {
                bd = d;
                best = m;
            }
        }
        if (best != null) {
            this.target = best;
            this.targetUntil = this.ticks + 300;
        }
    }

    private void fight() {
        LivingEntity t = this.target;
        if (t == null) {
            return;
        }
        double d = this.distanceTo(t);
        boolean low = this.getHealth() < this.getMaxHealth() * 0.3F;
        if (low && !this.canHealSelf()) {
            // run away from it
            Vec3 away = this.position().subtract(t.position()).normalize().scale(12.0);
            if (this.moveGoal == null || this.ticks % 20 == 0) {
                this.walkTo(BlockPos.containing(this.position().add(away)), 1.5);
            }
            return;
        }
        this.lookAt(EntityAnchorArgument.Anchor.EYES, t.getEyePosition());
        if (d > 2.6) {
            if (this.moveGoal == null || this.ticks % 10 == 0) {
                this.walkTo(t.blockPosition(), 1.6);
            }
        } else {
            this.stopWalking();
        }
        if (d <= 3.2 && this.hasLineOfSight(t) && this.getAttackStrengthScale(0.5F) >= 1.0F) {
            this.selectWeapon();
            this.attack(t);
            this.swing(InteractionHand.MAIN_HAND);
        }
    }

    // ------------------------------------------------------------------ cursed moves

    private boolean ready(Move m) {
        return this.cursedEnergy >= m.cost && this.ticks >= this.readyAt.getOrDefault(m, 0);
    }

    private boolean validVictim(LivingEntity v) {
        if (v == this || !v.isAlive()) {
            return false;
        }
        if (v instanceof Enemy) {
            return true;
        }
        return (Social.free || PaqganeConfig.SENTIENT_MOVES_HIT_PLAYERS.get()) && v instanceof Player && v == this.target;
    }

    /** Uses the real Cursed Fate abilities against what it is fighting. */
    private void cfAuto(ServerLevel sl) {
        LivingEntity t = this.target;
        if (t == null || !t.isAlive()) {
            return;
        }
        if (this.distanceTo(t) > 24.0 || !this.hasLineOfSight(t)) {
            return;
        }
        if (t instanceof Player && !Social.free && !PaqganeConfig.SENTIENT_MOVES_HIT_PLAYERS.get()) {
            return;
        }
        if (Social.protectedNear(this, t, 3.5)) {
            return;
        }
        if (this.random.nextInt(2) == 0) {
            this.cf.cast(t, -1);
        }
    }

    private void autoMoves(ServerLevel sl) {
        if (this.cf.active()) {
            this.cfAuto(sl);
            return;
        }
        if (this.domainTicks > 0) {
            return;
        }
        boolean low = this.getHealth() < this.getMaxHealth() * 0.5F;
        if (low && this.ready(Move.REVERSE)) {
            this.useMove(Move.REVERSE, null);
            return;
        }
        LivingEntity t = this.target;
        if (t == null || !t.isAlive()) {
            return;
        }
        int hostiles = sl.getEntitiesOfClass(Monster.class, this.getBoundingBox().inflate(10.0)).size();
        double d = this.distanceTo(t);
        if ((hostiles >= 3 || (low && hostiles >= 1)) && this.ready(Move.DOMAIN)) {
            this.useMove(Move.DOMAIN, null);
        } else if (d <= 4.0 && this.ready(Move.BLACK_FLASH) && this.random.nextInt(3) == 0) {
            this.useMove(Move.BLACK_FLASH, t);
        } else if (d > 5.0 && d < 24.0 && this.hasLineOfSight(t) && this.ready(Move.HOLLOW_PURPLE)) {
            this.lookAt(EntityAnchorArgument.Anchor.EYES, t.getEyePosition());
            this.useMove(Move.HOLLOW_PURPLE, t);
        }
    }

    /** Returns false when it lacks the energy or is on cooldown. A null target means "just show it off" or "hit whatever is hostile in front". */
    public boolean useMove(Move m, @Nullable LivingEntity tgt) {
        if (!(this.level() instanceof ServerLevel sl) || !this.ready(m)) {
            return false;
        }
        this.cursedEnergy -= m.cost;
        this.readyAt.put(m, this.ticks + m.cooldownTicks);
        this.swing(InteractionHand.MAIN_HAND);

        switch (m) {
            case BLACK_FLASH -> {
                LivingEntity v = tgt;
                if (v == null || this.distanceTo(v) > 5.0) {
                    v = this.nearestHostile(sl, 5.0);
                }
                if (v != null && this.validVictim(v)) {
                    v.hurt(this.damageSources().playerAttack(this), 9.0F);
                    v.knockback(0.9, this.getX() - v.getX(), this.getZ() - v.getZ());
                    sl.sendParticles(ParticleTypes.ELECTRIC_SPARK, v.getX(), v.getY() + v.getBbHeight() / 2, v.getZ(), 30, 0.4, 0.5, 0.4, 0.2);
                    sl.sendParticles(ParticleTypes.SQUID_INK, v.getX(), v.getY() + v.getBbHeight() / 2, v.getZ(), 12, 0.3, 0.4, 0.3, 0.05);
                } else {
                    sl.sendParticles(ParticleTypes.ELECTRIC_SPARK, this.getX(), this.getEyeY(), this.getZ(), 20, 0.5, 0.3, 0.5, 0.15);
                }
                this.playSound(SoundEvents.LIGHTNING_BOLT_THUNDER, 0.4F, 2.0F);
            }
            case HOLLOW_PURPLE -> {
                Vec3 eye = this.getEyePosition();
                Vec3 dir = tgt != null ? tgt.getEyePosition().subtract(eye).normalize() : this.getLookAngle();
                Set<LivingEntity> hit = new HashSet<>();
                for (int i = 1; i <= 32 && hit.size() < 3; i++) {
                    Vec3 p = eye.add(dir.scale(i * 0.75));
                    BlockPos bp = BlockPos.containing(p);
                    if (!sl.getBlockState(bp).getCollisionShape(sl, bp).isEmpty()) {
                        break;
                    }
                    sl.sendParticles(ParticleTypes.WITCH, p.x, p.y, p.z, 2, 0.05, 0.05, 0.05, 0.0);
                    sl.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 1, 0.1, 0.1, 0.1, 0.0);
                    AABB box = new AABB(p.x - 0.8, p.y - 0.8, p.z - 0.8, p.x + 0.8, p.y + 0.8, p.z + 0.8);
                    for (LivingEntity v : sl.getEntitiesOfClass(LivingEntity.class, box, this::validVictim)) {
                        if (hit.add(v)) {
                            v.hurt(this.damageSources().indirectMagic(this, this), 14.0F);
                        }
                    }
                }
                this.playSound(SoundEvents.ENDER_DRAGON_SHOOT, 0.6F, 1.5F);
            }
            case REVERSE -> {
                this.heal(12.0F);
                sl.sendParticles(ParticleTypes.HEART, this.getX(), this.getY() + 1.2, this.getZ(), 8, 0.4, 0.5, 0.4, 0.02);
                this.playSound(SoundEvents.BEACON_POWER_SELECT, 0.8F, 1.6F);
            }
            case DOMAIN -> {
                this.domainTicks = 160;
                this.domainCenter = this.blockPosition();
                this.playSound(SoundEvents.BEACON_ACTIVATE, 1.0F, 0.6F);
            }
        }
        return true;
    }

    @Nullable
    private LivingEntity nearestHostile(ServerLevel sl, double radius) {
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, this.getBoundingBox().inflate(radius), this::validVictim)) {
            double d = this.distanceToSqr(e);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    private void tickDomain(ServerLevel sl) {
        this.domainTicks--;
        if (this.domainCenter == null) {
            this.domainTicks = 0;
            return;
        }
        double cx = this.domainCenter.getX() + 0.5;
        double cy = this.domainCenter.getY();
        double cz = this.domainCenter.getZ() + 0.5;
        if (this.domainTicks % 10 == 0) {
            for (int i = 0; i < 24; i++) {
                double a = i * (Math.PI * 2.0 / 24.0);
                sl.sendParticles(ParticleTypes.SQUID_INK, cx + Math.cos(a) * 8.0, cy + 0.3, cz + Math.sin(a) * 8.0, 1, 0, 0, 0, 0);
            }
            AABB box = new AABB(cx - 8, cy - 3, cz - 8, cx + 8, cy + 5, cz + 8);
            for (Monster m : sl.getEntitiesOfClass(Monster.class, box)) {
                m.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 2));
                m.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 30, 1));
                if (this.domainTicks % 20 == 0) {
                    m.hurt(this.damageSources().indirectMagic(this, this), 2.0F);
                }
            }
        }
    }

    // ------------------------------------------------------------------ interaction, death

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (!this.level().isClientSide && player instanceof ServerPlayer sp && !SentientManager.isBot(sp)) {
            if (sp.isShiftKeyDown() && Brain.isOp(sp)) {
                this.openInventory(sp);
                return InteractionResult.CONSUME;
            }
            ItemStack held = sp.getItemInHand(hand);
            if (PaqganeConfig.SENTIENT_ACCEPT_GIFTS.get() && !held.isEmpty()) {
                ItemStack gift = held.copy();
                int before = gift.getCount();
                this.getInventory().add(gift);
                int given = before - gift.getCount();
                if (given > 0) {
                    ItemStack record = held.copy();
                    record.setCount(given);
                    sp.setItemInHand(hand, gift.isEmpty() ? ItemStack.EMPTY : gift);
                    Social.onGift(this, sp, record);
                }
                return InteractionResult.CONSUME;
            }
        }
        return super.interact(player, hand);
    }

    /** Shows its 36 main inventory slots to an op. Vanilla only keeps the screen open while the viewer is within 8 blocks of it. */
    public void openInventory(ServerPlayer op) {
        if (op.distanceToSqr(this) > 49.0) {
            PaqganeManager.whisper(op, "stand within 7 blocks of me to open my inventory");
            return;
        }
        op.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new ChestMenu(MenuType.GENERIC_9x4, id, inv, this.getInventory(), 4),
                Component.literal("Paqgane's inventory")));
    }

    @Override
    public void die(DamageSource source) {
        if (this.level().isClientSide || this.dying) {
            super.die(source);
            return;
        }
        this.dying = true;
        this.stopWalking();
        this.resetBreak();
        MinecraftServer server = this.getServer();
        boolean keep = PaqganeConfig.SENTIENT_KEEP_INVENTORY.get();
        GameRules.BooleanValue rule = this.serverLevel().getGameRules().getRule(GameRules.RULE_KEEPINVENTORY);
        boolean old = rule.get();
        if (keep) {
            rule.set(true, server);
        }
        try {
            super.die(source);
        } finally {
            if (keep) {
                rule.set(old, server);
            }
        }
        SentientManager.onDeath(this, source);
        // so the saved player data is not a corpse: it gets a fresh body when it comes back
        this.setHealth(this.getMaxHealth());
        this.getFoodData().setFoodLevel(20);
        if (server != null) {
            server.tell(new TickTask(server.getTickCount() + 1, () -> {
                if (this.connection != null) {
                    this.connection.onDisconnect(Component.literal("died"));
                }
            }));
        }
    }
}
