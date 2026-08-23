package ru.dagxam.animalfarm;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Animals;
import org.bukkit.entity.EntityType;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** Управляет назначением и постоянным хранением пола поддерживаемых животных в PDC. */
public final class AnimalGenderManager {
    private final AnimalFarmPlugin plugin;
    private final NamespacedKey genderKey;

    public AnimalGenderManager(AnimalFarmPlugin plugin) {
        this.plugin = plugin;
        this.genderKey = new NamespacedKey(plugin, "animal_gender");
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("animal-genders.enabled", true);
    }

    public boolean supported(Animals animal) {
        if (!enabled()) return false;
        String key = animal.getType().name().toLowerCase(Locale.ROOT);
        return switch (animal.getType()) {
            case COW, SHEEP, GOAT, PIG, CHICKEN -> plugin.getConfig().getBoolean("animal-genders.animals." + key, true);
            default -> false;
        };
    }

    public AnimalGender getOrAssign(Animals animal) {
        if (!supported(animal)) return AnimalGender.FEMALE;
        PersistentDataContainer pdc = animal.getPersistentDataContainer();
        String raw = pdc.get(genderKey, PersistentDataType.STRING);
        if (raw != null) {
            try { return AnimalGender.valueOf(raw); } catch (IllegalArgumentException ignored) { }
        }
        AnimalGender gender = randomGender();
        pdc.set(genderKey, PersistentDataType.STRING, gender.name());
        return gender;
    }

    public void assignRandomIfSupported(Animals animal) { getOrAssign(animal); }

    public boolean canBreed(Animals first, Animals second) {
        if (!plugin.getConfig().getBoolean("animal-genders.breeding.require-male-and-female", true)) return first.getType() == second.getType();
        if (!supported(first) || !supported(second)) return first.getType() == second.getType();
        return first.getType() == second.getType() && getOrAssign(first) != getOrAssign(second);
    }

    public boolean canGiveMilk(Animals animal) {
        if (!plugin.getConfig().getBoolean("animal-genders.milk.females-only", true)) return true;
        return !supported(animal) || getOrAssign(animal) == AnimalGender.FEMALE;
    }

    public AnimalGender randomGender() {
        int male = clamp(plugin.getConfig().getInt("animal-genders.male-chance", 50));
        int female = clamp(plugin.getConfig().getInt("animal-genders.female-chance", 50));
        int total = male + female;
        if (total <= 0) return ThreadLocalRandom.current().nextBoolean() ? AnimalGender.MALE : AnimalGender.FEMALE;
        return ThreadLocalRandom.current().nextInt(total) < male ? AnimalGender.MALE : AnimalGender.FEMALE;
    }

    private int clamp(int value) { return Math.max(0, Math.min(100, value)); }
}
