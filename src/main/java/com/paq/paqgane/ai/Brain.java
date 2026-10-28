package com.paq.paqgane.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.paq.paqgane.PaqganeConfig;
import com.paq.paqgane.PaqganeManager;
import com.paq.paqgane.PaqganeMod;
import com.paq.paqgane.action.ActionExecutor;
import com.paq.paqgane.action.CommandRunner;
import com.paq.paqgane.sentient.SentientManager;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public class Brain {
    private static final Pattern TRIGGER = Pattern.compile("^@?(paqgane|paq)\\b[,:!.\\s]*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern THINK = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);
    private static final Map<UUID, Long> LAST_MESSAGE_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<JsonObject>> HISTORY = new ConcurrentHashMap<>();
    private static final Random RNG = new Random();

    private static final List<String> FAIL_LINES = List.of(
            "brain lagged out. try again in a bit",
            "i blanked on that one. ask me again",
            "my brain is buffering bro, one sec",
            "server wifi is cooked, ask me again");

    /** True when the message was addressed to Paqgane and handled here. */
    public static boolean onChat(ServerPlayer player, String raw) {
        Matcher m = TRIGGER.matcher(raw.trim());
        if (!m.matches()) {
            return false;
        }
        MinecraftServer server = player.getServer();
        if (!PaqganeManager.isSummoned(player) && !SentientManager.isActive(server)) {
            return false;
        }

        String text = m.group(2).trim();
        int maxChars = PaqganeConfig.MAX_INPUT_CHARS.get();
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars);
        }
        text = text.replaceAll("[\\p{Cntrl}&&[^ ]]", " ");
        boolean op = isOp(player);
        if (text.isEmpty()) {
            text = "hey";
        }

        String lower = text.toLowerCase();
        if (op && CommandRunner.hasPending(player)) {
            if (lower.equals("confirm") || lower.equals("yes") || lower.equals("do it")) {
                CommandRunner.confirm(player);
                return true;
            }
            if (lower.equals("cancel") || lower.equals("no") || lower.equals("nvm")) {
                CommandRunner.cancel(player);
                PaqganeManager.whisper(player, "cancelled. js forget it");
                return true;
            }
        }

        int cd = PaqganeConfig.COOLDOWN_SECONDS.get();
        if (cd > 0 && (!op || !PaqganeConfig.OPS_BYPASS_COOLDOWN.get())) {
            long now = System.currentTimeMillis();
            Long last = LAST_MESSAGE_MS.get(player.getUUID());
            if (last != null && now - last < cd * 1000L) {
                long left = (cd * 1000L - (now - last) + 999L) / 1000L;
                player.displayClientMessage(Component.literal("Paqgane: chill, " + left + "s"), true);
                return true;
            }
            LAST_MESSAGE_MS.put(player.getUUID(), now);
        }

        if (!GroqClient.isConfigured()) {
            PaqganeManager.whisper(player, "my brain isnt plugged in yet. tell the real paq to add the api key");
            return true;
        }
        ask(player, text, op);
        return true;
    }

    public static boolean isOp(ServerPlayer player) {
        return player.hasPermissions(PaqganeConfig.OP_LEVEL.get());
    }

    private static void ask(ServerPlayer player, String text, boolean op) {
        MinecraftServer server = player.getServer();
        UUID id = player.getUUID();
        String name = player.getGameProfile().getName();
        JsonArray messages = new JsonArray();
        messages.add(msg("system", Prompts.system(player, op)));
        Deque<JsonObject> hist = HISTORY.computeIfAbsent(id, k -> new ArrayDeque<>());
        synchronized (hist) {
            for (JsonObject h : hist) {
                messages.add(h);
            }
        }
        messages.add(msg("user", name + ": " + text));

        if (GroqClient.queueDepth() >= 2) {
            PaqganeManager.whisper(player, "lots of yall talking, give me a sec");
        }

        final String finalText = text;
        GroqClient.chat(messages).whenComplete((reply, err) -> server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) {
                return;
            }
            if (err != null) {
                Throwable cause = err;
                while (cause.getCause() != null && cause.getCause() != cause) {
                    cause = cause.getCause();
                }
                if (cause instanceof GroqClient.RateLimitedException) {
                    PaqganeManager.whisper(p, "im rate limited rn bro, give me a sec");
                } else if (cause instanceof GroqClient.BusyException) {
                    PaqganeManager.whisper(p, "too many people talking to me, try again in a few seconds");
                } else {
                    PaqganeMod.LOGGER.warn("Paqgane AI call failed: {}", cause.toString());
                    PaqganeManager.whisper(p, FAIL_LINES.get(RNG.nextInt(FAIL_LINES.size())));
                }
            } else {
                handleReply(p, finalText, reply, op);
            }
        }));
    }

    public record Parsed(String say, JsonArray actions) {
    }

    /** Lenient parser shared with the other AI features. */
    public static Parsed parse(String reply) {
        String say = null;
        JsonArray actions = new JsonArray();
        String cleaned = THINK.matcher(reply == null ? "" : reply).replaceAll("").trim();
        try {
            String json = cleaned;
            int a = json.indexOf('{');
            int b = json.lastIndexOf('}');
            if (a >= 0 && b > a) {
                json = json.substring(a, b + 1);
            }
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.has("say") && !obj.get("say").isJsonNull()) {
                say = obj.get("say").getAsString();
            }
            JsonElement acts = obj.get("actions");
            if (acts != null && acts.isJsonArray()) {
                actions = acts.getAsJsonArray();
            }
        } catch (Exception e) {
            say = cleaned.length() > 220 ? cleaned.substring(0, 220) : cleaned;
        }
        return new Parsed(say, actions);
    }

    private static void handleReply(ServerPlayer player, String userText, String reply, boolean op) {
        Parsed parsed = parse(reply);
        String say = parsed.say();
        if (say != null && !say.isBlank()) {
            if (say.length() > 240) {
                say = say.substring(0, 240);
            }
            PaqganeManager.say(player.getServer(), say);
            remember(player.getUUID(), player.getGameProfile().getName() + ": " + userText, say);
        }
        ActionExecutor.run(player, parsed.actions(), op);
    }

    private static void remember(UUID id, String userText, String say) {
        int max = PaqganeConfig.HISTORY_MESSAGES.get();
        if (max > 0) {
            Deque<JsonObject> hist = HISTORY.computeIfAbsent(id, k -> new ArrayDeque<>());
            synchronized (hist) {
                hist.add(msg("user", userText));
                hist.add(msg("assistant", say));
                while (hist.size() > max) {
                    hist.poll();
                }
            }
        }
    }

    public static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    public static void forget(UUID id) {
        HISTORY.remove(id);
        LAST_MESSAGE_MS.remove(id);
    }
}
