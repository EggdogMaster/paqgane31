package com.paq.paqgane.monitor;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.paq.paqgane.PaqganeManager;
import com.paq.paqgane.PaqganeMod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;

public class WarningStore {
    private static final Gson GSON = new Gson();
    private static Map<String, Integer> counts;

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("paqgane-warnings.json");
    }

    private static synchronized Map<String, Integer> load() {
        if (counts == null) {
            counts = new HashMap<>();
            try {
                Path f = file();
                if (Files.exists(f)) {
                    Map<String, Integer> read = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8),
                            new TypeToken<Map<String, Integer>>() {
                            }.getType());
                    if (read != null) {
                        counts = read;
                    }
                }
            } catch (Exception e) {
                PaqganeMod.LOGGER.warn("Could not read warnings file", e);
            }
        }
        return counts;
    }

    private static synchronized void save() {
        try {
            Files.writeString(file(), GSON.toJson(load()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            PaqganeMod.LOGGER.warn("Could not save warnings file", e);
        }
    }

    public static synchronized int count(UUID id) {
        return load().getOrDefault(id.toString(), 0);
    }

    public static synchronized void clear(UUID id) {
        load().remove(id.toString());
        save();
    }

    public static String ladder(int warnings) {
        if (warnings >= 6) {
            return "permanent ban";
        }
        if (warnings >= 4) {
            return "3 day minecraft ban";
        }
        return warnings >= 2 ? "1 day mute (minecraft + discord)" : "no punishment step yet";
    }

    public static String nextStep(int warnings) {
        if (warnings < 2) {
            return "2 warnings = 1 day mute";
        }
        if (warnings < 4) {
            return "4 warnings = 3 day minecraft ban";
        }
        return warnings < 6 ? "6 warnings = permanent ban" : "already at the top of the ladder";
    }

    public static synchronized void warn(MinecraftServer server, ServerPlayer target, String reason, String by) {
        int n = load().merge(target.getUUID().toString(), 1, Integer::sum);
        save();
        String why = reason != null && !reason.isBlank() ? reason : "rule break";
        PaqganeManager.say(server, target.getGameProfile().getName() + " got warning " + n + ": " + why + ". on foenem");
        Discord.send("**warning issued**\nplayer: " + target.getGameProfile().getName()
                + "\nreason: " + why
                + "\nissued by: " + by
                + "\nwarnings on record: " + n + " (current step: " + ladder(n) + ", " + nextStep(n) + ")"
                + (n >= 2 ? "\nadmins: this player has reached a punishment step, please apply it." : ""));
    }
}
