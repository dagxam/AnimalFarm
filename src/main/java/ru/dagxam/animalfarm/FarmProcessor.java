package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fish;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Единственный процессор автоматических механик AnimalFarm.
 * Работает только с зарегистрированными объектами и не сканирует все чанки.
 */
public final class FarmProcessor {

    private final AnimalFarmPlugin plugin;
    private final FarmSettings settings;

    private final org.bukkit.NamespacedKey breedingDayKey;
    private final org.bukkit.NamespacedKey milkProductionDayKey;
    private final org.bukkit.NamespacedKey eggProductionDayKey;
    private final org.bukkit.NamespacedKey fishBreedingDayKey;

    public FarmProcessor(AnimalFarmPlugin plugin, FarmSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.breedingDayKey = new org.bukkit.NamespacedKey(plugin, "daily_breeding_day");
        this.milkProductionDayKey = new org.bukkit.NamespacedKey(plugin, "milk_production_day");
        this.eggProductionDayKey = new org.bukkit.NamespacedKey(plugin, "egg_production_day");
        this.fishBreedingDayKey = new org.bukkit.NamespacedKey(plugin, "fish_breeding_day");
    }

    public void process(FarmObjectKey key, FarmObjectType type, long serverTick) {
        Location location = key.location(plugin.getServer());
        if (!(location.getBlock().getState() instanceof Barrel barrel)) return;

        long day = location.getWorld().getFullTime() / 24000L;
        if (type == FarmObjectType.LAND_FEEDER) {
            processLandFeeder(barrel, location, day);
        } else {
            processAquarium(barrel, location, day);
        }
        barrel.update(true, false);
    }

    private void processLandFeeder(Barrel feeder, Location location, long day) {
        if (feeder.getPersistentDataContainer().getOrDefault(breedingDayKey, PersistentDataType.LONG, -1L) < day) {
            processBreeding(feeder, location, day);
        }
        if (feeder.getPersistentDataContainer().getOrDefault(eggProductionDayKey, PersistentDataType.LONG, -1L) < day) {
            processEggs(feeder, location, day);
        }
        if (feeder.getPersistentDataContainer().getOrDefault(milkProductionDayKey, PersistentDataType.LONG, -1L) < day) {
            processMilk(feeder, location, day);
        }
    }

    private void processBreeding(Barrel feeder, Location location, long day) {
        List<Animals> animals = findNearbyAnimals(location);
        Map<String, List<Animals>> groups = new HashMap<>();
        for (Animals animal : animals) {
            if (!animal.isAdult() || !animal.canBreed() || animal.isLoveMode() || !supported(animal)) continue;
            groups.computeIfAbsent(animal.getType().name(), ignored -> new ArrayList<>()).add(animal);
        }

        int pairs = 0;
        int maxPairs = settings.maxBreedingPairsPerDay();
        for (List<Animals> group : groups.values()) {
            for (int i = 0; i + 1 < group.size() && pairs < maxPairs; i += 2) {
                Animals a = group.get(i);
                Animals b = group.get(i + 1);
                Material foodA = findFood(a, feeder.getInventory());
                Material foodB = findFood(b, feeder.getInventory());
                if (foodA == null || foodB == null) continue;
                if (!consumeWater(feeder.getInventory())) continue;
                removeOne(feeder.getInventory(), foodA);
                removeOne(feeder.getInventory(), foodB);
                a.setLoveModeTicks(600);
                b.setLoveModeTicks(600);
                pairs++;
            }
            if (pairs >= maxPairs) break;
        }

        feeder.getPersistentDataContainer().set(breedingDayKey, PersistentDataType.LONG, day);
    }

    private void processEggs(Barrel feeder, Location location, long day) {
        List<Animals> animals = findNearbyAnimals(location);
        long adults = animals.stream().filter(Animals::isAdult).filter(a -> a.getType().name().equals("CHICKEN")).count();
        if (adults >= 2) {
            for (Animals animal : animals) {
                if (!animal.isAdult() || !animal.getType().name().equals("CHICKEN")) continue;
                int amount = ThreadLocalRandom.current().nextInt(5, 11);
                for (int i = 0; i < amount; i++) {
                    animal.getWorld().dropItemNaturally(animal.getLocation(), new ItemStack(Material.EGG));
                }
            }
        }
        feeder.getPersistentDataContainer().set(eggProductionDayKey, PersistentDataType.LONG, day);
    }

    private void processMilk(Barrel feeder, Location location, long day) {
        Inventory inventory = feeder.getInventory();
        int emptyBuckets = count(inventory, Material.BUCKET);
        if (emptyBuckets <= 0) {
            feeder.getPersistentDataContainer().set(milkProductionDayKey, PersistentDataType.LONG, day);
            return;
        }

        int min = Math.max(0, plugin.getConfig().getInt("milking.milk-min", 2));
        int max = Math.max(min, plugin.getConfig().getInt("milking.milk-max", 3));
        int milkBuckets = 0;

        for (Animals animal : findNearbyAnimals(location)) {
            if (!animal.isAdult()) continue;
            String type = animal.getType().name();
            if (!type.equals("COW") && !type.equals("SHEEP") && !type.equals("GOAT")) continue;
            milkBuckets += ThreadLocalRandom.current().nextInt(min, max + 1);
        }

        milkBuckets = Math.min(milkBuckets, emptyBuckets);
        if (milkBuckets > 0) {
            remove(inventory, Material.BUCKET, milkBuckets);
            addBuckets(inventory, Material.MILK_BUCKET, milkBuckets);
        }
        feeder.getPersistentDataContainer().set(milkProductionDayKey, PersistentDataType.LONG, day);
    }

    private void processAquarium(Barrel shelf, Location location, long day) {
        if (shelf.getPersistentDataContainer().getOrDefault(fishBreedingDayKey, PersistentDataType.LONG, -1L) >= day) return;
        List<Fish> fish = new ArrayList<>();
        for (Entity entity : location.getWorld().getNearbyEntities(location, settings.aquariumMaxRadius(), settings.aquariumVerticalRange(), settings.aquariumMaxRadius())) {
            if (entity instanceof Fish f && f.getLocation().getBlock().getType() == Material.WATER) fish.add(f);
        }

        Map<org.bukkit.entity.EntityType, List<Fish>> groups = new HashMap<>();
        for (Fish f : fish) groups.computeIfAbsent(f.getType(), ignored -> new ArrayList<>()).add(f);

        int pairs = 0;
        for (var entry : groups.entrySet()) {
            List<Fish> group = entry.getValue();
            for (int i = 0; i + 1 < group.size() && pairs < settings.maxBreedingPairsPerDay(); i += 2) {
                if (!consumeSeeds(shelf.getInventory(), 2)) break;
                Location spawn = findWater(location);
                if (spawn == null) break;
                location.getWorld().spawnEntity(spawn, entry.getKey());
                pairs++;
            }
            if (pairs >= settings.maxBreedingPairsPerDay()) break;
        }
        shelf.getPersistentDataContainer().set(fishBreedingDayKey, PersistentDataType.LONG, day);
    }

    private Location findWater(Location center) {
        int radius = settings.aquariumMaxRadius();
        World world = center.getWorld();
        for (int y = center.getBlockY(); y <= center.getBlockY() + settings.aquariumVerticalRange(); y++) {
            for (int x = center.getBlockX() - radius; x <= center.getBlockX() + radius; x++) {
                for (int z = center.getBlockZ() - radius; z <= center.getBlockZ() + radius; z++) {
                    if (world.getBlockAt(x, y, z).getType() == Material.WATER) {
                        return new Location(world, x + 0.5, y + 0.2, z + 0.5);
                    }
                }
            }
        }
        return null;
    }

    private List<Animals> findNearbyAnimals(Location location) {
        List<Animals> result = new ArrayList<>();
        for (Entity entity : location.getWorld().getNearbyEntities(location, settings.penMaxRadius() + 1, settings.penVerticalRange(), settings.penMaxRadius() + 1)) {
            if (entity instanceof Animals animal && supported(animal)) result.add(animal);
        }
        return result;
    }

    private boolean supported(Animals animal) {
        return switch (animal.getType()) {
            case COW, SHEEP, GOAT, CHICKEN, HORSE, RABBIT -> true;
            default -> false;
        };
    }

    private Material findFood(Animals animal, Inventory inventory) {
        String key = animal.getType().name().toLowerCase(Locale.ROOT);
        for (String value : plugin.getConfig().getStringList("feeding." + key + ".foods")) {
            if ("ANY_SEEDS".equalsIgnoreCase(value)) {
                for (ItemStack item : inventory.getContents()) {
                    if (item != null && isSeed(item.getType()) && item.getAmount() > 0) return item.getType();
                }
                continue;
            }
            Material material = Material.matchMaterial(value);
            if (material != null && contains(inventory, material)) return material;
        }
        return null;
    }

    private boolean consumeWater(Inventory inventory) {
        return removeAndReturn(inventory, Material.WATER_BUCKET, 1);
    }

    private boolean consumeSeeds(Inventory inventory, int amount) {
        int available = 0;
        for (ItemStack item : inventory.getContents()) if (item != null && isSeed(item.getType())) available += item.getAmount();
        if (available < amount) return false;
        removeSeeds(inventory, amount);
        return true;
    }

    private void removeSeeds(Inventory inventory, int amount) {
        for (int slot = 0; slot < inventory.getSize() && amount > 0; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || !isSeed(item.getType())) continue;
            int take = Math.min(amount, item.getAmount());
            item.setAmount(item.getAmount() - take);
            if (item.getAmount() <= 0) inventory.setItem(slot, null);
            amount -= take;
        }
    }

    private boolean isSeed(Material material) {
        return material.name().endsWith("_SEEDS") || material == Material.PITCHER_POD;
    }

    private boolean contains(Inventory inventory, Material material) {
        for (ItemStack item : inventory.getContents()) if (item != null && item.getType() == material && item.getAmount() > 0) return true;
        return false;
    }

    private void removeOne(Inventory inventory, Material material) {
        remove(inventory, material, 1);
    }

    private void remove(Inventory inventory, Material material, int amount) {
        for (int slot = 0; slot < inventory.getSize() && amount > 0; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() != material) continue;
            int take = Math.min(amount, item.getAmount());
            item.setAmount(item.getAmount() - take);
            if (item.getAmount() <= 0) inventory.setItem(slot, null);
            amount -= take;
        }
    }

    private boolean removeAndReturn(Inventory inventory, Material material, int amount) {
        int before = count(inventory, material);
        if (before < amount) return false;
        remove(inventory, material, amount);
        addBuckets(inventory, Material.BUCKET, amount);
        return true;
    }

    private int count(Inventory inventory, Material material) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) if (item != null && item.getType() == material) total += item.getAmount();
        return total;
    }

    private void addBuckets(Inventory inventory, Material material, int amount) {
        while (amount > 0) {
            int add = Math.min(16, amount);
            ItemStack stack = new ItemStack(material, add);
            Map<Integer, ItemStack> leftovers = inventory.addItem(stack);
            int returned = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
            if (returned >= add) break;
            amount -= add - returned;
        }
    }
}
