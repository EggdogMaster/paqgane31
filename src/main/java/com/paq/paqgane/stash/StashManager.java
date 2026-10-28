package com.paq.paqgane.stash;

import com.paq.paqgane.PaqganeConfig;
import com.paq.paqgane.PaqganeManager;
import com.paq.paqgane.monitor.RuleMonitor;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;

public class StashManager {
    private static final String DATA_NAME = "paqgane_stash";

    public static StashData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(StashData::load, StashData::new, DATA_NAME);
    }

    /** The stash of a player if they have ever used it, otherwise null. Used by the item scans. */
    @Nullable
    public static Container peek(MinecraftServer server, UUID id) {
        if (!PaqganeConfig.STASH_ENABLED.get()) {
            return null;
        }
        return data(server).peek(id);
    }

    public static void openOwn(ServerPlayer player) {
        if (!PaqganeConfig.STASH_ENABLED.get()) {
            PaqganeManager.whisper(player, "stashes are turned off rn bro");
            return;
        }
        if (PaqganeConfig.STASH_BLOCK_IN_COMBAT.get() && RuleMonitor.inCombat(player)) {
            PaqganeManager.whisper(player, "not in combat bro. finish the fight first");
            return;
        }
        open(player, player.getUUID(), "Your stash");
    }

    /** Opens any player's stash for an op. */
    public static void openFor(ServerPlayer viewer, UUID owner, String ownerName) {
        open(viewer, owner, ownerName + "'s stash");
    }

    private static void open(ServerPlayer viewer, UUID owner, String title) {
        SimpleContainer box = data(viewer.getServer()).get(owner);
        int rows = PaqganeConfig.STASH_ROWS.get();
        viewer.openMenu(new SimpleMenuProvider((id, inv, p) -> menu(rows, id, inv, box), Component.literal(title)));
    }

    public static AbstractContainerMenu menu(int rows, int id, Inventory inv, Container box) {
        MenuType<?> type = switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
        int r = Math.max(1, Math.min(6, rows));
        return new ChestMenu(type, id, inv, box, r);
    }
}
