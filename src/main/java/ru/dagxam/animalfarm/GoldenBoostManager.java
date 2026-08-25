package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Barrel;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fish;
import org.bukkit.inventory.Inventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Единая система золотого ускорения. Работает только с зарегистрированной областью
 * фермы/аквариума и никогда не создаёт сущностей сверх установленной вместимости.
 */
public final class GoldenBoostManager {
    private final AnimalFarmPlugin plugin;
    private final FarmSettings settings;
    private final FarmEntityService entityService;
    private final FarmCapacityService capacityService;
    private final NamespacedKey dayKey;
    private final NamespacedKey nextBreedDayKey;

    public GoldenBoostManager(AnimalFarmPlugin plugin, FarmSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.entityService = new FarmEntityService(plugin, settings);
        this.capacityService = new FarmCapacityService(settings);
        this.dayKey = new NamespacedKey(plugin, "golden_boost_day");
        this.nextBreedDayKey = new NamespacedKey(plugin, "next_breed_day");
    }

    public void process(FarmObjectKey key, FarmObjectType type, long currentTick) {
        Location location = key.location(plugin.getServer());
        if (location.getWorld() == null || !(location.getBlock().getState() instanceof Barrel feeder)) return;
        if (!hasGoldenFood(feeder.getInventory())) return;

        FarmAreaCache area = plugin.farmObjectManager().areaAnalyzer().analyze(key, type, plugin.getServer());
        if (!area.valid()) return;

        long day = location.getWorld().getFullTime() / 24000L;
        long last = feeder.getPersistentDataContainer().getOrDefault(dayKey, PersistentDataType.LONG, -1L);
        if (last >= day) return;

        int produced = type == FarmObjectType.AQUARIUM_SHELF
                ? breedFish(feeder, area, day)
                : breedAnimals(feeder, area, day);

        if (produced > 0) {
            feeder.getPersistentDataContainer().set(dayKey, PersistentDataType.LONG, day);
            feeder.update(true, false);
        }
    }

    private int breedAnimals(Barrel feeder, FarmAreaCache area, long day) {
        List<Animals> animals = entityService.findAnimals(area);
        Map<EntityType, List<Animals>> groups = new HashMap<>();
        for (Animals animal : animals) {
            if (!animal.isAdult() || !supported(animal)) continue;
            groups.computeIfAbsent(animal.getType(), ignored -> new ArrayList<>()).add(animal);
        }

        int pairs = 0;
        int children = 0;
        int currentCount = animals.size();

        outer:
        for (List<Animals> group : groups.values()) {
            Animals male = null;
            Animals female = null;
            for (Animals animal : group) {
                AnimalGender gender = plugin.genderManager().getOrAssign(animal);
                if (gender == AnimalGender.MALE && male == null) male = animal;
                if (gender == AnimalGender.FEMALE && female == null) female = animal;
                if (male != null && female != null) break;
            }
            if (male == null || female == null || !plugin.genderManager().canBreed(male, female)) continue;

            while (pairs < settings.maxBreedingPairsPerDay()) {
                int amount = Math.min(random(2, 4), settings.animalCapacity() - currentCount - children);
                if (amount <= 0) break outer;
                if (!consumeGoldenFood(feeder.getInventory())) break outer;

                Location spawn = midpoint(male.getLocation(), female.getLocation());
                for (int index = 0; index < amount; index++) {
                    Entity child = male.getWorld().spawnEntity(
                            spawn.clone().add(randomOffset(), 0.0, randomOffset()), male.getType()
                    );
                    if (child instanceof Ageable ageable) {
                        ageable.setBaby();
                        plugin.genderManager().assignRandomIfSupported(ageable);
                    }
                    children++;
                }
                scheduleNext(male, day);
                scheduleNext(female, day);
                pairs++;
                break;
            }
        }
        return children;
    }

    private int breedFish(Barrel feeder, FarmAreaCache area, long day) {
        List<Fish> fish = entityService.findFish(area);
        Map<EntityType, List<Fish>> groups = new HashMap<>();
        for (Fish current : fish) {
            groups.computeIfAbsent(current.getType(), ignored -> new ArrayList<>()).add(current);
        }

        int pairs = 0;
        int children = 0;
        outer:
        for (List<Fish> group : groups.values()) {
            for (int index = 0; index + 1 < group.size() && pairs < settings.maxBreedingPairsPerDay(); index += 2) {
                int amount = Math.min(random(2, 4), settings.fishCapacity() - fish.size() - children);
                if (amount <= 0) break outer;
                if (!consumeGoldenFood(feeder.getInventory())) break outer;

                Location spawn = group.get(index).getLocation().clone();
                for (int childIndex = 0; childIndex < amount; childIndex++) {
                    group.get(index).getWorld().spawnEntity(
                            spawn.clone().add(randomOffset(), 0.0, randomOffset()), group.get(index).getType()
                    );
                    children++;
                }
                scheduleNext(group.get(index), day);
                scheduleNext(group.get(index + 1), day);
                pairs++;
            }
        }
        return children;
    }

    private boolean supported(Animals animal) {
        return switch (animal.getType()) {
            case COW, SHEEP, GOAT, PIG, CHICKEN, HORSE, RABBIT -> true;
            default -> false;
        };
    }

    private boolean hasGoldenFood(Inventory inventory) {
        return count(inventory, Material.GOLDEN_APPLE) > 0
                || count(inventory, Material.GOLDEN_CARROT) > 0;
    }

    private boolean consumeGoldenFood(Inventory inventory) {
        return consumeOne(inventory, Material.GOLDEN_APPLE)
                || consumeOne(inventory, Material.GOLDEN_CARROT);
    }

    private int count(Inventory inventory, Material material) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() == material) total += item.getAmount();
        }
        return total;
    }

    private boolean consumeOne(Inventory inventory, Material material) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() != material || item.getAmount() <= 0) continue;
            if (item.getAmount() == 1) inventory.setItem(slot, null);
            else item.setAmount(item.getAmount() - 1);
            return true;
        }
        return false;
    }

    private Location midpoint(Location first, Location second) {
        Location result = first.clone();
        result.add(second).multiply(0.5);
        result.setWorld(first.getWorld());
        return result;
    }

    private void scheduleNext(Entity entity, long day) {
        entity.getPersistentDataContainer().set(nextBreedDayKey, PersistentDataType.LONG, day + random(1, 3));
    }

    private int random(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private double randomOffset() {
        return ThreadLocalRandom.current().nextDouble(-0.35, 0.36);
    }
}
