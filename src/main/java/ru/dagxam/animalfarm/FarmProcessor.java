package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fish;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Единый ежедневный процессор кормушек и аквариумов. */
public final class FarmProcessor {
    private final AnimalFarmPlugin plugin;
    private final FarmSettings settings;
    private final org.bukkit.NamespacedKey breedingDayKey;
    private final org.bukkit.NamespacedKey nextBreedDayKey;
    private final org.bukkit.NamespacedKey productionDayKey;
    private final org.bukkit.NamespacedKey fishBreedingDayKey;

    public FarmProcessor(AnimalFarmPlugin plugin, FarmSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        breedingDayKey = new org.bukkit.NamespacedKey(plugin, "daily_breeding_day");
        nextBreedDayKey = new org.bukkit.NamespacedKey(plugin, "next_breed_day");
        productionDayKey = new org.bukkit.NamespacedKey(plugin, "production_day");
        fishBreedingDayKey = new org.bukkit.NamespacedKey(plugin, "fish_breeding_day");
    }

    public void process(FarmObjectKey key, FarmObjectType type, long serverTick) {
        Location location = key.location(plugin.getServer());
        if (location.getWorld() == null || !(location.getBlock().getState() instanceof Barrel barrel)) return;
        long day = location.getWorld().getFullTime() / 24000L;
        if (type == FarmObjectType.LAND_FEEDER) processLandFeeder(barrel, location, day);
        else processAquarium(barrel, location, day);
        barrel.update(true, false);
    }

    private void processLandFeeder(Barrel feeder, Location location, long day) {
        long last = feeder.getPersistentDataContainer().getOrDefault(breedingDayKey, PersistentDataType.LONG, -1L);
        long next = feeder.getPersistentDataContainer().getOrDefault(nextBreedDayKey, PersistentDataType.LONG, -1L);
        if (last < 0 || (day >= next && last < day)) processBreeding(feeder, location, day);
        long production = feeder.getPersistentDataContainer().getOrDefault(productionDayKey, PersistentDataType.LONG, -1L);
        if (production < day) processProduction(feeder, location, day);
    }

    private void processBreeding(Barrel feeder, Location location, long day) {
        List<Animals> animals = findNearbyAnimals(location);
        Map<EntityType, List<Animals>> groups = new HashMap<>();
        for (Animals animal : animals) {
            if (animal.isAdult() && animal.canBreed() && !animal.isLoveMode()) {
                groups.computeIfAbsent(animal.getType(), ignored -> new ArrayList<>()).add(animal);
            }
        }

        int pairs = 0;
        int maxPairs = settings.maxBreedingPairsPerDay();
        boolean usedGolden = false;
        for (List<Animals> group : groups.values()) {
            for (int i = 0; i + 1 < group.size() && pairs < maxPairs; i += 2) {
                Animals a = group.get(i);
                Animals b = group.get(i + 1);
                boolean golden = consumeGolden(feeder.getInventory());
                int foodCost = golden ? 1 : random(3, 5);
                if (golden) {
                    usedGolden = true;
                } else if (!consumeFoodForPair(a, b, feeder.getInventory(), foodCost)) {
                    continue;
                }
                if (!consumeWater(feeder.getInventory(), golden ? 1 : foodCost)) continue;

                a.setLoveModeTicks(600);
                b.setLoveModeTicks(600);
                int delay = golden ? random(1, 1) : random(1, 3);
                a.getPersistentDataContainer().set(nextBreedDayKey, PersistentDataType.LONG, day + delay);
                b.getPersistentDataContainer().set(nextBreedDayKey, PersistentDataType.LONG, day + delay);
                pairs++;
            }
            if (pairs >= maxPairs) break;
        }

        feeder.getPersistentDataContainer().set(breedingDayKey, PersistentDataType.LONG, day);
        feeder.getPersistentDataContainer().set(nextBreedDayKey, PersistentDataType.LONG, day + (usedGolden ? random(1, 2) : random(1, 3)));
    }

    private boolean consumeFoodForPair(Animals a, Animals b, Inventory inventory, int amount) {
        Material foodA = findFood(a, inventory);
        Material foodB = findFood(b, inventory);
        if (foodA == null || foodB == null) return false;
        if (!contains(inventory, foodA, amount) || !contains(inventory, foodB, amount)) return false;
        remove(inventory, foodA, amount);
        remove(inventory, foodB, amount);
        return true;
    }

    private void processProduction(Barrel feeder, Location location, long day) {
        Inventory inventory = feeder.getInventory();
        List<Animals> animals = findNearbyAnimals(location);
        int chickens = 0;
        int woolAnimals = 0;
        int milkingAnimals = 0;
        for (Animals animal : animals) {
            if (!animal.isAdult()) continue;
            switch (animal.getType()) {
                case CHICKEN -> chickens++;
                case SHEEP -> woolAnimals++;
                case COW, GOAT -> milkingAnimals++;
                default -> { }
            }
        }

        if (chickens > 0) {
            int amount = random(3, 5);
            if (consumeFoodForGenericProduction(inventory, amount)) {
                for (int i = 0; i < amount; i++) location.getWorld().dropItemNaturally(location, new ItemStack(Material.EGG));
            }
        }
        if (woolAnimals > 0) {
            int amount = Math.min(woolAnimals, random(2, 3));
            if (consumeFoodForGenericProduction(inventory, amount)) addItems(inventory, Material.WHITE_WOOL, amount);
        }
        if (milkingAnimals > 0 && plugin.getConfig().getBoolean("milking.enabled", true)) {
            int buckets = Math.min(count(inventory, Material.BUCKET), Math.min(3, milkingAnimals));
            if (buckets > 0) {
                remove(inventory, Material.BUCKET, buckets);
                addItems(inventory, Material.MILK_BUCKET, buckets);
            }
        }
        feeder.getPersistentDataContainer().set(productionDayKey, PersistentDataType.LONG, day);
    }

    private boolean consumeFoodForGenericProduction(Inventory inventory, int amount) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || !isAnyFood(item.getType())) continue;
            if (item.getAmount() < amount) continue;
            item.setAmount(item.getAmount() - amount);
            if (item.getAmount() <= 0) inventory.setItem(slot, null);
            return true;
        }
        return false;
    }

    private void processAquarium(Barrel shelf, Location location, long day) {
        long next = shelf.getPersistentDataContainer().getOrDefault(fishBreedingDayKey, PersistentDataType.LONG, -1L);
        if (next >= 0 && day < next) return;

        List<Fish> fish = findNearbyFish(location);
        Map<EntityType, List<Fish>> groups = new HashMap<>();
        for (Fish f : fish) groups.computeIfAbsent(f.getType(), ignored -> new ArrayList<>()).add(f);

        int pairs = 0;
        for (List<Fish> group : groups.values()) {
            for (int i = 0; i + 1 < group.size() && pairs < settings.maxBreedingPairsPerDay(); i += 2) {
                int cost = random(3, 5);
                if (!consumeFishFood(shelf.getInventory(), cost)) continue;
                Location spawn = findWater(location);
                if (spawn == null) continue;
                location.getWorld().spawnEntity(spawn, group.get(i).getType());
                pairs++;
            }
            if (pairs >= settings.maxBreedingPairsPerDay()) break;
        }
        shelf.getPersistentDataContainer().set(fishBreedingDayKey, PersistentDataType.LONG, day + random(1, 3));
    }

    private List<Fish> findNearbyFish(Location location) {
        List<Fish> result = new ArrayList<>();
        for (Entity entity : location.getWorld().getNearbyEntities(location, settings.aquariumMaxRadius(), settings.aquariumVerticalRange(), settings.aquariumMaxRadius())) {
            if (entity instanceof Fish f && f.getLocation().getBlock().getType() == Material.WATER) result.add(f);
        }
        return result;
    }

    private Location findWater(Location center) {
        World world = center.getWorld();
        if (world == null) return null;
        int r = settings.aquariumMaxRadius();
        for (int x = center.getBlockX() - r; x <= center.getBlockX() + r; x++) {
            for (int y = center.getBlockY() - settings.aquariumVerticalRange(); y <= center.getBlockY() + settings.aquariumVerticalRange(); y++) {
                for (int z = center.getBlockZ() - r; z <= center.getBlockZ() + r; z++) {
                    if (world.getBlockAt(x, y, z).getType() == Material.WATER) return new Location(world, x + .5, y + .2, z + .5);
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
                for (ItemStack item : inventory.getContents()) if (item != null && isSeed(item.getType()) && item.getAmount() > 0) return item.getType();
                continue;
            }
            Material material = Material.matchMaterial(value);
            if (material != null && contains(inventory, material, 1)) return material;
        }
        return null;
    }

    private boolean consumeFishFood(Inventory inventory, int amount) {
        int available = 0;
        for (ItemStack item : inventory.getContents()) if (item != null && isFishFood(item.getType())) available += item.getAmount();
        if (available < amount) return false;
        for (int slot = 0; slot < inventory.getSize() && amount > 0; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || !isFishFood(item.getType())) continue;
            int take = Math.min(amount, item.getAmount());
            item.setAmount(item.getAmount() - take);
            if (item.getAmount() <= 0) inventory.setItem(slot, null);
            amount -= take;
        }
        return true;
    }

    private boolean consumeWater(Inventory inventory, int amount) {
        if (count(inventory, Material.WATER_BUCKET) < amount) return false;
        remove(inventory, Material.WATER_BUCKET, amount);
        addItems(inventory, Material.BUCKET, amount);
        return true;
    }

    private boolean consumeGolden(Inventory inventory) {
        return removeOneIfPresent(inventory, Material.GOLDEN_APPLE) || removeOneIfPresent(inventory, Material.GOLDEN_CARROT);
    }

    private boolean removeOneIfPresent(Inventory inventory, Material material) {
        if (count(inventory, material) <= 0) return false;
        remove(inventory, material, 1);
        return true;
    }

    private int random(int min, int max) { return ThreadLocalRandom.current().nextInt(min, max + 1); }

    private boolean isSeed(Material material) { return material.name().endsWith("_SEEDS") || material == Material.PITCHER_POD; }
    private boolean isFishFood(Material material) { return isSeed(material) || material == Material.SEAGRASS || material == Material.KELP || material == Material.SEA_PICKLE; }
    private boolean isAnyFood(Material material) { return isFishFood(material) || material == Material.WHEAT || material == Material.HAY_BLOCK || material == Material.APPLE || material == Material.CARROT || material == Material.MELON_SLICE || material == Material.PUMPKIN || material == Material.MELON; }

    private boolean contains(Inventory inv, Material type, int amount) { return count(inv, type) >= amount; }
    private int count(Inventory inv, Material type) { int n=0; for (ItemStack i: inv.getContents()) if(i!=null&&i.getType()==type)n+=i.getAmount(); return n; }
    private void remove(Inventory inv, Material type, int amount) { for(int s=0;s<inv.getSize()&&amount>0;s++){ItemStack i=inv.getItem(s);if(i==null||i.getType()!=type)continue;int take=Math.min(amount,i.getAmount());i.setAmount(i.getAmount()-take);if(i.getAmount()<=0)inv.setItem(s,null);amount-=take;}}
    private void addItems(Inventory inv, Material type, int amount) { if(amount>0) inv.addItem(new ItemStack(type, amount)); }
}
