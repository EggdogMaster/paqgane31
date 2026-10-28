package com.paq.paqgane;

import java.util.ArrayDeque;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.level.BlockEvent;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/** The floating head companion. One per summoning player. */
public class PaqganeEntity extends PathfinderMob implements GeoEntity {
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.paqgane.idle");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private UUID ownerId;
    private Vec3 holdPos;
    private final ArrayDeque<Task> tasks = new ArrayDeque<>();
    private String lastTaskMessage;

    public PaqganeEntity(EntityType<? extends PaqganeEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setInvulnerable(true);
        this.setPersistenceRequired();
        this.setCustomName(Component.literal("Paqgane"));
        this.setCustomNameVisible(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 20.0);
    }

    public void setOwner(UUID id) {
        this.ownerId = id;
    }

    public UUID getOwnerId() {
        return this.ownerId;
    }

    public void follow() {
        this.holdPos = null;
    }

    public void stay() {
        this.holdPos = this.position();
    }

    public void queue(Task task) {
        this.tasks.add(task);
    }

    public int queuedTasks() {
        return this.tasks.size();
    }

    public void clearTasks() {
        this.tasks.clear();
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        ServerLevel sl = (ServerLevel) this.level();
        ServerPlayer owner = this.ownerId == null ? null : sl.getServer().getPlayerList().getPlayer(this.ownerId);
        if (owner == null || owner.isRemoved() || owner.level() != this.level()) {
            PaqganeManager.forget(this.ownerId);
            this.discard();
            return;
        }

        if (this.tickCount % 4 == 0 && !this.tasks.isEmpty()) {
            this.runTask(sl, owner);
        }

        Task current = this.tasks.peek();
        Vec3 target;
        if (current != null) {
            target = Vec3.atCenterOf(current.pos()).add(0.0, 1.8, 0.0);
        } else if (this.holdPos != null) {
            target = this.holdPos.add(0.0, Math.sin(this.tickCount * 0.1) * 0.08, 0.0);
        } else {
            float ownerYaw = owner.getYRot() * ((float) Math.PI / 180F);
            Vec3 right = new Vec3(-Mth.cos(ownerYaw), 0.0, -Mth.sin(ownerYaw));
            target = owner.position().add(right.scale(0.9))
                    .add(0.0, owner.getBbHeight() + 0.1 + Math.sin(this.tickCount * 0.1) * 0.08, 0.0);
        }

        Vec3 delta = target.subtract(this.position());
        if (delta.length() > 32.0) {
            this.setPos(target.x, target.y, target.z);
            this.setDeltaMovement(Vec3.ZERO);
        } else {
            Vec3 v = delta.scale(0.2);
            if (v.length() > 1.0) {
                v = v.normalize();
            }
            this.setDeltaMovement(v);
        }

        Vec3 lookAt = current != null ? Vec3.atCenterOf(current.pos()) : owner.getEyePosition();
        double dx = lookAt.x - this.getX();
        double dz = lookAt.z - this.getZ();
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        this.setYRot(yaw);
        this.setYHeadRot(yaw);
        this.yBodyRot = yaw;
    }

    private void runTask(ServerLevel sl, ServerPlayer owner) {
        Task t = this.tasks.poll();
        if (t == null) {
            return;
        }
        BlockPos pos = t.pos();
        BlockState state = sl.getBlockState(pos);
        if (t.kind() == TaskKind.MINE) {
            if (state.isAir() || state.getDestroySpeed(sl, pos) < 0.0F) {
                return;
            }
            if (!t.bypass() && sl.getBlockEntity(pos) != null) {
                return;
            }
            if (!sl.mayInteract(owner, pos)) {
                return;
            }
            BlockEvent.BreakEvent ev = new BlockEvent.BreakEvent(sl, pos, state, owner);
            if (MinecraftForge.EVENT_BUS.post(ev)) {
                return;
            }
            sl.destroyBlock(pos, true, this);
            sl.sendParticles(ParticleTypes.CLOUD, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 3, 0.2, 0.2, 0.2, 0.01);
        } else {
            if (!state.canBeReplaced() || !sl.mayInteract(owner, pos)) {
                return;
            }
            if (!t.bypass() && !takeFromInventory(owner, t.block())) {
                this.lastTaskMessage = "ran out of " + t.block().getName().getString().toLowerCase() + " in your inventory bro";
                this.tasks.clear();
                PaqganeManager.say(sl.getServer(), this.lastTaskMessage);
                return;
            }
            BlockEvent.EntityPlaceEvent ev = new BlockEvent.EntityPlaceEvent(
                    BlockSnapshot.create(sl.dimension(), sl, pos), sl.getBlockState(pos.below()), owner);
            if (MinecraftForge.EVENT_BUS.post(ev)) {
                if (!t.bypass()) {
                    giveBack(owner, t.block());
                }
                return;
            }
            sl.setBlock(pos, t.block().defaultBlockState(), 3);
            sl.sendParticles(ParticleTypes.POOF, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 3, 0.2, 0.2, 0.2, 0.01);
        }
    }

    private static boolean takeFromInventory(ServerPlayer p, Block block) {
        for (ItemStack s : p.getInventory().items) {
            if (!s.isEmpty() && s.getItem() == block.asItem()) {
                s.shrink(1);
                return true;
            }
        }
        return false;
    }

    private static void giveBack(ServerPlayer p, Block block) {
        p.getInventory().add(new ItemStack(block.asItem()));
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 0, state -> state.setAndContinue(IDLE)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    public record Task(TaskKind kind, BlockPos pos, Block block, boolean bypass) {
    }

    public enum TaskKind {
        MINE,
        PLACE
    }
}
