package com.paq.paqgane.action;

import com.paq.paqgane.PaqganeConfig;
import com.paq.paqgane.PaqganeManager;
import com.paq.paqgane.PaqganeMod;
import com.paq.paqgane.monitor.Discord;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class CommandRunner {
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static final long CONFIRM_WINDOW_MS = 30_000L;

    public static boolean hasPending(ServerPlayer p) {
        Pending pend = PENDING.get(p.getUUID());
        if (pend != null && pend.expiresAtMs() < System.currentTimeMillis()) {
            PENDING.remove(p.getUUID());
            return false;
        }
        return pend != null;
    }

    public static void cancel(ServerPlayer p) {
        PENDING.remove(p.getUUID());
    }

    public static void confirm(ServerPlayer p) {
        Pending pend = PENDING.remove(p.getUUID());
        if (pend != null && pend.expiresAtMs() >= System.currentTimeMillis()) {
            execute(p, pend.command());
        } else {
            PaqganeManager.whisper(p, "nothing to confirm, it expired");
        }
    }

    static String blockedReason(String cmd) {
        if (cmd.contains("\n") || cmd.contains("\r")) {
            return "no multi-line commands";
        }
        String[] tokens = cmd.trim().split("\\s+");
        List<String> blocked = new ArrayList<>(PaqganeConfig.BLOCKED_COMMANDS.get());
        for (int i = 0; i < tokens.length; i++) {
            boolean isStart = i == 0 || tokens[i - 1].equalsIgnoreCase("run");
            if (!isStart) {
                continue;
            }
            String t = tokens[i].toLowerCase(Locale.ROOT);
            if (t.startsWith("minecraft:")) {
                t = t.substring("minecraft:".length());
            }
            for (String b : blocked) {
                if (t.equals(b.toLowerCase(Locale.ROOT))) {
                    return "/" + t + " is blocked, i wont run that one";
                }
            }
        }
        return null;
    }

    static boolean isDestructive(String cmd) {
        String norm = cmd.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (String prefix : PaqganeConfig.DESTRUCTIVE_COMMANDS.get()) {
            String p = prefix.toLowerCase(Locale.ROOT);
            if (norm.startsWith(p + " ") || norm.equals(p)) {
                return true;
            }
        }
        int idx = norm.indexOf(" run ");
        return norm.startsWith("execute") && idx >= 0 && isDestructive(norm.substring(idx + 5));
    }

    public static void request(ServerPlayer p, String cmd) {
        if (cmd == null || cmd.isBlank()) {
            return;
        }
        cmd = cmd.trim();
        if (cmd.startsWith("/")) {
            cmd = cmd.substring(1);
        }
        String reason = blockedReason(cmd);
        if (reason != null) {
            PaqganeManager.whisper(p, reason);
        } else if (PaqganeConfig.CONFIRM_DESTRUCTIVE.get() && isDestructive(cmd)) {
            PENDING.put(p.getUUID(), new Pending(cmd, System.currentTimeMillis() + CONFIRM_WINDOW_MS));
            PaqganeManager.whisper(p, "that one is destructive: /" + cmd + " . say \"paq confirm\" within 30s or \"paq cancel\"");
        } else {
            execute(p, cmd);
        }
    }

    private static void execute(ServerPlayer p, String cmd) {
        String reason = blockedReason(cmd);
        if (reason != null) {
            PaqganeManager.whisper(p, reason);
            return;
        }
        Collector out = new Collector();
        CommandSourceStack stack = p.createCommandSourceStack().withSource(out);
        int result = 0;
        try {
            result = p.getServer().getCommands().performPrefixedCommand(stack, cmd);
        } catch (Exception e) {
            PaqganeMod.LOGGER.warn("Paqgane command failed: {}", cmd, e);
            out.lines.add("error: " + e.getMessage());
        }
        PaqganeMod.LOGGER.info("[Paqgane audit] {} ran: /{}", p.getGameProfile().getName(), cmd);
        Discord.send("**command audit** " + p.getGameProfile().getName() + " ran via Paqgane: `/" + cmd + "`");
        String summary = String.join(" | ", out.lines);
        if (summary.length() > 280) {
            summary = summary.substring(0, 280) + "...";
        }
        PaqganeManager.whisper(p, summary.isBlank() ? "ran /" + cmd + " (result " + result + ")" : summary);
    }

    private static class Collector implements CommandSource {
        final List<String> lines = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component c) {
            this.lines.add(c.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }

    private record Pending(String command, long expiresAtMs) {
    }
}
