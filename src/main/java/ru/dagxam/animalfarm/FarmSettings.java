package ru.dagxam.animalfarm;

import org.bukkit.configuration.file.FileConfiguration;

/** Централизованное чтение и валидация настроек AnimalFarm. */
public final class FarmSettings {
    private final FileConfiguration config;

    public FarmSettings(FileConfiguration config) { this.config = config; }

    public long feederCheckIntervalTicks() { return Math.max(20L, Math.max(1L, config.getLong("feeder.check-interval-seconds", 5L)) * 20L); }
    public int maxBreedingPairsPerDay() { return Math.max(1, config.getInt("feeder.max-breeding-pairs-per-day", 10)); }
    public int animalCapacity() { return Math.max(2, config.getInt("feeder.capacity", 20)); }
    public int fishCapacity() { return Math.max(2, config.getInt("aquarium.capacity", 30)); }
    public int penMaxRadius() { return Math.max(4, config.getInt("pen.max-radius", 16)); }
    public int penVerticalRange() { return Math.max(3, config.getInt("pen.vertical-range", 5)); }
    public int aquariumMaxRadius() { return Math.max(4, config.getInt("aquarium.max-radius", 16)); }
    public int aquariumVerticalRange() { return Math.max(3, config.getInt("aquarium.vertical-range", 5)); }
    public boolean aquariumEnabled() { return config.getBoolean("aquarium.enabled", true); }
    public boolean aquariumOwnerOnlyHarvest() { return config.getBoolean("aquarium.owner-only-harvest", true); }
    public boolean ownershipEnabled() { return config.getBoolean("ownership.enabled", true); }
    public int hudRange() { return Math.max(1, config.getInt("hud.range", 6)); }
    public int milkFeedingsMin() { return Math.max(1, config.getInt("baby.milk-feedings-min", 1)); }
    public int milkFeedingsMax() { return Math.max(milkFeedingsMin(), config.getInt("baby.milk-feedings-max", 3)); }
    public int goldenCyclesMin() { return Math.max(2, config.getInt("golden.cycles-min", 2)); }
    public int goldenCyclesMax() { return Math.max(goldenCyclesMin(), config.getInt("golden.cycles-max", 3)); }
    public int animalFoodMin() { return Math.max(1, config.getInt("feeding.daily-consumption-min", 3)); }
    public int animalFoodMax() { return Math.max(animalFoodMin(), config.getInt("feeding.daily-consumption-max", 5)); }
}
