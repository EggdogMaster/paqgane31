package com.paq.paqgane.sentient;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Persistent state of sentient mode: whether it is on, who its owner is (the player who turned it on), and whether
 * its player body has ever been created. The body itself (position, inventory, armor, health) is saved by vanilla in
 * the world's playerdata like any other player.
 */
public class SentientData extends SavedData {
    public boolean enabled;
    public UUID admin;
    public boolean spawned;
    /** Whether the body is grinding FTB quests (survives restarts). */
    public boolean quests;
    /** Free will: no owner immunity and no social safety rules. Off by default. */
    public boolean freeWill;
    /** Old versions kept the inventory here. Not used anymore, kept so old saves still load. */
    public CompoundTag bank = new CompoundTag();

    public static SentientData load(CompoundTag tag) {
        SentientData d = new SentientData();
        d.enabled = tag.getBoolean("enabled");
        if (tag.hasUUID("admin")) {
            d.admin = tag.getUUID("admin");
        }
        d.spawned = tag.getBoolean("spawned");
        d.quests = tag.getBoolean("quests");
        d.freeWill = tag.getBoolean("freeWill");
        d.bank = tag.getCompound("bank");
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("enabled", enabled);
        if (admin != null) {
            tag.putUUID("admin", admin);
        }
        tag.putBoolean("spawned", spawned);
        tag.putBoolean("quests", quests);
        tag.putBoolean("freeWill", freeWill);
        tag.put("bank", bank);
        return tag;
    }
}
