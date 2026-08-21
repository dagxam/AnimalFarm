package ru.dagxam.animalfarm;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Централизованное чтение и валидация настроек AnimalFarm.
 *
 * Основная задача класса — не размазывать getConfig() по всем менеджерам
 * и не допускать некорректных значений из config.yml.
 */
public final class FarmSettings {
    private final FileConfiguration config;

    public FarmSettings(FileConfiguration config) {
        this.config = config;
    }

    public long feederCheckIntervalTicks() {
        long seconds = Math.max(1L, config.getLong("feeder.check-interval-seconds", 5L));
        return Math.max(20L, seconds * 20L);
    }

    public int maxBreedingPairsPerDay() {
        return Math.max(1, config.getInt("feeder.max-breeding-pairs-per-day", 10));
    }

    public int penRadius() {
        return Math.max(4, config.getInt("pen.max-radius", 16));
    }

    public int penVerticalRange() {
        return Math.max(3, config.getInt("pen.vertical-range", 5));
    }

    public int aquariumRadius() {
        return Math.max(4, config.getInt("aquarium.max-radius", 16));
    }

    public int aquariumVerticalRange() {
        return Math.max(3, config.getInt("aquarium.vertical-range", 5));
    }

    public int hudRange() {
        return Math.max(1, config.getInt("hud.range", 6));
    }

    public int milkFeedingsMin() {
        int min = Math.max(1, config.getInt("baby.milk-feedings-min", 1));
        int max = Math.max(min, config.getInt("baby.milk-feedings-max", 3));
        return Math.min(min, max);
    }

    public int milkFeedingsMax() {
        int min = Math.max(1, config.getInt("baby.milk-feedings-min", 1));
        return Math.max(min, config.getInt("baby.milk-feedings-max", 3));
    }
}
