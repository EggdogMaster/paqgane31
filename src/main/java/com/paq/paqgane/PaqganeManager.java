package com.paq.paqgane;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public class PaqganeManager {
    private static final Map<UUID, UUID> ACTIVE = new ConcurrentHashMap<>();

    public static void toggle(ServerPlayer player) {
        if (isSummoned(player)) {
            dismiss(player);
            say(player.getServer(), "aight im out. call me if u need me");
        } else {
            summon(player);
            say(player.getServer(), "im here. ask me stuff, start with \"paq\" in chat");
        }
    }

    public static boolean isSummoned(ServerPlayer player) {
        return getEntity(player) != null;
    }

    public static int summonedCount() {
        return ACTIVE.size();
    }

    @Nullable
    public static PaqganeEntity getEntity(ServerPlayer player) {
        UUID entityId = ACTIVE.get(player.getUUID());
        if (entityId == null) {
            return null;
        }
        ServerLevel level = player.serverLevel();
        Entity found = level.getEntity(entityId);
        if (found instanceof PaqganeEntity p && p.isAlive()) {
            return p;
        }
        ACTIVE.remove(player.getUUID());
        return null;
    }

    public static void summon(ServerPlayer player) {
        if (getEntity(player) != null) {
            return;
        }
        ServerLevel level = player.serverLevel();
        PaqganeEntity e = ModEntities.PAQGANE.get().create(level);
        if (e != null) {
            e.setOwner(player.getUUID());
            e.moveTo(player.getX(), player.getY() + player.getBbHeight() + 0.1, player.getZ(), player.getYRot(), 0.0F);
            level.addFreshEntity(e);
            ACTIVE.put(player.getUUID(), e.getUUID());
        }
    }

    public static void dismiss(ServerPlayer player) {
        PaqganeEntity e = getEntity(player);
        if (e != null) {
            e.discard();
        }
        ACTIVE.remove(player.getUUID());
    }

    public static void forget(@Nullable UUID ownerId) {
        if (ownerId != null) {
            ACTIVE.remove(ownerId);
        }
    }

    public static void say(@Nullable MinecraftServer server, String text) {
        if (server != null && text != null && !text.isBlank()) {
            server.getPlayerList().broadcastSystemMessage(Component.literal("<Paqgane> " + text), false);
        }
    }

    public static void whisper(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal("<Paqgane> " + text));
    }
}
