package ru.dagxam.animalfarm;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Проверяет конфигурацию и заменяет некорректные значения безопасными. */
public final class ConfigValidator {
    private final JavaPlugin plugin;
    private final FileConfiguration config;
    private boolean changed;

    public ConfigValidator(JavaPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
    }

    public void validateAndRepair() {
        changed = false;

        positiveLong("feeder.check-interval-seconds", 5, 1, 3600);
        positiveInt("feeder.max-breeding-pairs-per-day", 10, 1, 1000);
        positiveInt("feeder.water-max-stack", 16, 1, 64);
        positiveInt("feeder.capacity", 20, 2, 10000);
        positiveInt("pen.max-radius", 16, 4, 128);
        positiveInt("pen.vertical-range", 5, 3, 64);
        positiveInt("aquarium.max-radius", 16, 4, 128);
        positiveInt("aquarium.vertical-range", 5, 3, 64);
        positiveInt("aquarium.capacity", 30, 2, 10000);
        positiveInt("hud.range", 6, 1, 64);
        positiveInt("animal-genders.male-chance", 50, 0, 100);
        positiveInt("animal-genders.female-chance", 50, 0, 100);
        positiveInt("feeding.daily-consumption-min", 3, 1, 1000);
        positiveInt("feeding.daily-consumption-max", 5, config.getInt("feeding.daily-consumption-min", 3), 1000);
        positiveInt("golden.cycles-min", 2, 2, 1000);
        positiveInt("golden.cycles-max", 3, config.getInt("golden.cycles-min", 2), 1000);
        positiveInt("baby.milk-feedings-min", 1, 1, 1000);
        positiveInt("baby.milk-feedings-max", 3, config.getInt("baby.milk-feedings-min", 1), 1000);
        positiveInt("fishing.live-catch-chance", 25, 0, 100);
        validatePair("production.chicken.eggs-min", "production.chicken.eggs-max", 1, 1, 64);
        validatePair("production.wool.min", "production.wool.max", 1, 0, 64);
        validatePair("milking.milk-min", "milking.milk-max", 1, 1, 64);

        if (changed) {
            plugin.saveConfig();
            plugin.getLogger().warning("AnimalFarm: конфигурация содержала некорректные значения. Они заменены безопасными.");
        }
    }

    private void positiveInt(String path, int fallback, int min, int max) {
        int value = config.getInt(path, fallback);
        int repaired = clamp(value, min, max);
        if (value != repaired || !config.contains(path)) repair(path, repaired, fallback);
    }

    private void positiveLong(String path, long fallback, long min, long max) {
        long value = config.getLong(path, fallback);
        long repaired = Math.max(min, Math.min(max, value));
        if (value != repaired || !config.contains(path)) repair(path, repaired, fallback);
    }

    private void validatePair(String minPath, String maxPath, int fallback, int min, int max) {
        int minValue = clamp(config.getInt(minPath, fallback), min, max);
        int maxValue = clamp(config.getInt(maxPath, minValue), minValue, max);
        if (config.getInt(minPath, fallback) != minValue || !config.contains(minPath)) repair(minPath, minValue, fallback);
        if (config.getInt(maxPath, minValue) != maxValue || !config.contains(maxPath)) repair(maxPath, maxValue, minValue);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void repair(String path, long value, long fallback) {
        config.set(path, value);
        changed = true;
        plugin.getLogger().warning("AnimalFarm: параметр '" + path + "' имеет недопустимое значение и исправлен на " + value + " (безопасное значение по умолчанию: " + fallback + ").");
    }
}