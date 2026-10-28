package com.paq.paqgane.monitor;

import com.paq.paqgane.PaqganeConfig;
import com.paq.paqgane.PaqganeManager;
import com.paq.paqgane.PaqganeMod;
import com.paq.paqgane.sentient.SentientManager;
import com.paq.paqgane.action.Punishments;
import com.paq.paqgane.ai.Brain;
import com.paq.paqgane.stash.StashManager;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityTeleportEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingUseTotemEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

@Mod.EventBusSubscriber(modid = "paqgane")
public class RuleMonitor {
    private static final long ALERT_COOLDOWN_MS = 120_000L;
    private static final long SPAWN_KILL_WINDOW_MS = 15_000L;
    private static final long BACK_WINDOW_MS = 120_000L;

    private static final Map<UUID, Long> COMBAT_UNTIL = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> ARRIVED_AT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> KILLED_BY_PLAYER_AT = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_ALERT = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<Long>> CHAT_TIMES = new ConcurrentHashMap<>();
    private static final Map<UUID, String> LAST_CHAT = new ConcurrentHashMap<>();

    public enum Severity {
        LIGHT,
        SERIOUS,
        REVIEW
    }

    private static boolean enabled() {
        return PaqganeConfig.MONITOR_ENABLED.get();
    }

    /** Whether this player is currently being watched by the monitors. */
    public static boolean watched(ServerPlayer p) {
        if (!enabled() || p.isCreative() || p.isSpectator() || SentientManager.isBot(p)) {
            return false;
        }
        return PaqganeConfig.MONITOR_OPS.get() || !p.hasPermissions(PaqganeConfig.OP_LEVEL.get());
    }

    public static boolean inCombat(ServerPlayer p) {
        return COMBAT_UNTIL.getOrDefault(p.getUUID(), 0L) > System.currentTimeMillis();
    }

    private static void tagCombat(ServerPlayer p) {
        COMBAT_UNTIL.put(p.getUUID(), System.currentTimeMillis() + PaqganeConfig.COMBAT_SECONDS.get() * 1000L);
    }

    public static void flag(ServerPlayer p, String key, String rule, String detail, Severity sev) {
        String cooldownKey = p.getUUID() + ":" + key;
        long now = System.currentTimeMillis();
        Long last = LAST_ALERT.get(cooldownKey);
        if (last != null && now - last < ALERT_COOLDOWN_MS) {
            return;
        }
        LAST_ALERT.put(cooldownKey, now);

        String name = p.getGameProfile().getName();
        int warnings = WarningStore.count(p.getUUID());
        String where = p.level().dimension().location() + " " + p.blockPosition().getX() + " "
                + p.blockPosition().getY() + " " + p.blockPosition().getZ();

        switch (sev) {
            case LIGHT -> {
                String kind = Punishments.randomKind();
                Punishments.apply(p, kind);
                PaqganeManager.say(p.getServer(), name + ", " + Punishments.randomLine());
                Discord.send("**rule alert (light, funny punishment: " + kind + ")**\nplayer: " + name
                        + "\nrule: " + rule + "\ndetails: " + detail + "\nwhere: " + where);
            }
            case SERIOUS -> {
                PaqganeManager.whisper(p, "that breaks the rules (" + rule + "). admins got pinged, on foenem");
                notifyOps(p, "[Paqgane alert] " + name + ": " + rule + " - " + detail);
                Discord.send("**rule alert (serious)**\nplayer: " + name + "\nrule: " + rule + "\ndetails: " + detail
                        + "\nwhere: " + where + "\nwarnings on record: " + warnings + " (" + WarningStore.nextStep(warnings)
                        + ")\nno warning issued yet, an op can tell Paqgane to warn them");
                if (PaqganeConfig.AUTO_WARN.get()) {
                    WarningStore.warn(p.getServer(), p, rule, "Paqgane (auto)");
                }
            }
            case REVIEW -> {
                notifyOps(p, "[Paqgane review] " + name + ": " + rule + " - " + detail);
                Discord.send("**needs a look (not proven)**\nplayer: " + name + "\nrule: " + rule + "\ndetails: " + detail
                        + "\nwhere: " + where);
            }
        }
        PaqganeMod.LOGGER.info("[Paqgane monitor] {} {} {} ({})", sev, name, rule, detail);
    }

    public static void notifyOps(ServerPlayer about, String text) {
        for (ServerPlayer op : about.getServer().getPlayerList().getPlayers()) {
            if (op.hasPermissions(PaqganeConfig.OP_LEVEL.get())) {
                op.sendSystemMessage(Component.literal(text));
            }
        }
    }

    @SubscribeEvent
    public static void playerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER) {
            return;
        }
        if (!(event.player instanceof ServerPlayer p) || !watched(p)) {
            return;
        }
        if (p.tickCount % 10 == 0 && p.isFallFlying() && inCombat(p)) {
            flag(p, "elytra", "elytra in pvp", "flying with an elytra while in combat", Severity.LIGHT);
        }
        if (p.tickCount % PaqganeConfig.MONITOR_INTERVAL_TICKS.get() == 0) {
            if (PaqganeConfig.END_BANNED.get() && p.level().dimension() == Level.END) {
                flag(p, "end", "the End is banned until the event", "player is in the End", Severity.SERIOUS);
            }
            scanInventory(p);
        }
    }

    private static void scanInventory(ServerPlayer p) {
        Set<String> banned = new HashSet<>();
        for (String s : PaqganeConfig.BANNED_ITEMS.get()) {
            banned.add(s.toLowerCase(Locale.ROOT));
        }
        int[] gapple = {0};
        int[] egapple = {0};
        String[] bannedFound = {null};

        scanContainer(p.getInventory(), banned, gapple, egapple, bannedFound);
        scanContainer(p.getEnderChestInventory(), banned, gapple, egapple, bannedFound);
        Container stash = StashManager.peek(p.getServer(), p.getUUID());
        if (stash != null) {
            scanContainer(stash, banned, gapple, egapple, bannedFound);
        }

        if (bannedFound[0] != null) {
            flag(p, "banned_item", "hard banned item", "carrying " + bannedFound[0], Severity.SERIOUS);
        }
        if (gapple[0] > PaqganeConfig.GOLDEN_APPLE_CAP.get()) {
            flag(p, "gapple_cap", "golden apple cap",
                    gapple[0] + " golden apples (cap " + PaqganeConfig.GOLDEN_APPLE_CAP.get() + ")", Severity.SERIOUS);
        }
        if (egapple[0] > PaqganeConfig.ENCHANTED_GOLDEN_APPLE_CAP.get()) {
            flag(p, "egapple_cap", "enchanted golden apple cap",
                    egapple[0] + " enchanted golden apples (cap " + PaqganeConfig.ENCHANTED_GOLDEN_APPLE_CAP.get() + ")",
                    Severity.SERIOUS);
        }
    }

    private static void scanContainer(Container c, Set<String> banned, int[] gapple, int[] egapple, String[] bannedFound) {
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            if (s.is(Items.GOLDEN_APPLE)) {
                gapple[0] += s.getCount();
            } else if (s.is(Items.ENCHANTED_GOLDEN_APPLE)) {
                egapple[0] += s.getCount();
            }
            if (bannedFound[0] == null) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(s.getItem());
                if (id != null && banned.contains(id.toString())) {
                    bannedFound[0] = id.toString();
                }
            }
        }
    }

    @SubscribeEvent
    public static void hurt(LivingHurtEvent event) {
        if (event.getEntity() instanceof ServerPlayer victim
                && event.getSource().getEntity() instanceof ServerPlayer attacker
                && victim != attacker) {
            tagCombat(victim);
            tagCombat(attacker);
        }
    }

    @SubscribeEvent
    public static void death(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) {
            return;
        }
        COMBAT_UNTIL.remove(victim.getUUID());
        if (event.getSource().getEntity() instanceof ServerPlayer killer && killer != victim) {
            KILLED_BY_PLAYER_AT.put(victim.getUUID(), System.currentTimeMillis());
            Long arrived = ARRIVED_AT.get(victim.getUUID());
            if (watched(killer) && arrived != null && System.currentTimeMillis() - arrived < SPAWN_KILL_WINDOW_MS) {
                flag(killer, "spawn_camp", "spawn camping",
                        "killed " + victim.getGameProfile().getName() + " within 15s of them arriving at spawn",
                        Severity.SERIOUS);
            }
        }
    }

    @SubscribeEvent
    public static void respawn(PlayerEvent.PlayerRespawnEvent event) {
        ARRIVED_AT.put(event.getEntity().getUUID(), System.currentTimeMillis());
    }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        ARRIVED_AT.put(event.getEntity().getUUID(), System.currentTimeMillis());
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) {
            return;
        }
        if (watched(p) && inCombat(p)) {
            flag(p, "combat_log", "combat logging", "left the server while in combat", Severity.SERIOUS);
        }
        UUID id = p.getUUID();
        COMBAT_UNTIL.remove(id);
        CHAT_TIMES.remove(id);
        LAST_CHAT.remove(id);
        XrayDetector.forget(id);
        ProblemDetector.forget(id);
        GriefMonitor.forget(id);
        PaqganeManager.dismiss(p);
        Brain.forget(id);
    }

    @SubscribeEvent
    public static void pearl(EntityTeleportEvent.EnderPearl event) {
        ServerPlayer p = event.getPlayer();
        if (watched(p) && inCombat(p)) {
            flag(p, "pearl", "ender pearl in pvp", "threw an ender pearl while in combat", Severity.LIGHT);
        }
    }

    @SubscribeEvent
    public static void totem(LivingUseTotemEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && watched(p)) {
            flag(p, "totem", "totems of undying are banned", "a totem popped", Severity.SERIOUS);
        }
    }

    @SubscribeEvent
    public static void riptide(LivingEntityUseItemEvent.Stop event) {
        if (event.getEntity() instanceof ServerPlayer p && watched(p) && inCombat(p)
                && event.getItem().is(Items.TRIDENT)
                && EnchantmentHelper.getRiptide(event.getItem()) > 0) {
            flag(p, "riptide", "riptide trident in pvp", "used riptide while in combat", Severity.LIGHT);
        }
    }

    @SubscribeEvent
    public static void command(CommandEvent event) {
        if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer p) || !watched(p)) {
            return;
        }
        String raw = event.getParseResults().getReader().getString().trim();
        if (raw.startsWith("/")) {
            raw = raw.substring(1);
        }
        String name = raw.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (name.contains(":")) {
            name = name.substring(name.indexOf(':') + 1);
        }

        if (inCombat(p)) {
            for (String blocked : PaqganeConfig.COMBAT_BLOCKED_COMMANDS.get()) {
                if (name.equals(blocked.toLowerCase(Locale.ROOT))) {
                    event.setCanceled(true);
                    PaqganeManager.whisper(p, "not in combat bro");
                    flag(p, "combat_cmd", "teleport command in combat", "tried /" + name + " while in combat", Severity.LIGHT);
                    return;
                }
            }
        }
        if (name.equals("back")) {
            Long killedAt = KILLED_BY_PLAYER_AT.get(p.getUUID());
            if (killedAt != null && System.currentTimeMillis() - killedAt < BACK_WINDOW_MS) {
                event.setCanceled(true);
                PaqganeManager.whisper(p, "no /back after a player kill, thats a rule");
                flag(p, "back_after_kill", "/back after a player kill", "tried /back right after dying to a player", Severity.LIGHT);
            }
        }
    }

    /** Called from the chat handler. Returns true when the message was blocked. */
    public static boolean onChat(ServerChatEvent event) {
        ServerPlayer p = event.getPlayer();
        if (!watched(p)) {
            return false;
        }
        String text = event.getRawText();
        String lower = text.toLowerCase(Locale.ROOT);

        for (String w : PaqganeConfig.BAD_WORDS.get()) {
            if (!w.isBlank() && lower.contains(w.toLowerCase(Locale.ROOT))) {
                event.setCanceled(true);
                flag(p, "bad_word", "offensive language", "message blocked, contained a listed word", Severity.SERIOUS);
                return true;
            }
        }

        long now = System.currentTimeMillis();
        int mentions = 0;
        for (char c : text.toCharArray()) {
            if (c == '@') {
                mentions++;
            }
        }
        if (mentions >= 4) {
            flag(p, "mass_ping", "mass pings", mentions + " @ mentions in one message", Severity.LIGHT);
        }
        if (text.length() > 280) {
            flag(p, "wall", "wall of text", "message was " + text.length() + " characters", Severity.LIGHT);
        }

        Deque<Long> times = CHAT_TIMES.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>());
        String prev = LAST_CHAT.put(p.getUUID(), lower);
        synchronized (times) {
            if (prev != null && prev.equals(lower)) {
                times.add(now);
            } else {
                times.clear();
            }
            while (!times.isEmpty() && now - times.peekFirst() > 10_000L) {
                times.poll();
            }
            if (times.size() >= 3) {
                times.clear();
                flag(p, "spam", "spam", "same message repeated 3+ times in 10 seconds", Severity.LIGHT);
            }
        }
        return false;
    }
}
