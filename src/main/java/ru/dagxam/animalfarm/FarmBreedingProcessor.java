package ru.dagxam.animalfarm;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Barrel;
import org.bukkit.entity.Animals;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.Inventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Отвечает только за ежедневное размножение животных в загоне. */
public final class FarmBreedingProcessor {
    private final AnimalFarmPlugin plugin;
    private final FarmSettings settings;
    private final FarmCapacityService capacityService;
    private final FarmWaterService waterService;
    private final FarmInventoryService inventoryService;
    private final NamespacedKey nextBreedDayKey;

    public FarmBreedingProcessor(AnimalFarmPlugin plugin, FarmSettings settings, FarmCapacityService capacityService, FarmWaterService waterService, FarmInventoryService inventoryService, NamespacedKey nextBreedDayKey) {
        this.plugin = plugin;
        this.settings = settings;
        this.capacityService = capacityService;
        this.waterService = waterService;
        this.inventoryService = inventoryService;
        this.nextBreedDayKey = nextBreedDayKey;
    }

    public void process(Barrel feeder, List<Animals> animals, long day, int cycles) {
        long nextBreedDay = feeder.getPersistentDataContainer().getOrDefault(nextBreedDayKey, PersistentDataType.LONG, -1L);
        if (nextBreedDay >= 0 && day < nextBreedDay) return;
        if (!capacityService.canAddAnimal(animals.size(), 1)) {
            scheduleNextDay(feeder, day, cycles);
            return;
        }

        Inventory inventory = feeder.getInventory();
        int bred = 0;
        Map<EntityType, List<Animals>> groups = new HashMap<>();
        for (Animals animal : animals) {
            if (animal.isAdult() && animal.canBreed() && !animal.isLoveMode()) {
                groups.computeIfAbsent(animal.getType(), ignored -> new ArrayList<>()).add(animal);
            }
        }

        outer:
        for (int cycle = 0; cycle < cycles; cycle++) {
            for (List<Animals> group : groups.values()) {
                if (bred >= settings.maxBreedingPairsPerDay() || !capacityService.canAddAnimal(animals.size() + bred, 1)) break outer;
                Animals male = null;
                Animals female = null;
                for (Animals animal : group) {
                    AnimalGender gender = plugin.genderManager().getOrAssign(animal);
                    if (gender == AnimalGender.MALE && male == null) male = animal;
                    else if (gender == AnimalGender.FEMALE && female == null) female = animal;
                    if (male != null && female != null) break;
                }
                if (male == null || female == null || !plugin.genderManager().canBreed(male, female)) continue;

                int foodCost = random(settings.animalFoodMin(), settings.animalFoodMax());
                if (!inventoryService.consumeAnimalFood(male.getType(), inventory, foodCost * 2)) continue;
                if (!waterService.consumeWater(inventory, 1)) continue;

                male.setLoveModeTicks(600);
                female.setLoveModeTicks(600);
                bred++;
            }
        }
        scheduleNextDay(feeder, day, cycles);
    }

    private void scheduleNextDay(Barrel feeder, long day, int cycles) {
        feeder.getPersistentDataContainer().set(nextBreedDayKey, PersistentDataType.LONG, day + (cycles > 1 ? 1 : random(1, 3)));
    }

    private int random(int min, int max) {
        return ThreadLocalRandom.current().nextInt(Math.min(min, max), Math.max(min, max) + 1);
    }
}
