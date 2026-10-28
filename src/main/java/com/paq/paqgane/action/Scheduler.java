package com.paq.paqgane.action;

import com.paq.paqgane.PaqganeMod;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "paqgane")
public class Scheduler {
    private static final List<Job> JOBS = new ArrayList<>();

    public static void runLater(int ticks, Runnable r) {
        JOBS.add(new Job(ticks, r));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || JOBS.isEmpty()) {
            return;
        }
        List<Runnable> due = new ArrayList<>();
        Iterator<Job> it = JOBS.iterator();
        while (it.hasNext()) {
            Job j = it.next();
            if (--j.ticks <= 0) {
                due.add(j.run);
                it.remove();
            }
        }
        for (Runnable r : due) {
            try {
                r.run();
            } catch (Exception e) {
                PaqganeMod.LOGGER.warn("Scheduled job failed", e);
            }
        }
    }

    private static class Job {
        int ticks;
        final Runnable run;

        Job(int ticks, Runnable run) {
            this.ticks = ticks;
            this.run = run;
        }
    }
}
