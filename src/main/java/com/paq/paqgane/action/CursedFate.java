package com.paq.paqgane.action;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.paq.paqgane.PaqganeConfig;
import com.paq.paqgane.PaqganeManager;
import com.paq.paqgane.PaqganeMod;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraftforge.fml.ModList;

public class CursedFate {
    private static final Pattern VAR = Pattern.compile("[A-Za-z0-9_]{1,40}");

    public static boolean loaded() {
        return ModList.get().isLoaded("cursedfate");
    }

    public static void read(ServerPlayer p, String variable) {
        if (!loaded()) {
            PaqganeManager.whisper(p, "cursed fate isnt on this server bro");
            return;
        }
        if (variable == null || !VAR.matcher(variable).matches()) {
            PaqganeManager.whisper(p, "thats not a variable i can read");
            return;
        }
        final List<String> lines = new ArrayList<>();
        CommandSource collector = new CommandSource() {
            @Override
            public void sendSystemMessage(Component c) {
                lines.add(c.getString());
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
        };
        try {
            CommandSourceStack stack = p.getServer().createCommandSourceStack().withSource(collector);
            String cmd = "cursedfate Datapack ReturnAndPrint " + p.getGameProfile().getName() + " " + variable;
            int result = p.getServer().getCommands().getDispatcher().execute(cmd, stack);
            String out = String.join(" ", lines);
            PaqganeManager.whisper(p, out.isBlank() ? variable + " = " + result : out);
        } catch (CommandSyntaxException e) {
            PaqganeManager.whisper(p, "cursed fate didnt like that variable");
        } catch (Exception e) {
            PaqganeMod.LOGGER.warn("cf_read failed", e);
            PaqganeManager.whisper(p, "couldnt read that one");
        }
    }

    public static void pressAbility(ServerPlayer p, int slot) {
        if (!PaqganeConfig.CURSED_FATE_ABILITIES.get()) {
            PaqganeManager.whisper(p, "ability pressing is turned off");
            return;
        }
        if (!loaded()) {
            PaqganeManager.whisper(p, "cursed fate isnt on this server bro");
            return;
        }
        if (slot < 1 || slot > 7) {
            return;
        }
        try {
            Class<?> pressed = Class.forName("cursedfate.procedures.Ability" + slot + "OnKeyPressedProcedure");
            Method press = pressed.getMethod("execute", LevelAccessor.class, double.class, double.class, double.class, Entity.class);
            press.invoke(null, p.level(), p.getX(), p.getY(), p.getZ(), p);
            Scheduler.runLater(6, () -> {
                try {
                    Class<?> released = Class.forName("cursedfate.procedures.Ability" + slot + "OnKeyReleasedProcedure");
                    Method rel = released.getMethod("execute", Entity.class);
                    rel.invoke(null, p);
                } catch (Exception ignored) {
                    // release procedure is optional
                }
            });
        } catch (Exception e) {
            PaqganeMod.LOGGER.warn("cf_ability failed for slot {}", slot, e);
            PaqganeManager.whisper(p, "couldnt press that ability, cursed fate said no");
        }
    }
}
