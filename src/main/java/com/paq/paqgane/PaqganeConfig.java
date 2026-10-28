package com.paq.paqgane;

import java.util.List;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.BooleanValue;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;
import net.minecraftforge.common.ForgeConfigSpec.IntValue;

public class PaqganeConfig {
    public static final ForgeConfigSpec SPEC;

    // ai
    public static final ConfigValue<String> API_KEY;
    public static final ConfigValue<String> API_URL;
    public static final ConfigValue<String> MODEL;
    public static final ConfigValue<String> FALLBACK_MODEL;
    public static final ConfigValue<String> REASONING_EFFORT;
    public static final IntValue MAX_TOKENS;
    public static final IntValue REQUESTS_PER_MINUTE;
    public static final IntValue MAX_QUEUE;
    public static final IntValue HISTORY_MESSAGES;

    // chat
    public static final IntValue COOLDOWN_SECONDS;
    public static final BooleanValue OPS_BYPASS_COOLDOWN;
    public static final IntValue OP_LEVEL;
    public static final IntValue MAX_INPUT_CHARS;
    public static final IntValue MAX_BLOCKS_PER_ACTION;
    public static final BooleanValue CURSED_FATE_ABILITIES;

    // op commands
    public static final ConfigValue<List<? extends String>> BLOCKED_COMMANDS;
    public static final BooleanValue CONFIRM_DESTRUCTIVE;
    public static final ConfigValue<List<? extends String>> DESTRUCTIVE_COMMANDS;

    // rule monitor
    public static final BooleanValue MONITOR_ENABLED;
    public static final BooleanValue MONITOR_OPS;
    public static final IntValue MONITOR_INTERVAL_TICKS;
    public static final IntValue COMBAT_SECONDS;
    public static final BooleanValue END_BANNED;
    public static final ConfigValue<List<? extends String>> BANNED_ITEMS;
    public static final ConfigValue<List<? extends String>> COMBAT_BLOCKED_COMMANDS;
    public static final ConfigValue<List<? extends String>> BAD_WORDS;
    public static final IntValue GOLDEN_APPLE_CAP;
    public static final IntValue ENCHANTED_GOLDEN_APPLE_CAP;
    public static final BooleanValue AUTO_WARN;

    // xray
    public static final BooleanValue XRAY_ENABLED;
    public static final ConfigValue<List<? extends String>> XRAY_ORES;
    public static final IntValue XRAY_WINDOW_BLOCKS;
    public static final IntValue XRAY_WINDOW_MINUTES;
    public static final IntValue XRAY_DIAMOND_ALERT;
    public static final IntValue XRAY_DEBRIS_ALERT;
    public static final IntValue XRAY_HIDDEN_ALERT;
    public static final IntValue XRAY_SPEED_ORES;
    public static final IntValue XRAY_SPEED_SECONDS;
    public static final IntValue XRAY_SPEED_HIDDEN;
    public static final IntValue XRAY_COOLDOWN_MINUTES;

    // problem detection
    public static final BooleanValue PROBLEMS_ENABLED;
    public static final BooleanValue PROBLEMS_SPEAK;
    public static final BooleanValue PROBLEMS_DISCORD;
    public static final BooleanValue PROBLEM_STUCK;
    public static final IntValue STUCK_SECONDS;
    public static final BooleanValue PROBLEM_SUFFOCATE;
    public static final BooleanValue PROBLEM_DANGER;
    public static final IntValue DANGER_HEALTH;
    public static final BooleanValue PROBLEM_DEATHS;
    public static final IntValue DEATHS_COUNT;
    public static final IntValue DEATHS_WINDOW_MINUTES;
    public static final BooleanValue PROBLEM_STARVING;
    public static final BooleanValue PROBLEM_HELP_CHAT;
    public static final IntValue HELP_COOLDOWN_SECONDS;
    public static final IntValue HELP_MAX_PER_HOUR;

    // grief
    public static final BooleanValue GRIEF_ENABLED;
    public static final IntValue GRIEF_TNT;
    public static final IntValue GRIEF_LAVA;
    public static final IntValue GRIEF_BURST_COUNT;
    public static final IntValue GRIEF_BURST_SECONDS;

    // stash
    public static final BooleanValue STASH_ENABLED;
    public static final IntValue STASH_ROWS;
    public static final BooleanValue STASH_BLOCK_IN_COMBAT;

    // discord
    public static final ConfigValue<String> WEBHOOK_URL;

    // sentient
    public static final BooleanValue SENTIENT_ALLOWED;
    public static final IntValue SENTIENT_THINK_SECONDS;
    public static final IntValue SENTIENT_MIN_CHAT_SECONDS;
    public static final BooleanValue SENTIENT_KEEP_INVENTORY;
    public static final IntValue SENTIENT_RESPAWN_SECONDS;
    public static final BooleanValue SENTIENT_PICKUP;
    public static final BooleanValue SENTIENT_MINING;
    public static final BooleanValue SENTIENT_ACCEPT_GIFTS;
    public static final BooleanValue SENTIENT_MOVES_HIT_PLAYERS;
    public static final IntValue SENTIENT_MAX_HEALTH;
    public static final BooleanValue SENTIENT_REAL_CURSED_FATE;
    public static final BooleanValue SENTIENT_SOCIAL;
    public static final BooleanValue SENTIENT_HUNT_PLAYERS;
    public static final IntValue SENTIENT_SPAWN_SAFE_RADIUS;
    public static final IntValue SENTIENT_SOCIAL_DECAY_PER_HOUR;
    public static final BooleanValue SENTIENT_QUESTS;
    public static final BooleanValue SENTIENT_QUEST_COMMAND_REWARDS;
    public static final IntValue SENTIENT_QUEST_MAX_TRAVEL;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("AI settings (Groq free tier by default)").push("ai");
        API_KEY = b.comment("Groq API key. Get one free at console.groq.com. Leave empty to disable the AI.")
                .define("apiKey", "");
        API_URL = b.comment("OpenAI-compatible chat completions endpoint.")
                .define("apiUrl", "https://api.groq.com/openai/v1/chat/completions");
        MODEL = b.comment("Model id. If Groq retires this one, pick a current one from console.groq.com/docs/models")
                .define("model", "openai/gpt-oss-20b");
        FALLBACK_MODEL = b.comment("Used automatically when the main model is rate limited, missing or erroring. Each Groq model has its own rate limit. Leave empty to disable.")
                .define("fallbackModel", "openai/gpt-oss-120b");
        REASONING_EFFORT = b.comment("For reasoning models (gpt-oss, qwen3): low, medium or high. Low keeps replies fast and stops the model from using all its tokens thinking. Leave empty to not send it.")
                .define("reasoningEffort", "low");
        MAX_TOKENS = b.comment("Max tokens per reply. Reasoning models count their thinking in this, so keep it high (2000+).")
                .defineInRange("maxTokens", 2000, 200, 8000);
        REQUESTS_PER_MINUTE = b.comment("Paqgane spaces its AI calls so they never go faster than this. Groq free tier is about 30. 0 = no spacing.")
                .defineInRange("requestsPerMinute", 20, 0, 600);
        MAX_QUEUE = b.comment("How many AI requests can wait in line before Paqgane says it is busy.")
                .defineInRange("maxQueue", 12, 1, 100);
        HISTORY_MESSAGES = b.comment("How many past messages per player Paqgane remembers.")
                .defineInRange("historyMessages", 8, 0, 40);
        b.pop();

        b.comment("Chat and permissions").push("chat");
        COOLDOWN_SECONDS = b.comment("Seconds between messages a player can send to Paqgane.")
                .defineInRange("cooldownSeconds", 5, 0, 600);
        OPS_BYPASS_COOLDOWN = b.define("opsBypassCooldown", false);
        OP_LEVEL = b.comment("Permission level that counts as 'operator' for full access.")
                .defineInRange("opLevel", 2, 1, 4);
        MAX_INPUT_CHARS = b.comment("Player messages to Paqgane are cut to this length (saves tokens, limits prompt-injection text).")
                .defineInRange("maxInputChars", 300, 50, 2000);
        MAX_BLOCKS_PER_ACTION = b.comment("Safety cap on blocks mined or placed per request (stops server lag).")
                .defineInRange("maxBlocksPerAction", 128, 1, 4096);
        CURSED_FATE_ABILITIES = b.comment("Let Paqgane press Cursed Fate ability slots for a player who asks.")
                .define("cursedFateAbilities", true);
        b.pop();

        b.comment("Operator raw commands").push("opCommands");
        BLOCKED_COMMANDS = b.comment("Commands Paqgane will never run, even for ops.")
                .defineListAllowEmpty("blockedCommands", List.of("stop", "op", "deop", "ban", "ban-ip"), o -> o instanceof String);
        CONFIRM_DESTRUCTIVE = b.comment("Ask the op to confirm destructive commands before running them.")
                .define("confirmDestructive", true);
        DESTRUCTIVE_COMMANDS = b.comment("Command prefixes that need confirmation.")
                .defineListAllowEmpty("destructiveCommands", List.of(
                        "kill", "clear", "fill", "setblock", "kick", "gamerule", "pardon", "whitelist",
                        "function pvp:reset_round", "function pvp:announce_winner", "function pvp:round/stop"
                ), o -> o instanceof String);
        b.pop();

        b.comment("Rule monitor").push("monitor");
        MONITOR_ENABLED = b.define("enabled", true);
        MONITOR_OPS = b.comment("Also watch operators.").define("monitorOps", false);
        MONITOR_INTERVAL_TICKS = b.comment("How often the inventory/dimension scan runs per player (20 ticks = 1 second).")
                .defineInRange("scanIntervalTicks", 100, 20, 1200);
        COMBAT_SECONDS = b.comment("How long a player stays 'in combat' after a PvP hit.")
                .defineInRange("combatSeconds", 20, 5, 120);
        END_BANNED = b.comment("The End is banned until a scheduled event. Set false when the event starts.")
                .define("endBanned", true);
        BANNED_ITEMS = b.comment("Hard banned items (registry ids).")
                .defineListAllowEmpty("bannedItems", List.of(
                        "minecraft:shield", "minecraft:totem_of_undying", "minecraft:chorus_fruit", "minecraft:bedrock",
                        "minecraft:barrier", "minecraft:command_block", "minecraft:chain_command_block",
                        "minecraft:repeating_command_block", "minecraft:structure_block", "minecraft:jigsaw",
                        "minecraft:end_portal_frame", "minecraft:reinforced_deepslate"
                ), o -> o instanceof String);
        COMBAT_BLOCKED_COMMANDS = b.comment("Commands that must not be used while in combat (names, no slash).")
                .defineListAllowEmpty("combatBlockedCommands",
                        List.of("tpa", "tpaccept", "tpahere", "back", "home", "spawn", "warp"), o -> o instanceof String);
        BAD_WORDS = b.comment("Words that trigger a chat alert (racism, slurs, NSFW). Empty by default, fill it in.")
                .defineListAllowEmpty("badWords", List.of(), o -> o instanceof String);
        GOLDEN_APPLE_CAP = b.defineInRange("goldenAppleCap", 32, 0, 2304);
        ENCHANTED_GOLDEN_APPLE_CAP = b.defineInRange("enchantedGoldenAppleCap", 5, 0, 2304);
        AUTO_WARN = b.comment("If true, serious violations add a recorded warning automatically. Default false: Paqgane alerts admins and an op confirms the warning.")
                .define("autoWarn", false);
        b.pop();

        b.comment("X-ray detection (alert only, an admin makes the call). Every number here is a starting guess, tune it after you see real alerts.").push("xray");
        XRAY_ENABLED = b.define("enabled", true);
        XRAY_ORES = b.comment("Ores that count as 'rare' for the hidden-ore and speed checks (registry ids).")
                .defineListAllowEmpty("rareOres", List.of(
                        "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore", "minecraft:ancient_debris"
                ), o -> o instanceof String);
        XRAY_WINDOW_BLOCKS = b.comment("Each player's last N broken blocks are looked at.")
                .defineInRange("windowBlocks", 200, 50, 1000);
        XRAY_WINDOW_MINUTES = b.comment("Breaks older than this are forgotten.")
                .defineInRange("windowMinutes", 20, 1, 120);
        XRAY_DIAMOND_ALERT = b.comment("Alert at this many diamond ores in the window.")
                .defineInRange("diamondAlert", 5, 1, 100);
        XRAY_DEBRIS_ALERT = b.comment("Alert at this many ancient debris in the window.")
                .defineInRange("debrisAlert", 3, 1, 100);
        XRAY_HIDDEN_ALERT = b.comment("Alert at this many 'hidden hits' in the window. A hidden hit is a rare ore that had no cave, tunnel or water next to it before the player dug (so they could not have seen it), and that the player reached by turning their tunnel straight into it or by breaking it with no open side at all.")
                .defineInRange("hiddenAlert", 4, 1, 100);
        XRAY_SPEED_ORES = b.comment("Speed check: this many rare ores...").defineInRange("speedOres", 3, 1, 50);
        XRAY_SPEED_SECONDS = b.comment("...within this many seconds...").defineInRange("speedSeconds", 60, 5, 600);
        XRAY_SPEED_HIDDEN = b.comment("...with at least this many of them being hidden hits.").defineInRange("speedHidden", 2, 0, 50);
        XRAY_COOLDOWN_MINUTES = b.comment("Minutes before the same player can be alerted about x-ray again.")
                .defineInRange("alertCooldownMinutes", 10, 1, 240);
        b.pop();

        b.comment("Problem detection: Paqgane notices when a player is stuck, in danger, dying a lot or starving").push("problems");
        PROBLEMS_ENABLED = b.define("enabled", true);
        PROBLEMS_SPEAK = b.comment("Paqgane whispers a tip to the player (private, not global chat).")
                .define("speakToPlayer", true);
        PROBLEMS_DISCORD = b.comment("Post the serious ones (stuck, suffocating, repeated deaths) to the Discord webhook.")
                .define("discordAlerts", true);
        PROBLEM_STUCK = b.define("checkStuck", true);
        STUCK_SECONDS = b.comment("Not moving for this long while boxed in by solid blocks counts as stuck.")
                .defineInRange("stuckSeconds", 120, 20, 1800);
        PROBLEM_SUFFOCATE = b.define("checkSuffocating", true);
        PROBLEM_DANGER = b.comment("Low health in lava or fire, running out of air, falling into the void.")
                .define("checkDanger", true);
        DANGER_HEALTH = b.comment("Health points (2 per heart) at or under which danger tips start.")
                .defineInRange("dangerHealth", 8, 1, 20);
        PROBLEM_DEATHS = b.define("checkRepeatedDeaths", true);
        DEATHS_COUNT = b.defineInRange("deathsInWindow", 3, 2, 20);
        DEATHS_WINDOW_MINUTES = b.defineInRange("deathsWindowMinutes", 5, 1, 60);
        PROBLEM_STARVING = b.define("checkStarving", true);
        PROBLEM_HELP_CHAT = b.comment("Paqgane scans chat for people asking for help and answers if it is a real question. Costs a few AI calls.")
                .define("helpFromChat", true);
        HELP_COOLDOWN_SECONDS = b.comment("Per player, seconds between help answers.")
                .defineInRange("helpCooldownSeconds", 180, 10, 3600);
        HELP_MAX_PER_HOUR = b.comment("Server wide cap on chat help scans per hour (protects your free AI quota).")
                .defineInRange("helpMaxPerHour", 30, 0, 1000);
        b.pop();

        b.comment("Grief alerts (alert only)").push("grief");
        GRIEF_ENABLED = b.define("enabled", true);
        GRIEF_TNT = b.comment("TNT lit by one player this many times in 60 seconds.").defineInRange("tntAlert", 3, 1, 100);
        GRIEF_LAVA = b.comment("Lava buckets emptied by one player this many times in 60 seconds.").defineInRange("lavaAlert", 4, 1, 100);
        GRIEF_BURST_COUNT = b.comment("Built blocks (planks, bricks, glass, wool, concrete, stairs, slabs, doors, fences...) broken by one player...")
                .defineInRange("burstCount", 40, 5, 1000);
        GRIEF_BURST_SECONDS = b.comment("...within this many seconds.").defineInRange("burstSeconds", 20, 3, 300);
        b.pop();

        b.comment("Personal stash: private storage for every player, saved with the world").push("stash");
        STASH_ENABLED = b.define("enabled", true);
        STASH_ROWS = b.comment("Rows of 9 slots (1-6). Lowering this hides slots but never deletes items in them.")
                .defineInRange("rows", 3, 1, 6);
        STASH_BLOCK_IN_COMBAT = b.comment("Stops players opening their stash while in PvP combat.")
                .define("blockInCombat", true);
        b.pop();

        b.comment("Sentient mode: an op turns Paqgane into a walking body with its own inventory and moves").push("sentient");
        SENTIENT_ALLOWED = b.comment("Master switch for the whole feature.").define("allowed", true);
        SENTIENT_THINK_SECONDS = b.comment("How often the sentient body asks the AI what to do on its own. Higher = fewer AI calls.")
                .defineInRange("thinkSeconds", 45, 15, 1800);
        SENTIENT_MIN_CHAT_SECONDS = b.comment("Minimum seconds between things it says on its own.")
                .defineInRange("minChatSeconds", 90, 10, 3600);
        SENTIENT_KEEP_INVENTORY = b.comment("If true its items survive death. If false they drop where it died.")
                .define("keepInventory", true);
        SENTIENT_RESPAWN_SECONDS = b.comment("Seconds before it comes back after dying.")
                .defineInRange("respawnSeconds", 30, 5, 600);
        SENTIENT_PICKUP = b.comment("It picks up item drops near it.").define("pickupItems", true);
        SENTIENT_MINING = b.comment("It can mine logs, ores, stone, dirt, sand and gravel (never anything with a chest or inventory).")
                .define("mining", true);
        SENTIENT_ACCEPT_GIFTS = b.comment("Players can right click it with an item to give it the item.")
                .define("acceptGifts", true);
        SENTIENT_MOVES_HIT_PLAYERS = b.comment("Let its cursed moves hit players that it is fighting. Off = moves only hit hostile mobs.")
                .define("movesHitPlayers", true);
        SENTIENT_MAX_HEALTH = b.defineInRange("maxHealth", 40, 4, 400);
        SENTIENT_REAL_CURSED_FATE = b.comment("When the cursedfate mod is loaded, the body uses the real Cursed Fate abilities (its own technique and ability slots) instead of the built-in fallback moves.")
                .define("realCursedFate", true);
        SENTIENT_SOCIAL = b.comment("The body keeps a relationship score for every player and decides for itself who it allies with and who it fights. Players cannot opt out, only the AI's own feelings change it. Off = it treats everyone neutrally and never hunts players.")
                .define("social", true);
        SENTIENT_HUNT_PLAYERS = b.comment("Let the body actively hunt and fight players it considers enemies (score -40 or lower). The owner (whoever turned sentient mode on) is never auto-targeted. Off = it only defends itself.")
                .define("huntEnemyPlayers", true);
        SENTIENT_SPAWN_SAFE_RADIUS = b.comment("It never hunts a player who is within this many blocks of the world spawn (spawn camping is against the rules).")
                .defineInRange("spawnSafeRadius", 48, 0, 1000);
        SENTIENT_SOCIAL_DECAY_PER_HOUR = b.comment("How many points per hour every relationship score drifts back toward 0 (grudges and friendships fade slowly).")
                .defineInRange("socialDecayPerHour", 3, 0, 100);
        SENTIENT_QUESTS = b.comment("Allow the body to grind FTB Quests (only if ftbquests is installed). Simple tasks only: collect, kill, location, checkmark.")
                .define("quests", true);
        SENTIENT_QUEST_COMMAND_REWARDS = b.comment("Let it claim quest rewards that run commands. Off = it skips command rewards.")
                .define("questCommandRewards", false);
        SENTIENT_QUEST_MAX_TRAVEL = b.comment("Longest distance in blocks it will walk to reach a quest location.")
                .defineInRange("questMaxTravel", 400, 20, 5000);
        b.pop();

        b.comment("Discord alerts").push("discord");
        WEBHOOK_URL = b.comment("Discord webhook URL for the admin alert channel. Treat it like a password.")
                .define("webhookUrl", "");
        b.pop();

        SPEC = b.build();
    }

}
