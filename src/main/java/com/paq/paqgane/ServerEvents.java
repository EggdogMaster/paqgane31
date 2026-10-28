package com.paq.paqgane;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.paq.paqgane.ai.Brain;
import com.paq.paqgane.ai.GroqClient;
import com.paq.paqgane.monitor.ProblemDetector;
import com.paq.paqgane.monitor.RuleMonitor;
import com.paq.paqgane.monitor.WarningStore;
import com.paq.paqgane.sentient.SentientBrain;
import com.paq.paqgane.sentient.SentientEntity;
import com.paq.paqgane.sentient.SentientManager;
import com.paq.paqgane.sentient.Social;
import com.paq.paqgane.stash.StashManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "paqgane")
public class ServerEvents {

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void chat(ServerChatEvent event) {
        ServerPlayer p = event.getPlayer();
        if (RuleMonitor.onChat(event)) {
            return;
        }
        String raw = event.getRawText();
        SentientBrain.recordChat(p.getGameProfile().getName(), raw);
        if (Brain.onChat(p, raw)) {
            Social.onSpokeTo(p);
        } else {
            ProblemDetector.onChat(p, raw);
        }
    }

    private static boolean isOp(CommandSourceStack s) {
        return s.hasPermission(PaqganeConfig.OP_LEVEL.get());
    }

    private static void reply(CommandSourceStack src, String text) {
        src.sendSuccess(() -> Component.literal(text), false);
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("paqgane");

        root.then(Commands.literal("summon").executes(ctx -> {
            PaqganeManager.summon(ctx.getSource().getPlayerOrException());
            return 1;
        }));
        root.then(Commands.literal("dismiss").executes(ctx -> {
            PaqganeManager.dismiss(ctx.getSource().getPlayerOrException());
            return 1;
        }));

        root.then(Commands.literal("warnings").requires(ServerEvents::isOp)
                .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                    ServerPlayer t = EntityArgument.getPlayer(ctx, "player");
                    int n = WarningStore.count(t.getUUID());
                    reply(ctx.getSource(), t.getGameProfile().getName() + " has " + n + " warning(s). Step: "
                            + WarningStore.ladder(n) + ". Next: " + WarningStore.nextStep(n));
                    return n;
                })));
        root.then(Commands.literal("clearwarnings").requires(ServerEvents::isOp)
                .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                    ServerPlayer t = EntityArgument.getPlayer(ctx, "player");
                    WarningStore.clear(t.getUUID());
                    ctx.getSource().sendSuccess(() -> Component.literal("Cleared warnings for " + t.getGameProfile().getName()), true);
                    return 1;
                })));

        root.then(Commands.literal("status").requires(ServerEvents::isOp).executes(ctx -> {
            CommandSourceStack src = ctx.getSource();
            MinecraftServer server = src.getServer();
            reply(src, "Paqgane 1.3.1");
            reply(src, "AI: " + (GroqClient.isConfigured() ? "key set" : "NO API KEY") + ", model " + PaqganeConfig.MODEL.get()
                    + ", fallback " + (PaqganeConfig.FALLBACK_MODEL.get().isBlank() ? "none" : PaqganeConfig.FALLBACK_MODEL.get())
                    + ", reasoning " + (PaqganeConfig.REASONING_EFFORT.get().isBlank() ? "default" : PaqganeConfig.REASONING_EFFORT.get())
                    + ", max tokens " + PaqganeConfig.MAX_TOKENS.get());
            reply(src, "AI calls: " + GroqClient.statusLine());
            reply(src, "chat cooldown " + PaqganeConfig.COOLDOWN_SECONDS.get() + "s, summoned heads: " + PaqganeManager.summonedCount()
                    + ", discord webhook: " + (PaqganeConfig.WEBHOOK_URL.get().isBlank() ? "NOT SET" : "set"));
            reply(src, "monitors: rules " + onOff(PaqganeConfig.MONITOR_ENABLED.get())
                    + ", xray " + onOff(PaqganeConfig.XRAY_ENABLED.get())
                    + ", problems " + onOff(PaqganeConfig.PROBLEMS_ENABLED.get())
                    + ", grief " + onOff(PaqganeConfig.GRIEF_ENABLED.get()));
            reply(src, "xray thresholds: diamonds " + PaqganeConfig.XRAY_DIAMOND_ALERT.get() + ", debris "
                    + PaqganeConfig.XRAY_DEBRIS_ALERT.get() + ", hidden " + PaqganeConfig.XRAY_HIDDEN_ALERT.get()
                    + " per " + PaqganeConfig.XRAY_WINDOW_BLOCKS.get() + " blocks");
            reply(src, "stash: " + onOff(PaqganeConfig.STASH_ENABLED.get()) + ", " + PaqganeConfig.STASH_ROWS.get() + " rows, "
                    + StashManager.data(server).count() + " players have one");
            reply(src, SentientManager.status(server));
            return 1;
        }));

        root.then(Commands.literal("ping").requires(ServerEvents::isOp).executes(ctx -> {
            CommandSourceStack src = ctx.getSource();
            MinecraftServer server = src.getServer();
            if (!GroqClient.isConfigured()) {
                reply(src, "no api key set in config/paqgane-common.toml");
                return 0;
            }
            reply(src, "asking the AI, one moment...");
            long start = System.currentTimeMillis();
            GroqClient.chat(GroqClient.single("reply with ONLY this json: {\"say\":\"pong\",\"actions\":[]}", "ping"))
                    .whenComplete((r, err) -> server.execute(() -> {
                        long ms = System.currentTimeMillis() - start;
                        if (err != null) {
                            reply(src, "AI call FAILED after " + ms + "ms: " + err.getMessage());
                        } else {
                            String s = r.length() > 120 ? r.substring(0, 120) : r;
                            reply(src, "AI replied in " + ms + "ms: " + s);
                        }
                    }));
            return 1;
        }));

        LiteralArgumentBuilder<CommandSourceStack> stash = Commands.literal("stash")
                .executes(ctx -> {
                    StashManager.openOwn(ctx.getSource().getPlayerOrException());
                    return 1;
                })
                .then(Commands.argument("player", EntityArgument.player()).requires(ServerEvents::isOp).executes(ctx -> {
                    ServerPlayer viewer = ctx.getSource().getPlayerOrException();
                    ServerPlayer t = EntityArgument.getPlayer(ctx, "player");
                    StashManager.openFor(viewer, t.getUUID(), t.getGameProfile().getName());
                    return 1;
                }));
        root.then(stash);

        root.then(Commands.literal("sentient").requires(ServerEvents::isOp)
                .then(Commands.literal("on").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    reply(ctx.getSource(), SentientManager.setEnabled(ctx.getSource().getServer(), p, true));
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    reply(ctx.getSource(), SentientManager.setEnabled(ctx.getSource().getServer(), p, false));
                    return 1;
                }))
                .then(Commands.literal("status").executes(ctx -> {
                    reply(ctx.getSource(), SentientManager.status(ctx.getSource().getServer()));
                    return 1;
                }))
                .then(Commands.literal("recall").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    reply(ctx.getSource(), SentientManager.recall(ctx.getSource().getServer(), p));
                    return 1;
                }))
                .then(Commands.literal("inventory").executes(ctx -> openSentientInventory(ctx.getSource())))
                .then(Commands.literal("freewill")
                        .then(Commands.literal("on").executes(ctx -> {
                            reply(ctx.getSource(), SentientManager.setFreeWill(ctx.getSource().getServer(), ctx.getSource().getPlayerOrException(), true));
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            reply(ctx.getSource(), SentientManager.setFreeWill(ctx.getSource().getServer(), ctx.getSource().getPlayerOrException(), false));
                            return 1;
                        })))
                .then(Commands.literal("relations").executes(ctx -> {
                    reply(ctx.getSource(), Social.observe(ctx.getSource().getServer()).trim());
                    return 1;
                }))
                .then(Commands.literal("quests")
                        .executes(ctx -> {
                            reply(ctx.getSource(), SentientManager.questStatus(ctx.getSource().getServer()));
                            return 1;
                        })
                        .then(Commands.literal("on").executes(ctx -> {
                            reply(ctx.getSource(), SentientManager.setQuests(ctx.getSource().getServer(), true));
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            reply(ctx.getSource(), SentientManager.setQuests(ctx.getSource().getServer(), false));
                            return 1;
                        })))
                .then(Commands.literal("unequip").executes(ctx -> {
                    SentientEntity e = SentientManager.entity(ctx.getSource().getServer());
                    if (e == null) {
                        reply(ctx.getSource(), "the sentient body is not out right now");
                        return 0;
                    }
                    e.unequipAll();
                    reply(ctx.getSource(), "moved everything it wears back into its inventory");
                    return 1;
                })));

        d.register(root);
    }

    private static int openSentientInventory(CommandSourceStack src) throws CommandSyntaxException {
        SentientEntity e = SentientManager.entity(src.getServer());
        if (e == null) {
            reply(src, "the sentient body is not out right now");
            return 0;
        }
        e.openInventory(src.getPlayerOrException());
        return 1;
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }
}
