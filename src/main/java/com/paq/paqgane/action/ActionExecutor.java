package com.paq.paqgane.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.paq.paqgane.PaqganeConfig;
import com.paq.paqgane.PaqganeEntity;
import com.paq.paqgane.PaqganeManager;
import com.paq.paqgane.PaqganeMod;
import com.paq.paqgane.monitor.Discord;
import com.paq.paqgane.monitor.WarningStore;
import com.paq.paqgane.sentient.SentientActions;
import com.paq.paqgane.sentient.SentientEntity;
import com.paq.paqgane.sentient.SentientManager;
import com.paq.paqgane.stash.StashManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;

public class ActionExecutor {
    private static final int MAX_ACTIONS = 3;
    private static final int SEARCH_RADIUS = 16;
    private static final Map<UUID, Long> LAST_PRIVILEGE_ALERT = new ConcurrentHashMap<>();

    public static void run(ServerPlayer player, JsonArray actions, boolean op) {
        int n = 0;
        for (JsonElement el : actions) {
            if (n++ >= MAX_ACTIONS || !el.isJsonObject()) {
                break;
            }
            JsonObject a = el.getAsJsonObject();
            String type = str(a, "type");
            if (type == null) {
                continue;
            }
            try {
                switch (type.toLowerCase()) {
                    case "follow" -> {
                        if (PaqganeManager.getEntity(player) != null) {
                            withEntity(player, PaqganeEntity::follow);
                        } else {
                            SentientActions.run(player, "sentient_follow", a, op);
                        }
                    }
                    case "stay" -> {
                        if (PaqganeManager.getEntity(player) != null) {
                            withEntity(player, PaqganeEntity::stay);
                        } else {
                            SentientActions.run(player, "sentient_stay", a, op);
                        }
                    }
                    case "mine" -> mine(player, a, op);
                    case "place" -> place(player, a, op);
                    case "cf_read" -> CursedFate.read(player, str(a, "variable"));
                    case "cf_ability" -> CursedFate.pressAbility(player, intOf(a, "slot", 0));
                    case "open_stash" -> StashManager.openOwn(player);
                    case "give" -> {
                        if (!deny(player, op, type)) {
                            give(player, a);
                        }
                    }
                    case "command" -> {
                        if (!deny(player, op, type)) {
                            CommandRunner.request(player, str(a, "command"));
                        }
                    }
                    case "punish" -> {
                        if (!deny(player, op, type)) {
                            ServerPlayer target = findPlayer(player, str(a, "player"));
                            if (target != null && Punishments.apply(target, str(a, "kind"))) {
                                Discord.send("**punishment** " + player.getGameProfile().getName()
                                        + " had Paqgane punish " + target.getGameProfile().getName()
                                        + " (" + str(a, "kind") + ")");
                            }
                        }
                    }
                    case "warn" -> {
                        if (!deny(player, op, type)) {
                            ServerPlayer target = findPlayer(player, str(a, "player"));
                            if (target != null) {
                                WarningStore.warn(player.getServer(), target, str(a, "reason"), player.getGameProfile().getName());
                            }
                        }
                    }
                    case "sentient_mode" -> {
                        if (!deny(player, op, type)) {
                            boolean on = !a.has("on") || a.get("on").getAsBoolean();
                            PaqganeManager.whisper(player, SentientManager.setEnabled(player.getServer(), player, on));
                        }
                    }
                    case "sentient_follow", "sentient_come", "sentient_stay", "sentient_wander",
                         "sentient_mine", "sentient_craft", "sentient_move", "sentient_ability", "sentient_quests",
                         "sentient_declare", "sentient_feel" -> SentientActions.run(player, type.toLowerCase(), a, op);
                    case "sentient_give" -> {
                        if (!deny(player, op, type)) {
                            SentientActions.run(player, "sentient_give", a, op);
                        }
                    }
                    default -> {
                    }
                }
            } catch (Exception e) {
                PaqganeMod.LOGGER.warn("Action {} failed", type, e);
            }
        }
    }

    /**
     * Permission is decided here, in code, never by the AI. Anything a regular player's chat gets the AI to
     * try that is ops only is refused, and logged so admins can see attempts.
     */
    private static boolean deny(ServerPlayer p, boolean op, String type) {
        if (!op) {
            PaqganeManager.whisper(p, "thats ops only bro");
            long now = System.currentTimeMillis();
            Long last = LAST_PRIVILEGE_ALERT.get(p.getUUID());
            if (last == null || now - last > 120_000L) {
                LAST_PRIVILEGE_ALERT.put(p.getUUID(), now);
                PaqganeMod.LOGGER.info("[Paqgane] refused ops-only action '{}' for {}", type, p.getGameProfile().getName());
                Discord.send("**privilege attempt (refused)** " + p.getGameProfile().getName()
                        + " got Paqgane to try the ops-only action `" + type + "`. nothing ran.");
            }
        }
        return !op;
    }

    private static void withEntity(ServerPlayer p, Consumer<PaqganeEntity> c) {
        PaqganeEntity e = PaqganeManager.getEntity(p);
        if (e != null) {
            c.accept(e);
        }
    }

    private static void mine(ServerPlayer p, JsonObject a, boolean op) {
        PaqganeEntity e = PaqganeManager.getEntity(p);
        if (e == null) {
            SentientActions.run(p, "sentient_mine", a, op);
            return;
        }
        Block block = block(str(a, "block"));
        if (block == null || block == Blocks.AIR || block == Blocks.BEDROCK) {
            return;
        }
        int count = Math.max(1, Math.min(intOf(a, "count", 1), PaqganeConfig.MAX_BLOCKS_PER_ACTION.get()));
        BlockPos center = p.blockPosition();
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-SEARCH_RADIUS, -8, -SEARCH_RADIUS),
                center.offset(SEARCH_RADIUS, 8, SEARCH_RADIUS))) {
            if (p.level().getBlockState(pos).getBlock() == block) {
                found.add(pos.immutable());
            }
        }
        if (found.isEmpty()) {
            PaqganeManager.say(p.getServer(), "cant find any " + block.getName().getString().toLowerCase() + " near you bro");
            return;
        }
        found.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
        for (int i = 0; i < Math.min(count, found.size()); i++) {
            e.queue(new PaqganeEntity.Task(PaqganeEntity.TaskKind.MINE, found.get(i), block, op));
        }
    }

    private static void place(ServerPlayer p, JsonObject a, boolean op) {
        PaqganeEntity e = PaqganeManager.getEntity(p);
        Block block = block(str(a, "block"));
        if (e == null || block == null || block == Blocks.AIR || block.asItem() == Items.AIR) {
            return;
        }
        int count = Math.max(1, Math.min(intOf(a, "count", 1), PaqganeConfig.MAX_BLOCKS_PER_ACTION.get()));
        if (!op) {
            int have = 0;
            for (ItemStack s : p.getInventory().items) {
                if (s.getItem() == block.asItem()) {
                    have += s.getCount();
                }
            }
            if (have == 0) {
                PaqganeManager.say(p.getServer(), "you dont have any of that in your inventory bro");
                return;
            }
            count = Math.min(count, have);
        }

        HitResult hit = p.pick(20.0, 1.0F, false);
        BlockPos start;
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockHitResult bhr = (BlockHitResult) hit;
            start = bhr.getBlockPos().relative(bhr.getDirection());
        } else {
            start = p.blockPosition().relative(p.getDirection(), 2);
        }
        Direction facing = p.getDirection();
        String dirName = str(a, "direction");
        String d = dirName == null ? "forward" : dirName.toLowerCase();
        Direction dir = switch (d) {
            case "up" -> Direction.UP;
            case "down" -> Direction.DOWN;
            case "left" -> facing.getCounterClockWise();
            case "right" -> facing.getClockWise();
            default -> facing;
        };
        for (int i = 0; i < count; i++) {
            e.queue(new PaqganeEntity.Task(PaqganeEntity.TaskKind.PLACE, start.relative(dir, i), block, op));
        }
    }

    private static void give(ServerPlayer op, JsonObject a) {
        ResourceLocation id = ResourceLocation.tryParse(String.valueOf(str(a, "item")));
        Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
        if (item == null || item == Items.AIR) {
            PaqganeManager.whisper(op, "dont know that item");
            return;
        }
        int count = Math.max(1, Math.min(intOf(a, "count", 1), 2304));
        ServerPlayer target = findPlayer(op, str(a, "player"));
        if (target == null) {
            target = op;
        }
        int remaining = count;
        while (remaining > 0) {
            int n = Math.min(remaining, item.getMaxStackSize());
            ItemHandlerHelper.giveItemToPlayer(target, new ItemStack(item, n));
            remaining -= n;
        }
        Discord.send("**give audit** " + op.getGameProfile().getName() + " had Paqgane give " + count + "x " + id
                + " to " + target.getGameProfile().getName());
    }

    @Nullable
    static ServerPlayer findPlayer(ServerPlayer asker, @Nullable String name) {
        return name == null || name.isBlank() ? null : asker.getServer().getPlayerList().getPlayerByName(name);
    }

    @Nullable
    public static Block block(@Nullable String id) {
        if (id == null) {
            return null;
        }
        ResourceLocation rl = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        return rl != null && ForgeRegistries.BLOCKS.containsKey(rl) ? ForgeRegistries.BLOCKS.getValue(rl) : null;
    }

    @Nullable
    public static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    public static int intOf(JsonObject o, String key, int def) {
        try {
            JsonElement e = o.get(key);
            return e == null || e.isJsonNull() ? def : e.getAsInt();
        } catch (Exception ex) {
            return def;
        }
    }
}
