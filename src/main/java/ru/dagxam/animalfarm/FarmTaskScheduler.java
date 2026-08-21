package ru.dagxam.animalfarm;

import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;

/** Owns plugin repeating tasks and guarantees that they are cancelled on shutdown. */
public final class FarmTaskScheduler {

    private final AnimalFarmPlugin plugin;
    private BukkitTask farmTask;

    public FarmTaskScheduler(AnimalFarmPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void startFarmTask(Runnable processor, long intervalTicks) {
        stopFarmTask();
        farmTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                processor,
                intervalTicks,
                intervalTicks
        );
    }

    public void restartFarmTask(Runnable processor, long intervalTicks) {
        startFarmTask(processor, intervalTicks);
    }

    public void stop() {
        stopFarmTask();
    }

    private void stopFarmTask() {
        if (farmTask != null) {
            farmTask.cancel();
            farmTask = null;
        }
    }
}
