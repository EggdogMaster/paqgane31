package com.paq.paqgane.sentient;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

/** How the sentient body feels about each player: a score from -100 (enemy) to 100 (ally). Saved with the world. */
public class Relations extends SavedData {
    public static class Entry {
        public double score;
        public String name = "?";
    }

    public final Map<UUID, Entry> map = new HashMap<>();

    public static Relations load(CompoundTag tag) {
        Relations r = new Relations();
        ListTag list = tag.getList("p", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            if (!c.hasUUID("id")) {
                continue;
            }
            Entry e = new Entry();
            e.score = c.getDouble("s");
            e.name = c.getString("n");
            r.map.put(c.getUUID("id"), e);
        }
        return r;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Entry> en : this.map.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("id", en.getKey());
            c.putDouble("s", en.getValue().score);
            c.putString("n", en.getValue().name);
            list.add(c);
        }
        tag.put("p", list);
        return tag;
    }
}
