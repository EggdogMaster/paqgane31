package com.paq.paqgane.stash;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.saveddata.SavedData;

/** Every player's personal stash, saved with the world. Containers always hold 54 slots; the config only changes how many rows are shown. */
public class StashData extends SavedData {
    public static final int SLOTS = 54;
    private final Map<UUID, SimpleContainer> boxes = new HashMap<>();

    private SimpleContainer newBox() {
        SimpleContainer c = new SimpleContainer(SLOTS);
        c.addListener(container -> this.setDirty());
        return c;
    }

    public SimpleContainer get(UUID id) {
        return boxes.computeIfAbsent(id, k -> newBox());
    }

    public SimpleContainer peek(UUID id) {
        return boxes.get(id);
    }

    public int count() {
        return boxes.size();
    }

    public static StashData load(CompoundTag tag) {
        StashData d = new StashData();
        ListTag list = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            try {
                UUID id = UUID.fromString(c.getString("id"));
                SimpleContainer box = d.newBox();
                box.fromTag(c.getList("items", Tag.TAG_COMPOUND));
                d.boxes.put(id, box);
            } catch (IllegalArgumentException ignored) {
                // bad uuid, skip entry
            }
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, SimpleContainer> e : boxes.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putString("id", e.getKey().toString());
            c.put("items", e.getValue().createTag());
            list.add(c);
        }
        tag.put("players", list);
        return tag;
    }
}
