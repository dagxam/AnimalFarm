package ru.dagxam.animalfarm;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Централизованное чтение и валидация настроек AnimalFarm. */
public final class FarmSettings {
    private final FileConfiguration config;

    public FarmSettings(FileConfiguration config) {
        this.config = config;
    }

    public static FarmSettings load(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        validate(plugin, config);
        return new FarmSettings(config);
    }

    private static void validate(JavaPlugin plugin, FileConfiguration c) {
        validateRange(plugin, c, "feeder.check-interval-seconds", 1, 3600, 5);
        validateRange(plugin, c, "feeder.eating-distance", 1, 32, 3);
        validateRange(plugin, c, "feeder.max-breeding-pairs-per-day", 1, 1000, 10);
        validateRange(plugin, c, "feeder.water-max-stack", 1, 64, 16);
        validateRange(plugin, c, "feeder.capacity", 2, 1000, 20);
        validateRange(plugin, c, "pen.max-radius", 4, 128, 16);
        validateRange(plugin, c, "pen.vertical-range", 3, 64, 5);
        validateRange(plugin, c, "aquarium.max-radius", 4, 128, 16);
        validateRange(plugin, c, "aquarium.vertical-range", 3, 64, 5);
        validateRange(plugin, c, "aquarium.capacity", 2, 1000, 30);
        validateRange(plugin, c, "hud.range", 1, 64, 6);
        validateRange(plugin, c, "animal-genders.male-chance", 0, 100, 50);
        validateRange(plugin, c, "animal-genders.female-chance", 0, 100, 50);
        validateRange(plugin, c, "feeding.daily-consumption-min", 1, 64, 3);
        validateRange(plugin, c, "feeding.daily-consumption-max", 1, 64, 5);
        validateRange(plugin, c, "golden.cycles-min", 1, 1000, 2);
        validateRange(plugin, c, "golden.cycles-max", 1, 1000, 3);
        validateRange(plugin, c, "fishing.live-catch-chance", 0, 100, 25);
        validateRange(plugin, c, "milking.milk-min", 1, 64, 1);
        validateRange(plugin, c, "milking.milk-max", 1, 64, 3);
        validateRange(plugin, c, "baby.milk-feedings-min", 1, 64, 1);
        validateRange(plugin, c, "baby.milk-feedings-max", 1, 64, 3);
        validateRange(plugin, c, "production.chicken.eggs-min", 0, 64, 5);
        validateRange(plugin, c, "production.chicken.eggs-max", 0, 64, 10);
        validateRange(plugin, c, "production.wool.min", 0, 64, 2);
        validateRange(plugin, c, "production.wool.max", 0, 64, 3);

        normalizePair(plugin, c, "animal-genders.male-chance", "animal-genders.female-chance", 50, 50);
        normalizeMinMax(plugin, c, "feeding.daily-consumption-min", "feeding.daily-consumption-max");
        normalizeMinMax(plugin, c, "golden.cycles-min", "golden.cycles-max");
        normalizeMinMax(plugin, c, "milking.milk-min", "milking.milk-max");
        normalizeMinMax(plugin, c, "baby.milk-feedings-min", "baby.milk-feedings-max");
        normalizeMinMax(plugin, c, "production.chicken.eggs-min", "production.chicken.eggs-max");
        normalizeMinMax(plugin, c, "production.wool.min", "production.wool.max");
    }

    private static void validateRange(JavaPlugin plugin, FileConfiguration c, String path, int min, int max, int fallback) {
        int value = c.getInt(path, fallback);
        if (value < min || value > max) {
            plugin.getLogger().warning("Неверное значение " + path + " = " + value + ". Используется безопасное значение: " + fallback);
            c.set(path, fallback);
        }
    }

    private static void normalizeMinMax(JavaPlugin plugin, FileConfiguration c, String minPath, String maxPath) {
        int min = c.getInt(minPath);
        int max = c.getInt(maxPath);
        if (max < min) {
            plugin.getLogger().warning("Неверная пара настроек: " + maxPath + " меньше " + minPath + ". " + maxPath + " установлен в " + min);
            c.set(maxPath, min);
        }
    }

    private static void normalizePair(JavaPlugin plugin, FileConfiguration c, String malePath, String femalePath, int maleFallback, int femaleFallback) {
        int male = c.getInt(malePath, maleFallback);
        int female = c.getInt(femalePath, femaleFallback);
        if (male + female != 100) {
            plugin.getLogger().warning("Сумма " + malePath + " и " + femalePath + " должна быть 100. Используются значения " + maleFallback + "/" + femaleFallback);
            c.set(malePath, maleFallback);
            c.set(femalePath, femaleFallback);
        }
    }

    public long feederCheckIntervalTicks() { return config.getLong("feeder.check-interval-seconds", 5L) * 20L; }
    public double feederEatingDistance() { return config.getDouble("feeder.eating-distance", 2.5D); }
    public int waterMaxStack() { return config.getInt("feeder.water-max-stack", 16); }
    public int maxBreedingPairsPerDay() { return config.getInt("feeder.max-breeding-pairs-per-day", 10); }
    public int animalCapacity() { return config.getInt("feeder.capacity", 20); }
    public int fishCapacity() { return config.getInt("aquarium.capacity", 30); }
    public int penMaxRadius() { return config.getInt("pen.max-radius", 16); }
    public int penVerticalRange() { return config.getInt("pen.vertical-range", 5); }
    public int aquariumMaxRadius() { return config.getInt("aquarium.max-radius", 16); }
    public int aquariumVerticalRange() { return config.getInt("aquarium.vertical-range", 5); }
    public boolean aquariumEnabled() { return config.getBoolean("aquarium.enabled", true); }
    public boolean aquariumOwnerOnlyHarvest() { return config.getBoolean("aquarium.owner-only-harvest", true); }
    public boolean ownershipEnabled() { return config.getBoolean("ownership.enabled", true); }
    public int hudRange() { return config.getInt("hud.range", 6); }
    public int milkFeedingsMin() { return config.getInt("baby.milk-feedings-min", 1); }
    public int milkFeedingsMax() { return config.getInt("baby.milk-feedings-max", 3); }
    public int goldenCyclesMin() { return config.getInt("golden.cycles-min", 2); }
    public int goldenCyclesMax() { return config.getInt("golden.cycles-max", 3); }
    public int animalFoodMin() { return config.getInt("feeding.daily-consumption-min", 3); }
    public int animalFoodMax() { return config.getInt("feeding.daily-consumption-max", 5); }
}
