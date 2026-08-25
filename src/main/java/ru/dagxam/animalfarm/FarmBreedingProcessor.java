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

    public FarmBreedingProcessor(AnimalFarmPlugin plugin, FarmSettings settings, FarmCapacityService capacityService,
                                 FarmWaterService waterService, FarmInventoryService inventoryService,
                                 NamespacedKey nextBreedDayKey) {
        this.plugin = plugin;
        this.settings = settings;
        this.capacityService = capacityService;
        this.waterService = waterService;
        this.inventoryService = inventoryService;
        this.nextBreedDayKey = nextBreedDayKey;
    }

    public void process(Barrel feeder, List<Animals> animals, long day, int cycles) {
        long nextBreedDay = feeder.getPersistentDataContainer()
                .getOrDefault(nextBreedDayKey, PersistentDataType.LONG, -1L);
        if (nextBreedDay >= 0 && day < nextBreedDay) return;

        if (!capacityService.canAddAnimal(animals.size(), 1)) {
            scheduleNextDay(feeder, day, cycles);
            return;
        }

        Inventory inventory = feeder.getInventory();
        Map<EntityType, List<Animals>> groups = new HashMap<>();
        for (Animals animal : animals) {
            if (animal.isAdult() && animal.canBreed() && !animal.isLoveMode()) {
                groups.computeIfAbsent(animal.getType(), ignored -> new ArrayList<>()).add(animal);
            }
        }

        int bred = 0;
        int maxPairs = Math.max(0, settings.maxBreedingPairsPerDay());
        int passes = Math.max(1, cycles);

        outer:
        for (int pass = 0; pass < passes; pass++) {
            for (Map.Entry<EntityType, List<Animals>> entry : groups.entrySet()) {
                if (bred >= maxPairs || !capacityService.canAddAnimal(animals.size() + bred, 1)) break outer;

                List<Animals> males = new ArrayList<>();
                List<Animals> females = new ArrayList<>();
                for (Animals animal : entry.getValue()) {
                    AnimalGender gender = plugin.genderManager().getOrAssign(animal);
                    if (gender == AnimalGender.MALE) males.add(animal);
                    else if (gender == AnimalGender.FEMALE) females.add(animal);
                }

                int pairs = Math.min(males.size(), females.size());
                for (int index = 0; index < pairs; index++) {
                    if (bred >= maxPairs || !capacityService.canAddAnimal(animals.size() + bred, 1)) break outer;

                    Animals male = males.get(index);
                    Animals female = females.get(index);
                    if (!plugin.genderManager().canBreed(male, female)) continue;

                    int foodCost = random(settings.animalFoodMin(), settings.animalFoodMax());
                    if (!inventoryService.consumeAnimalFood(entry.getKey(), inventory, foodCost * 2)) break;
                    if (!waterService.consumeWater(inventory, 1)) break outer;

                    male.setLoveModeTicks(600);
                    female.setLoveModeTicks(600);
                    bred++;
                }
            }
        }

        scheduleNextDay(feeder, day, cycles);
    }

    private void scheduleNextDay(Barrel feeder, long day, int cycles) {
        feeder.getPersistentDataContainer().set(
                nextBreedDayKey,
                PersistentDataType.LONG,
                day + (cycles > 1 ? 1 : random(1, 3))
        );
    }

    private int random(int min, int max) {
        int lower = Math.min(min, max);
        int upper = Math.max(min, max);
        return ThreadLocalRandom.current().nextInt(lower, upper + 1);
    }
}
