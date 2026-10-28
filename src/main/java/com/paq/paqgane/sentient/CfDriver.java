package com.paq.paqgane.sentient;

import com.paq.paqgane.PaqganeMod;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/**
 * Plays Cursed Fate for the body the way a player would: it keeps combat mode on, binds ability ids to its seven slots,
 * faces what it wants to hit, presses a slot that is off cooldown and lets go of it a moment later. The mod does not
 * name its abilities by slot, so the driver judges a slot by what happened after the press (cooldown started, energy
 * dropped, or a hold flag went up). A slot that does nothing twice in a row gets another ability bound to it.
 */
final class CfDriver {
    private static final int SLOTS = 7;
    /** Which ability ids belong to which technique, read from the mod's own dispatcher (AbilitysProcedure). */
    private static final int[] MELEE = {8, 9, 10, 11, 12, 13};
    private static final int[] CURSED_ENERGY = {14, 15, 16, 17, 18, 19};

    private final SentientEntity bot;
    private final int[] duds = new int[SLOTS + 1];
    private final int[] backoff = new int[SLOTS + 1];
    private final boolean[] confirmed = new boolean[SLOTS + 1];
    private int rotate;
    private int releaseTick = -1;
    private int releaseSlot;
    private int releaseTries;
    private int checkTick = -1;
    private int checkSlot;
    private double checkEnergy;
    private int combatOffTick = -1;
    private int lastCastTick = -1000;
    private boolean rolled;
    private String loadoutFor = "";

    CfDriver(SentientEntity bot) {
        this.bot = bot;
    }

    boolean active() {
        return CfBridge.available();
    }

    private static int[] techniqueIds(String technique) {
        switch (technique) {
            case "LimitLess":
                return new int[]{1, 2, 3, 4, 5, 6, 7};
            case "Shrine":
                return new int[]{26, 27, 28, 29, 30, 31};
            case "Vessel":
                return new int[]{26, 27, 28, 29, 30, 31, 50};
            case "WoggieBoggie":
                return new int[]{32, 33, 34, 35, 36, 37};
            case "PrivatePureLoveTrain":
                return new int[]{38, 39, 40, 41, 42, 43};
            case "Heavenly Restriction":
                return new int[]{20, 21, 22, 23, 24, 25};
            case "Cursed Partners":
                return new int[]{51, 52, 53, 54, 55, 56};
            default:
                // Blood Manipulation, Puppet Manipulation, Star Rage, Disaster Flames: handled by the mod's second dispatcher
                return new int[]{57, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68};
        }
    }

    private static List<Integer> allCandidates(String technique) {
        List<Integer> out = new ArrayList<>();
        for (int i : techniqueIds(technique)) {
            out.add(i);
        }
        for (int i : MELEE) {
            out.add(i);
        }
        for (int i : CURSED_ENERGY) {
            out.add(i);
        }
        return out;
    }

    /** Called every tick of the body. Cheap when nothing is going on. */
    void tick(int now) {
        if (!active()) {
            return;
        }
        boolean busy = this.releaseTick >= 0 || this.checkTick >= 0 || this.combatOffTick >= 0;
        if (!busy && now % 20 != 0) {
            return;
        }
        Object pv = CfBridge.vars(this.bot);
        if (pv == null) {
            return;
        }
        if (!this.rolled && now > 120 && CfBridge.text(pv, "Technique").isEmpty()) {
            this.rolled = true;
            CfBridge.rollTechnique(this.bot);
            PaqganeMod.LOGGER.info("Paqgane: the body had no Cursed Fate technique after joining, rolled one");
        }
        if (this.releaseTick >= 0 && now >= this.releaseTick) {
            CfBridge.release(this.releaseSlot, this.bot);
            if (this.releaseTries++ < 3 && CfBridge.flag(pv, "holdability" + this.releaseSlot)) {
                this.releaseTick = now + 15;
            } else {
                this.releaseTick = -1;
            }
        }
        if (this.checkTick >= 0 && now >= this.checkTick) {
            int slot = this.checkSlot;
            this.checkTick = -1;
            boolean worked = CfBridge.num(pv, "abilitycooldown" + slot) > 0.0
                    || CfBridge.flag(pv, "holdability" + slot)
                    || this.checkEnergy - CfBridge.num(pv, "CursedEnegry") > 0.5;
            if (worked) {
                this.duds[slot] = 0;
                this.backoff[slot] = now + 30;
                if (!this.confirmed[slot]) {
                    this.confirmed[slot] = true;
                    PaqganeMod.LOGGER.info("Paqgane: Cursed Fate slot {} works for the body (ability id {}, technique {})", slot,
                            Math.round(CfBridge.num(pv, "AbilityNum" + slot)), CfBridge.text(pv, "Technique"));
                }
            } else {
                this.duds[slot]++;
                this.backoff[slot] = now + 100;
                if (this.duds[slot] >= 2) {
                    this.duds[slot] = 0;
                    this.rebind(pv, slot);
                }
            }
        }
        if (this.combatOffTick >= 0 && now >= this.combatOffTick && this.bot.currentTarget() == null) {
            this.combatOffTick = -1;
            if (CfBridge.flag(pv, "CombatMode")) {
                CfBridge.put(pv, "CombatMode", Boolean.FALSE);
                CfBridge.sync(this.bot, pv);
            }
        } else if (this.combatOffTick >= 0 && now >= this.combatOffTick) {
            this.combatOffTick = now + 100;
        }
    }

    private boolean slotReady(Object pv, int now, int slot) {
        return CfBridge.num(pv, "AbilityNum" + slot) > 0.0
                && CfBridge.num(pv, "abilitycooldown" + slot) <= 0.0
                && now >= this.backoff[slot]
                && !CfBridge.flag(pv, "holdability" + slot);
    }

    /** Binds ability ids to the seven slots when none are bound yet, or when the technique changed. */
    private void ensureLoadout(Object pv) {
        String tech = CfBridge.text(pv, "Technique");
        boolean anyBound = false;
        for (int s = 1; s <= SLOTS; s++) {
            if (CfBridge.num(pv, "AbilityNum" + s) > 0.0) {
                anyBound = true;
            }
        }
        if (anyBound && (this.loadoutFor.isEmpty() || this.loadoutFor.equals(tech))) {
            this.loadoutFor = tech;
            return;
        }
        List<Integer> chosen = new ArrayList<>();
        int[] own = techniqueIds(tech);
        for (int i = 0; i < own.length && chosen.size() < 5; i++) {
            chosen.add(own[i]);
        }
        chosen.add(MELEE[0]);
        chosen.add(CURSED_ENERGY[0]);
        for (int i = 0; i < SLOTS; i++) {
            int id = i < chosen.size() ? chosen.get(i) : 0;
            CfBridge.put(pv, "AbilityNum" + (i + 1), Double.valueOf(id));
        }
        CfBridge.sync(this.bot, pv);
        this.loadoutFor = tech;
        PaqganeMod.LOGGER.info("Paqgane: bound Cursed Fate abilities {} to the body's slots (technique {})", chosen, tech);
    }

    private void rebind(Object pv, int slot) {
        String tech = CfBridge.text(pv, "Technique");
        List<Integer> all = allCandidates(tech);
        Set<Integer> bound = new HashSet<>();
        for (int s = 1; s <= SLOTS; s++) {
            bound.add((int) Math.round(CfBridge.num(pv, "AbilityNum" + s)));
        }
        for (int k = 0; k < all.size(); k++) {
            int id = all.get(this.rotate++ % all.size());
            if (!bound.contains(id)) {
                CfBridge.put(pv, "AbilityNum" + slot, Double.valueOf(id));
                CfBridge.sync(this.bot, pv);
                return;
            }
        }
    }

    int readyCount() {
        Object pv = CfBridge.vars(this.bot);
        if (pv == null) {
            return 0;
        }
        int now = this.bot.age();
        int n = 0;
        for (int s = 1; s <= SLOTS; s++) {
            if (this.slotReady(pv, now, s)) {
                n++;
            }
        }
        return n;
    }

    String summary() {
        Object pv = CfBridge.vars(this.bot);
        if (pv == null) {
            return "cursed fate: not available";
        }
        String tech = CfBridge.text(pv, "Technique");
        if (tech.isEmpty()) {
            return "cursed fate: no technique yet";
        }
        return "cursed fate technique " + tech + ", energy " + Math.round(CfBridge.num(pv, "CursedEnegry")) + "/"
                + Math.round(CfBridge.num(pv, "CursedEnegryMax")) + ", " + this.readyCount() + " of " + SLOTS + " ability slots ready";
    }

    boolean hasEnergy() {
        Object pv = CfBridge.vars(this.bot);
        double max = CfBridge.num(pv, "CursedEnegryMax");
        return pv != null && (max <= 0.0 || CfBridge.num(pv, "CursedEnegry") >= max * 0.2);
    }

    /** Presses one slot (wantedSlot 1-7) or a random ready one (anything else). Returns true if a press went out. */
    boolean cast(@Nullable LivingEntity target, int wantedSlot) {
        if (!this.active() || !(this.bot.level() instanceof ServerLevel sl)) {
            return false;
        }
        Object pv = CfBridge.vars(this.bot);
        int now = this.bot.age();
        if (pv == null || now - this.lastCastTick < 8 || CfBridge.text(pv, "Technique").isEmpty() || !this.hasEnergy()) {
            return false;
        }
        this.ensureLoadout(pv);
        int slot = wantedSlot;
        if (slot >= 1 && slot <= SLOTS) {
            if (!this.slotReady(pv, now, slot)) {
                return false;
            }
        } else {
            List<Integer> ready = new ArrayList<>();
            for (int s = 1; s <= SLOTS; s++) {
                if (this.slotReady(pv, now, s)) {
                    ready.add(s);
                }
            }
            if (ready.isEmpty()) {
                return false;
            }
            slot = ready.get(this.bot.getRandom().nextInt(ready.size()));
        }
        if (target != null) {
            this.bot.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
        }
        if (!CfBridge.flag(pv, "CombatMode")) {
            CfBridge.put(pv, "CombatMode", Boolean.TRUE);
            CfBridge.sync(this.bot, pv);
        }
        this.combatOffTick = now + 300;
        this.checkEnergy = CfBridge.num(pv, "CursedEnegry");
        if (!CfBridge.press(slot, sl, this.bot)) {
            return false;
        }
        this.lastCastTick = now;
        this.releaseSlot = slot;
        this.releaseTick = now + 8;
        this.releaseTries = 0;
        this.checkSlot = slot;
        this.checkTick = now + 4;
        return true;
    }
}
