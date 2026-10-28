package com.paq.paqgane.sentient;

import com.mojang.authlib.GameProfile;
import com.paq.paqgane.PaqganeConfig;
import com.paq.paqgane.PaqganeManager;
import com.paq.paqgane.PaqganeMod;
import com.paq.paqgane.monitor.Discord;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Turns sentient mode on and off. While it is on and at least one real player is online, exactly one fake player
 * (SentientEntity) is in the world. It is removed when the last real player leaves and comes back when someone joins.
 * Only its owner (the player who turned it on) can give it orders.
 */
@Mod.EventBusSubscriber(modid = "paqgane")
public class SentientManager {
    private static final String DATA_NAME = "paqgane_sentient";

    private static long respawnAtMs = 0;
    private static int tickCounter = 0;

    public static SentientData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(SentientData::load, SentientData::new, DATA_NAME);
    }

    public static boolean isBot(@Nullable Entity e) {
        return e instanceof SentientEntity;
    }

    public static boolean isOwner(ServerPlayer p) {
        MinecraftServer server = p.getServer();
        if (server == null || isBot(p)) {
            return false;
        }
        return p.getUUID().equals(data(server).admin);
    }

    public static boolean isEnabled(@Nullable MinecraftServer server) {
        return server != null && PaqganeConfig.SENTIENT_ALLOWED.get() && data(server).enabled;
    }

    @Nullable
    public static SentientEntity entity(@Nullable MinecraftServer server) {
        if (server == null) {
            return null;
        }
        ServerPlayer p = server.getPlayerList().getPlayer(SentientEntity.BOT_ID);
        if (p instanceof SentientEntity s && s.isAlive() && !s.dying) {
            return s;
        }
        return null;
    }

    /** On and with a living body in the world. */
    public static boolean isActive(@Nullable MinecraftServer server) {
        return isEnabled(server) && entity(server) != null;
    }

    public static List<ServerPlayer> realPlayers(MinecraftServer server) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!isBot(p)) {
                out.add(p);
            }
        }
        return out;
    }

    public static String status(MinecraftServer server) {
        if (!PaqganeConfig.SENTIENT_ALLOWED.get()) {
            return "sentient mode is turned off in the config";
        }
        Social.free = data(server).freeWill && data(server).enabled;
        if (!data(server).enabled) {
            return "sentient mode: off";
        }
        SentientEntity e = entity(server);
        if (e == null) {
            long left = Math.max(0, (respawnAtMs - System.currentTimeMillis()) / 1000);
            return "sentient mode: on, body not in the world right now (comes back when a player is online"
                    + (left > 0 ? ", respawn timer " + left + "s" : "") + ")";
        }
        return "sentient mode: on, health " + Math.round(e.getHealth()) + "/" + Math.round(e.getMaxHealth())
                + ", food " + e.getFoodData().getFoodLevel() + "/20"
                + ", " + (e.usesRealCursedFate() ? e.cursedFateStatus() : "cursed energy " + Math.round(e.cursedEnergy()) + "/100") + ", " + e.describeIntent()
                + ", at " + e.level().dimension().location() + " " + e.blockPosition().getX() + " "
                + e.blockPosition().getY() + " " + e.blockPosition().getZ();
    }

    /** Returns a short message for the person who asked. */
    public static String setEnabled(MinecraftServer server, ServerPlayer admin, boolean on) {
        if (!PaqganeConfig.SENTIENT_ALLOWED.get()) {
            return "sentient mode is turned off in the config";
        }
        SentientData d = data(server);
        if (on) {
            if (d.enabled && entity(server) != null) {
                return "my body is already out there. use /paqgane sentient recall to pull it to you";
            }
            if (!d.enabled) {
                d.admin = admin.getUUID();
            }
            d.enabled = true;
            d.setDirty();
            if (spawn(server, admin) == null) {
                return "tried to spawn my body but it failed, check the server log";
            }
            PaqganeManager.say(server, "ok im out. im doing my own thing now, on foenem");
            Discord.send("**sentient mode on** turned on by " + admin.getGameProfile().getName()
                    + ". it takes orders only from " + admin.getGameProfile().getName());
            return "sentient mode on (you are its owner, only you can give it orders)";
        }
        d.enabled = false;
        d.setDirty();
        removeBot(server, "sentient mode off");
        PaqganeManager.say(server, "aight im going back in. later");
        Discord.send("**sentient mode off** turned off by " + admin.getGameProfile().getName());
        return "sentient mode off, my inventory is saved";
    }

    /** Turns quest grinding on or off. Returns a short message for whoever asked. */
    public static String setQuests(MinecraftServer server, boolean on) {
        SentientData d = data(server);
        d.quests = on;
        d.setDirty();
        SentientEntity e = entity(server);
        if (e != null) {
            e.setQuestMode(on);
        }
        if (on && !com.paq.paqgane.sentient.QuestBot.loaded()) {
            return "quest mode saved as on, but ftb quests is not installed";
        }
        return on ? "quest mode on, it will grind simple ftb quests when it has nothing else to do" : "quest mode off";
    }

    /** Free will on or off. Op command only, always reported to Discord. */
    public static String setFreeWill(MinecraftServer server, ServerPlayer by, boolean on) {
        SentientData d = data(server);
        d.freeWill = on;
        d.setDirty();
        Social.free = on;
        Discord.send("**sentient free will " + (on ? "ON" : "off") + "** set by " + by.getGameProfile().getName()
                + (on ? ". the body may now fight anyone, including the owner, with no safety rules." : "."));
        return on ? "free will on: it does whatever it wants, including fighting anyone (even you). turn it off with /paqgane sentient freewill off"
                : "free will off, the normal rules are back";
    }

    public static String questStatus(MinecraftServer server) {
        SentientEntity e = entity(server);
        return e == null ? "the sentient body is not out right now (quest mode is " + (data(server).quests ? "on" : "off") + ")" : e.questStatus();
    }

    public static String recall(MinecraftServer server, ServerPlayer admin) {
        SentientEntity e = entity(server);
        if (e == null) {
            if (!isEnabled(server)) {
                return "sentient mode is off";
            }
            if (spawn(server, admin) == null) {
                return "tried to spawn my body but it failed, check the server log";
            }
            return "my body was gone, spawned it next to you";
        }
        e.stopWalking();
        e.teleportTo(admin.serverLevel(), admin.getX() + 1.0, admin.getY(), admin.getZ() + 1.0, e.getYRot(), 0.0F);
        return "pulled my body to you";
    }

    /** Creates the fake player and puts it in the world. Returns null if that failed. */
    @Nullable
    private static SentientEntity spawn(MinecraftServer server, ServerPlayer near) {
        if (entity(server) != null) {
            return entity(server);
        }
        try {
            ServerLevel level = near.serverLevel();
            GameProfile profile = new GameProfile(SentientEntity.BOT_ID, SentientEntity.NAME);
            SentientEntity bot = new SentientEntity(server, level, profile);

            // a connection nobody listens to: everything the server sends it is thrown away
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                    ReferenceCountUtil.release(msg);
                    promise.setSuccess();
                }
            }, connection);

            server.getPlayerList().placeNewPlayer(connection, bot);
            bot.setGameMode(GameType.SURVIVAL);
            bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(PaqganeConfig.SENTIENT_MAX_HEALTH.get());
            bot.setQuestMode(data(server).quests);
            Social.free = data(server).freeWill;

            SentientData d = data(server);
            if (!d.spawned) {
                Vec3 look = near.getLookAngle();
                bot.teleportTo(near.serverLevel(), near.getX() + look.x * 2.0, near.getY(), near.getZ() + look.z * 2.0,
                        near.getYRot() + 180.0F, 0.0F);
                bot.setHealth(bot.getMaxHealth());
                d.spawned = true;
                d.setDirty();
            } else if (bot.getHealth() <= 0.0F || bot.getHealth() > bot.getMaxHealth()) {
                bot.setHealth(bot.getMaxHealth());
            }
            return bot;
        } catch (Throwable t) {
            PaqganeMod.LOGGER.error("Paqgane: could not create the sentient player body", t);
            return null;
        }
    }

    private static void removeBot(MinecraftServer server, String reason) {
        ServerPlayer p = server.getPlayerList().getPlayer(SentientEntity.BOT_ID);
        if (p instanceof SentientEntity s && s.connection != null) {
            s.connection.onDisconnect(Component.literal(reason));
        }
    }

    public static void onDeath(SentientEntity e, DamageSource source) {
        MinecraftServer server = e.getServer();
        if (server == null) {
            return;
        }
        Social.onBotKilled(e, source);
        respawnAtMs = System.currentTimeMillis() + PaqganeConfig.SENTIENT_RESPAWN_SECONDS.get() * 1000L;
        String by = source.getEntity() != null ? source.getEntity().getName().getString() : source.getMsgId();
        PaqganeManager.say(server, "i died. back in a bit on foenem");
        Discord.send("**sentient body died** killed by " + by + " at " + e.level().dimension().location() + " "
                + e.blockPosition().getX() + " " + e.blockPosition().getY() + " " + e.blockPosition().getZ()
                + ". it respawns in " + PaqganeConfig.SENTIENT_RESPAWN_SECONDS.get() + "s");
    }

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++tickCounter < 20) {
            return;
        }
        tickCounter = 0;
        MinecraftServer server = event.getServer();
        if (!isEnabled(server)) {
            if (server.getPlayerList().getPlayer(SentientEntity.BOT_ID) != null) {
                removeBot(server, "sentient mode off");
            }
            return;
        }
        List<ServerPlayer> real = realPlayers(server);
        SentientEntity body = entity(server);
        if (body == null) {
            if (real.isEmpty() || System.currentTimeMillis() < respawnAtMs) {
                return;
            }
            if (server.getPlayerList().getPlayer(SentientEntity.BOT_ID) != null) {
                return; // still being removed after dying
            }
            ServerPlayer near = data(server).admin == null ? null : server.getPlayerList().getPlayer(data(server).admin);
            if (near == null || isBot(near)) {
                near = real.get(0);
            }
            spawn(server, near);
            return;
        }
        if (real.isEmpty()) {
            removeBot(server, "nobody is online");
            return;
        }
        SentientBrain.tick(server, body);
        Social.serverTick(server, body);
    }
}
