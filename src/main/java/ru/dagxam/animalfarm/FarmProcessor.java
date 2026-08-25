package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.entity.Animals;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fish;
import org.bukkit.inventory.Inventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Координирует обработку фермы; специализированная логика вынесена в отдельные обработчики. */
public final class FarmProcessor {
    private final AnimalFarmPlugin plugin;
    private final FarmSettings settings;
    private final FarmEntityService entityService;
    private final FarmCapacityService capacityService;
    private final FarmWaterService waterService;
    private final FarmInventoryService inventoryService;
    private final FarmBreedingProcessor breedingProcessor;
    private final FarmProductionProcessor productionProcessor;
    private final NamespacedKey nextBreedDayKey;
    private final NamespacedKey productionDayKey;
    private final NamespacedKey goldenDayKey;
    private final NamespacedKey goldenCyclesKey;
    private final NamespacedKey farmStateKey;

    public FarmProcessor(AnimalFarmPlugin plugin, FarmSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.entityService = new FarmEntityService(plugin, settings);
        this.capacityService = new FarmCapacityService(settings);
        this.waterService = new FarmWaterService();
        this.inventoryService = new FarmInventoryService(plugin.farmFoodService());
        this.nextBreedDayKey = new NamespacedKey(plugin, "next_breed_day");
        this.productionDayKey = new NamespacedKey(plugin, "production_day");
        this.goldenDayKey = new NamespacedKey(plugin, "golden_day");
        this.goldenCyclesKey = new NamespacedKey(plugin, "golden_cycles");
        this.farmStateKey = new NamespacedKey(plugin, "farm_state");
        this.breedingProcessor = new FarmBreedingProcessor(plugin, settings, capacityService, waterService, inventoryService, nextBreedDayKey);
        this.productionProcessor = new FarmProductionProcessor(plugin, settings, inventoryService, productionDayKey);
    }

    public void process(FarmObjectKey key, FarmObjectType type, long tick) {
        Location location = key.location(plugin.getServer());
        if (location.getWorld() == null || !(location.getBlock().getState() instanceof Barrel barrel)) return;
        FarmAreaCache area = plugin.farmObjectManager().areaAnalyzer().analyze(key, type, plugin.getServer());
        long day = location.getWorld().getFullTime() / 24000L;
        if (!area.valid()) { setState(barrel, type == FarmObjectType.LAND_FEEDER ? "OPEN" : "NO_WATER"); barrel.update(true, false); return; }
        if (type == FarmObjectType.LAND_FEEDER) processLand(barrel, area, day);
        else if (settings.aquariumEnabled()) processAquarium(barrel, area, day);
        barrel.update(true, false);
    }

    private void processLand(Barrel feeder, FarmAreaCache area, long day) {
        List<Animals> animals = entityService.findAnimals(area);
        for (Animals animal : animals) plugin.genderManager().assignRandomIfSupported(animal);
        updateLandState(feeder, animals);
        if (!isHealthy(feeder)) return;
        int cycles = goldenCycles(feeder, feeder.getInventory(), day);
        breedingProcessor.process(feeder, animals, day, cycles);
        productionProcessor.process(feeder, animals, day, cycles);
    }

    private void updateLandState(Barrel feeder, List<Animals> animals) {
        Inventory inventory = feeder.getInventory();
        String state;
        if (animals.size() >= settings.animalCapacity()) state = "FULL";
        else if (!hasFoodForAnimals(inventory, animals, settings.animalFoodMin())) state = "NO_FOOD";
        else if (!waterService.hasWater(inventory, 1)) state = "NO_WATER";
        else state = "HEALTHY";
        setState(feeder, state);
    }

    private boolean hasFoodForAnimals(Inventory inventory, List<Animals> animals, int minimum) {
        for (Animals animal : animals) if (inventoryService.countAnimalFood(animal.getType(), inventory) >= minimum) return true;
        return false;
    }

    private boolean isHealthy(Barrel feeder) { return "HEALTHY".equals(feeder.getPersistentDataContainer().get(farmStateKey, PersistentDataType.STRING)); }

    private int goldenCycles(Barrel feeder, Inventory inventory, long day) {
        if (getLong(feeder, goldenDayKey, -1L) == day) return feeder.getPersistentDataContainer().getOrDefault(goldenCyclesKey, PersistentDataType.INTEGER, 1);
        int cycles = 1;
        if (inventoryService.removeOne(inventory, Material.GOLDEN_APPLE) || inventoryService.removeOne(inventory, Material.GOLDEN_CARROT)) cycles = random(settings.goldenCyclesMin(), settings.goldenCyclesMax());
        feeder.getPersistentDataContainer().set(goldenDayKey, PersistentDataType.LONG, day); feeder.getPersistentDataContainer().set(goldenCyclesKey, PersistentDataType.INTEGER, cycles); return cycles;
    }

    private void processAquarium(Barrel aquarium, FarmAreaCache area, long day) {
        List<Fish> fish = entityService.findFish(area); Inventory inventory = aquarium.getInventory();
        if (fish.size() >= settings.fishCapacity()) { setState(aquarium, "FULL"); return; }
        if (inventoryService.countFishFood(inventory) < settings.animalFoodMin()) { setState(aquarium, "NO_FOOD"); return; }
        setState(aquarium, "HEALTHY"); long nextBreedDay = getLong(aquarium, nextBreedDayKey, -1L); if (nextBreedDay >= 0 && day < nextBreedDay) return;
        int cycles = goldenCycles(aquarium, inventory, day), created = 0; Map<EntityType, List<Fish>> groups = new HashMap<>();
        for (Fish current : fish) groups.computeIfAbsent(current.getType(), ignored -> new ArrayList<>()).add(current);
        outer: for (int cycle = 0; cycle < cycles; cycle++) for (List<Fish> group : groups.values()) for (int index = 0; index + 1 < group.size(); index += 2) {
            if (created >= settings.maxBreedingPairsPerDay() || !capacityService.canAddFish(fish.size() + created, 1)) break outer;
            if (!inventoryService.consumeFishFood(inventory, random(settings.animalFoodMin(), settings.animalFoodMax()))) continue;
            Location spawn = findWater(area, group.get(index).getLocation()); if (spawn == null || spawn.getWorld() == null) break outer;
            spawn.getWorld().spawnEntity(spawn, group.get(index).getType()); created++;
        }
        aquarium.getPersistentDataContainer().set(nextBreedDayKey, PersistentDataType.LONG, day + (cycles > 1 ? 1 : random(1, 3)));
    }

    private Location findWater(FarmAreaCache area, Location fallback) {
        if (fallback != null && area.contains(fallback) && fallback.getBlock().getType() == Material.WATER) return fallback.clone();
        World world = area.center().getWorld(); if (world == null) return null;
        for (FarmAreaCache.BlockPosition position : area.interior()) if (world.getBlockAt(position.x(), position.y(), position.z()).getType() == Material.WATER) return new Location(world, position.x() + 0.5, position.y() + 0.2, position.z() + 0.5);
        return null;
    }

    private void setState(Barrel feeder, String state) { feeder.getPersistentDataContainer().set(farmStateKey, PersistentDataType.STRING, state); }
    private long getLong(Barrel barrel, NamespacedKey key, long fallback) { return barrel.getPersistentDataContainer().getOrDefault(key, PersistentDataType.LONG, fallback); }
    private int random(int min, int max) { return ThreadLocalRandom.current().nextInt(Math.min(min, max), Math.max(min, max) + 1); }
}
