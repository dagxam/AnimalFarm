package ru.dagxam.animalfarm;

import org.bukkit.Location;

/** Immutable cached result of a feeder area validation. */
public record FarmAreaCache(
        boolean valid,
        FarmObjectType type,
        Location center,
        long checkedAtTick
) {
    public boolean isExpired(long currentTick, long ttlTicks) {
        return currentTick - checkedAtTick >= ttlTicks;
    }
}
