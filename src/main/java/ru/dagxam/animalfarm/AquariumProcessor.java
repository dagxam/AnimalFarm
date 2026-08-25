package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fish;
import org.bukkit.inventory.Inventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Отдельно обрабатывает состояние и размножение рыб в аквариуме. */
public final class AquariumProcessor {
    private final FarmSettings settings;
    private final FarmCapacityService capacityService;
    private final FarmInventoryService inventoryService;
    private final NamespacedKey nextBreedDayKey;
    private final NamespacedKey farmStateKey;

    public AquariumProcessor(FarmSettings settings, FarmCapacityService capacityService,
                             FarmInventoryService inventoryService, NamespacedKey nextBreedDayKey,
                             NamespacedKey farmStateKey) {
        this.settings = settings;
        this.capacityService = capacityService;
        this.inventoryService = inventoryService;
        this.nextBreedDayKey = nextBreedDayKey;
        this.farmStateKey = farmStateKey;
    }

    public void process(Barrel aquarium, FarmAreaCache area, List<Fish> fish, long day, int cycles) {
        Inventory inventory = aquarium.getInventory();
        if (fish.size() >= settings.fishCapacity()) { setState(aquarium, "FULL"); return; }
        if (inventoryService.countFishFood(inventory) < settings.animalFoodMin()) { setState(aquarium, "NO_FOOD"); return; }
        setState(aquarium, "HEALTHY");
        long nextBreedDay = aquarium.getPersistentDataContainer().getOrDefault(nextBreedDayKey, PersistentDataType.LONG, -1L);
        if (nextBreedDay >= 0 && day < nextBreedDay) return;

        int created = 0;
        Map<EntityType, List<Fish>> groups = new HashMap<>();
        for (Fish current : fish) groups.computeIfAbsent(current.getType(), ignored -> new ArrayList<>()).add(current);

        outer:
        for (int cycle = 0; cycle < Math.max(1, cycles); cycle++) {
            for (List<Fish> group : groups.values()) {
                for (int index = 0; index + 1 < group.size(); index += 2) {
                    if (created >= settings.maxBreedingPairsPerDay() || !capacityService.canAddFish(fish.size() + created, 1)) break outer;
                    int foodCost = random(settings.animalFoodMin(), settings.animalFoodMax());
                    if (!inventoryService.consumeFishFood(inventory, foodCost)) continue;
                    Location spawn = findWater(area, group.get(index).getLocation());
                    if (spawn == null || spawn.getWorld() == null) break outer;
                    spawn.getWorld().spawnEntity(spawn, group.get(index).getType());
                    created++;
                }
            }
        }

        aquarium.getPersistentDataContainer().set(nextBreedDayKey, PersistentDataType.LONG,
                day + (cycles > 1 ? 1 : random(1, 3)));
    }

    private Location findWater(FarmAreaCache area, Location fallback) {
        if (fallback != null && area.contains(fallback) && fallback.getBlock().getType() == Material.WATER) return fallback.clone();
        World world = area.center().getWorld();
        if (world == null) return null;
        for (FarmAreaCache.BlockPosition position : area.interior()) {
            if (world.getBlockAt(position.x(), position.y(), position.z()).getType() == Material.WATER) {
                return new Location(world, position.x() + 0.5, position.y() + 0.2, position.z() + 0.5);
            }
        }
        return null;
    }

    private void setState(Barrel aquarium, String state) {
        aquarium.getPersistentDataContainer().set(farmStateKey, PersistentDataType.STRING, state);
    }

    private int random(int min, int max) {
        int lower = Math.min(min, max);
        int upper = Math.max(min, max);
        return ThreadLocalRandom.current().nextInt(lower, upper + 1);
    }
}
