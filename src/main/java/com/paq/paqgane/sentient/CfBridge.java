package com.paq.paqgane.sentient;

import com.paq.paqgane.PaqganeConfig;
import com.paq.paqgane.PaqganeMod;
import com.paq.paqgane.action.CursedFate;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraftforge.common.capabilities.Capability;

/**
 * Reflection bridge to the Cursed Fate mod (no compile dependency). Everything Cursed Fate keeps about a player lives in
 * one capability (PlayerVariables); the abilities themselves are static procedures that take the entity. The body is a
 * real ServerPlayer, so the very same procedures a player's key press would run can be run for it.
 */
public final class CfBridge {
    private static boolean tried;
    private static boolean ok;
    private static Capability<Object> cap;
    private static Class<?> pvClass;
    private static Method syncMethod;
    private static final Map<String, Field> FIELDS = new HashMap<>();
    private static final Map<String, Method> PROCS = new HashMap<>();
    private static final Set<String> MISSING = new HashSet<>();
    private static boolean warnedInvoke;

    private CfBridge() {
    }

    /** True when the cursedfate mod is loaded, the bridge found everything it needs, and the config allows it. */
    public static boolean available() {
        if (!PaqganeConfig.SENTIENT_REAL_CURSED_FATE.get()) {
            return false;
        }
        init();
        return ok;
    }

    @SuppressWarnings("unchecked")
    private static synchronized void init() {
        if (tried) {
            return;
        }
        tried = true;
        if (!CursedFate.loaded()) {
            return;
        }
        try {
            Class<?> vars = Class.forName("cursedfate.network.CursedfateModVariables");
            pvClass = Class.forName("cursedfate.network.CursedfateModVariables$PlayerVariables");
            cap = (Capability<Object>) vars.getField("PLAYER_VARIABLES_CAPABILITY").get(null);
            syncMethod = pvClass.getMethod("syncPlayerVariables", Entity.class);
            ok = cap != null;
            PaqganeMod.LOGGER.info("Paqgane: Cursed Fate bridge ready, the sentient body will use real Cursed Fate abilities");
        } catch (Throwable t) {
            PaqganeMod.LOGGER.warn("Paqgane: could not hook into Cursed Fate, using the built-in fallback moves", t);
        }
    }

    @Nullable
    public static Object vars(Entity e) {
        if (!available()) {
            return null;
        }
        try {
            return e.getCapability(cap, null).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static synchronized Field field(String name) {
        if (MISSING.contains("f:" + name)) {
            return null;
        }
        Field f = FIELDS.get(name);
        if (f == null) {
            try {
                f = pvClass.getField(name);
                FIELDS.put(name, f);
            } catch (Throwable t) {
                MISSING.add("f:" + name);
                return null;
            }
        }
        return f;
    }

    public static double num(@Nullable Object pv, String name) {
        Field f = pv == null ? null : field(name);
        if (f == null) {
            return 0.0;
        }
        try {
            Object v = f.get(pv);
            return v instanceof Number n ? n.doubleValue() : 0.0;
        } catch (Throwable t) {
            return 0.0;
        }
    }

    public static boolean flag(@Nullable Object pv, String name) {
        Field f = pv == null ? null : field(name);
        if (f == null) {
            return false;
        }
        try {
            return f.get(pv) instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String text(@Nullable Object pv, String name) {
        Field f = pv == null ? null : field(name);
        if (f == null) {
            return "";
        }
        try {
            Object v = f.get(pv);
            return v == null ? "" : v.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public static void put(@Nullable Object pv, String name, Object value) {
        Field f = pv == null ? null : field(name);
        if (f == null) {
            return;
        }
        try {
            f.set(pv, value);
        } catch (Throwable ignored) {
            // wrong type for that field, leave it
        }
    }

    public static void sync(Entity e, @Nullable Object pv) {
        if (pv == null || syncMethod == null) {
            return;
        }
        try {
            syncMethod.invoke(pv, e);
        } catch (Throwable ignored) {
            // the body has no real client, a failed sync only matters to a human watching
        }
    }

    @Nullable
    private static synchronized Method proc(String cls, Class<?>... params) {
        String key = cls + Integer.toString(params.length);
        if (MISSING.contains(key)) {
            return null;
        }
        Method m = PROCS.get(key);
        if (m == null) {
            try {
                m = Class.forName("cursedfate.procedures." + cls).getMethod("execute", params);
                PROCS.put(key, m);
            } catch (Throwable t) {
                MISSING.add(key);
                return null;
            }
        }
        return m;
    }

    private static boolean invoke(@Nullable Method m, Object... args) {
        if (m == null) {
            return false;
        }
        try {
            m.invoke(null, args);
            return true;
        } catch (InvocationTargetException ex) {
            if (!warnedInvoke) {
                warnedInvoke = true;
                PaqganeMod.LOGGER.warn("Paqgane: a Cursed Fate procedure threw an error for the body (logged once)", ex.getCause());
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Runs what pressing ability key 1-7 does. */
    public static boolean press(int slot, LevelAccessor level, Entity e) {
        Method m = proc("Ability" + slot + "OnKeyPressedProcedure", LevelAccessor.class, double.class, double.class, double.class, Entity.class);
        return invoke(m, level, e.getX(), e.getY(), e.getZ(), e);
    }

    /** Runs what letting go of ability key 1-7 does. */
    public static void release(int slot, Entity e) {
        Method m = proc("Ability" + slot + "OnKeyReleasedProcedure", Entity.class);
        invoke(m, e);
    }

    /** Picks a random technique, exactly as joining the world for the first time does. */
    public static void rollTechnique(Entity e) {
        Method m = proc("SetTechniqueProcedure", Entity.class);
        invoke(m, e);
    }
}
