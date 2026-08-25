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
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Координирует работу фермы. Поиск сущностей, вместимость и вода вынесены
 * в отдельные сервисы, поэтому здесь остаётся только последовательность механик.
 */
public final class FarmProcessor {
    private final AnimalFarmPlugin plugin;
    private final FarmSettings settings;
    private final FarmEntityService entityService;
    private final FarmCapacityService capacityService;
    private final FarmWaterService waterService;

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
        this.nextBreedDayKey = new NamespacedKey(plugin, "next_breed_day");
        this.productionDayKey = new NamespacedKey(plugin, "production_day");
        this.goldenDayKey = new NamespacedKey(plugin, "golden_day");
        this.goldenCyclesKey = new NamespacedKey(plugin, "golden_cycles");
        this.farmStateKey = new NamespacedKey(plugin, "farm_state");
    }

    public void process(FarmObjectKey key, FarmObjectType type, long tick) {
        Location location = key.location(plugin.getServer());
        if (location.getWorld() == null || !(location.getBlock().getState() instanceof Barrel barrel)) return;

        FarmAreaCache area = plugin.farmObjectManager().areaAnalyzer().analyze(key, type, plugin.getServer());
        long day = location.getWorld().getFullTime() / 24000L;

        if (!area.valid()) {
            setState(barrel, type == FarmObjectType.LAND_FEEDER ? "OPEN" : "NO_WATER");
            barrel.update(true, false);
            return;
        }

        if (type == FarmObjectType.LAND_FEEDER) {
            processLand(barrel, area, day);
        } else if (settings.aquariumEnabled()) {
            processAquarium(barrel, area, day);
        }

        barrel.update(true, false);
    }

    private void processLand(Barrel feeder, FarmAreaCache area, long day) {
        List<Animals> animals = entityService.findAnimals(area);
        for (Animals animal : animals) plugin.genderManager().assignRandomIfSupported(animal);

        updateLandState(feeder, animals);
        if (!isHealthy(feeder)) return;

        long nextBreedDay = getLong(feeder, nextBreedDayKey, -1L);
        if (nextBreedDay < 0 || day >= nextBreedDay) {
            runBreeding(feeder, animals, day);
        }

        long productionDay = getLong(feeder, productionDayKey, -1L);
        if (productionDay < day) {
            runProduction(feeder, animals, day);
        }
    }

    private void updateLandState(Barrel feeder, List<Animals> animals) {
        Inventory inventory = feeder.getInventory();
        String state;
        if (animals.size() >= settings.animalCapacity()) {
            state = "FULL";
        } else if (countAnimalFood(inventory) < settings.animalFoodMin()) {
            state = "NO_FOOD";
        } else if (!waterService.hasWater(inventory, 1)) {
            state = "NO_WATER";
        } else {
            state = "HEALTHY";
        }
        setState(feeder, state);
    }

    private boolean isHealthy(Barrel feeder) {
        return "HEALTHY".equals(feeder.getPersistentDataContainer().get(farmStateKey, PersistentDataType.STRING));
    }

    private void runBreeding(Barrel feeder, List<Animals> animals, long day) {
        if (!capacityService.canAddAnimal(animals.size(), 1)) return;

        Inventory inventory = feeder.getInventory();
        int cycles = goldenCycles(feeder, inventory, day);
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
                if (bred >= settings.maxBreedingPairsPerDay()) break outer;
                if (!capacityService.canAddAnimal(animals.size() + bred, 1)) break outer;

                Animals male = null;
                Animals female = null;
                for (Animals animal : group) {
                    AnimalGender gender = plugin.genderManager().getOrAssign(animal);
                    if (gender == AnimalGender.MALE && male == null) male = animal;
                    if (gender == AnimalGender.FEMALE && female == null) female = animal;
                    if (male != null && female != null) break;
                }

                if (male == null || female == null || !plugin.genderManager().canBreed(male, female)) continue;

                int foodCost = random(settings.animalFoodMin(), settings.animalFoodMax());
                if (!consumeFoodForPair(male, female, inventory, foodCost)) continue;
                if (!waterService.consumeWater(inventory, 1)) continue;

                male.setLoveModeTicks(600);
                female.setLoveModeTicks(600);
                bred++;
            }
        }

        feeder.getPersistentDataContainer().set(
                nextBreedDayKey,
                PersistentDataType.LONG,
                day + (cycles > 1 ? 1 : random(1, 3))
        );
    }

    private void runProduction(Barrel feeder, List<Animals> animals, long day) {
        Inventory inventory = feeder.getInventory();
        int cycles = goldenCycles(feeder, inventory, day);
        int chickens = 0;
        int sheep = 0;

        for (Animals animal : animals) {
            if (!animal.isAdult()) continue;
            switch (animal.getType()) {
                case CHICKEN -> chickens++;
                case SHEEP -> sheep++;
                default -> { }
            }
        }

        for (int cycle = 0; cycle < cycles; cycle++) {
            if (chickens > 0) {
                int amount = Math.min(chickens, random(
                        plugin.getConfig().getInt("production.chicken.eggs-min", 5),
                        plugin.getConfig().getInt("production.chicken.eggs-max", 10)
                ));
                if (consumeAnyAnimalFood(inventory, settings.animalFoodMin())) {
                    addItems(inventory, Material.EGG, amount);
                }
            }

            if (sheep > 0) {
                int amount = Math.min(sheep, random(
                        plugin.getConfig().getInt("production.wool.min", 2),
                        plugin.getConfig().getInt("production.wool.max", 3)
                ));
                if (consumeAnyAnimalFood(inventory, settings.animalFoodMin())) {
                    addItems(inventory, Material.WHITE_WOOL, amount);
                }
            }
        }

        feeder.getPersistentDataContainer().set(productionDayKey, PersistentDataType.LONG, day);
    }

    private int goldenCycles(Barrel feeder, Inventory inventory, long day) {
        long markedDay = getLong(feeder, goldenDayKey, -1L);
        if (markedDay == day) {
            return feeder.getPersistentDataContainer().getOrDefault(goldenCyclesKey, PersistentDataType.INTEGER, 1);
        }

        int cycles = 1;
        if (removeOneIfPresent(inventory, Material.GOLDEN_APPLE)
                || removeOneIfPresent(inventory, Material.GOLDEN_CARROT)) {
            cycles = random(settings.goldenCyclesMin(), settings.goldenCyclesMax());
        }

        feeder.getPersistentDataContainer().set(goldenDayKey, PersistentDataType.LONG, day);
        feeder.getPersistentDataContainer().set(goldenCyclesKey, PersistentDataType.INTEGER, cycles);
        return cycles;
    }

    private void processAquarium(Barrel aquarium, FarmAreaCache area, long day) {
        List<Fish> fish = entityService.findFish(area);
        Inventory inventory = aquarium.getInventory();

        if (fish.size() >= settings.fishCapacity()) {
            setState(aquarium, "FULL");
            return;
        }
        if (countFishFood(inventory) < settings.animalFoodMin()) {
            setState(aquarium, "NO_FOOD");
            return;
        }
        setState(aquarium, "HEALTHY");

        long nextBreedDay = getLong(aquarium, nextBreedDayKey, -1L);
        if (nextBreedDay >= 0 && day < nextBreedDay) return;

        int cycles = goldenCycles(aquarium, inventory, day);
        int created = 0;
        Map<EntityType, List<Fish>> groups = new HashMap<>();
        for (Fish current : fish) {
            groups.computeIfAbsent(current.getType(), ignored -> new ArrayList<>()).add(current);
        }

        outer:
        for (int cycle = 0; cycle < cycles; cycle++) {
            for (List<Fish> group : groups.values()) {
                for (int index = 0; index + 1 < group.size(); index += 2) {
                    if (created >= settings.maxBreedingPairsPerDay()) break outer;
                    if (!capacityService.canAddFish(fish.size() + created, 1)) break outer;

                    if (!consumeFishFood(inventory, random(settings.animalFoodMin(), settings.animalFoodMax()))) {
                        continue;
                    }

                    Location spawn = findWater(area, group.get(index).getLocation());
                    if (spawn == null) break outer;
                    World world = spawn.getWorld();
                    if (world == null) break outer;

                    world.spawnEntity(spawn, group.get(index).getType());
                    created++;
                }
            }
        }

        aquarium.getPersistentDataContainer().set(
                nextBreedDayKey,
                PersistentDataType.LONG,
                day + (cycles > 1 ? 1 : random(1, 3))
        );
    }

    private Location findWater(FarmAreaCache area, Location fallback) {
        if (fallback != null && area.contains(fallback)
                && fallback.getBlock().getType() == Material.WATER) {
            return fallback.clone();
        }

        World world = area.center().getWorld();
        if (world == null) return null;
        for (FarmAreaCache.BlockPosition position : area.interior()) {
            if (world.getBlockAt(position.x(), position.y(), position.z()).getType() == Material.WATER) {
                return new Location(world, position.x() + 0.5, position.y() + 0.2, position.z() + 0.5);
            }
        }
        return null;
    }

    private boolean consumeFoodForPair(Animals first, Animals second, Inventory inventory, int amount) {
        Material firstFood = findFood(first, inventory);
        Material secondFood = findFood(second, inventory);
        if (firstFood == null || secondFood == null) return false;

        if (firstFood == secondFood) {
            if (count(inventory, firstFood) < amount * 2) return false;
        } else if (count(inventory, firstFood) < amount || count(inventory, secondFood) < amount) {
            return false;
        }

        remove(inventory, firstFood, amount);
        remove(inventory, secondFood, amount);
        return true;
    }

    private Material findFood(Animals animal, Inventory inventory) {
        for (String value : plugin.getConfig().getStringList(
                "feeding." + animal.getType().name().toLowerCase(Locale.ROOT) + ".foods"
        )) {
            if ("ANY_SEEDS".equalsIgnoreCase(value)) {
                for (ItemStack item : inventory.getContents()) {
                    if (item != null && isSeed(item.getType())) return item.getType();
                }
                continue;
            }
            if ("ANY_GRASS_LEAVES_BUSHES_FLOWERS".equalsIgnoreCase(value)) {
                for (ItemStack item : inventory.getContents()) {
                    if (item != null && isGrassOrLeaves(item.getType())) return item.getType();
                }
                continue;
            }

            Material material = Material.matchMaterial(value);
            if (material != null && count(inventory, material) > 0) return material;
        }
        return null;
    }

    private boolean consumeAnyAnimalFood(Inventory inventory, int amount) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || !isAnimalFood(item.getType()) || item.getAmount() < amount) continue;

            item.setAmount(item.getAmount() - amount);
            if (item.getAmount() <= 0) inventory.setItem(slot, null);
            return true;
        }
        return false;
    }

    private boolean consumeFishFood(Inventory inventory, int amount) {
        if (countFishFood(inventory) < amount) return false;

        for (int slot = 0; slot < inventory.getSize() && amount > 0; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || !isFishFood(item.getType())) continue;

            int consumed = Math.min(amount, item.getAmount());
            item.setAmount(item.getAmount() - consumed);
            amount -= consumed;
            if (item.getAmount() <= 0) inventory.setItem(slot, null);
        }
        return true;
    }

    private int countAnimalFood(Inventory inventory) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && isAnimalFood(item.getType())) total += item.getAmount();
        }
        return total;
    }

    private int countFishFood(Inventory inventory) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && isFishFood(item.getType())) total += item.getAmount();
        }
        return total;
    }

    private boolean isSeed(Material material) {
        return material.name().endsWith("_SEEDS") || material == Material.PITCHER_POD;
    }

    private boolean isGrassOrLeaves(Material material) {
        return material.name().endsWith("_LEAVES")
                || material == Material.GRASS_BLOCK
                || material == Material.SHORT_GRASS
                || material == Material.FERN
                || material == Material.LARGE_FERN
                || material == Material.MOSS_BLOCK;
    }

    private boolean isFishFood(Material material) {
        return isSeed(material)
                || material == Material.SEAGRASS
                || material == Material.KELP
                || material == Material.SEA_PICKLE;
    }

    private boolean isAnimalFood(Material material) {
        return isFishFood(material)
                || isGrassOrLeaves(material)
                || material == Material.WHEAT
                || material == Material.HAY_BLOCK
                || material == Material.APPLE
                || material == Material.CARROT
                || material == Material.POTATO
                || material == Material.BEETROOT
                || material == Material.MELON_SLICE
                || material == Material.PUMPKIN
                || material == Material.MELON
                || material == Material.GOLDEN_APPLE
                || material == Material.GOLDEN_CARROT;
    }

    private void setState(Barrel feeder, String state) {
        feeder.getPersistentDataContainer().set(farmStateKey, PersistentDataType.STRING, state);
    }

    private long getLong(Barrel barrel, NamespacedKey key, long fallback) {
        return barrel.getPersistentDataContainer().getOrDefault(key, PersistentDataType.LONG, fallback);
    }

    private int random(int min, int max) {
        return ThreadLocalRandom.current().nextInt(Math.min(min, max), Math.max(min, max) + 1);
    }

    private int count(Inventory inventory, Material material) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() == material) total += item.getAmount();
        }
        return total;
    }

    private void remove(Inventory inventory, Material material, int amount) {
        for (int slot = 0; slot < inventory.getSize() && amount > 0; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() != material) continue;

            int removed = Math.min(amount, item.getAmount());
            item.setAmount(item.getAmount() - removed);
            amount -= removed;
            if (item.getAmount() <= 0) inventory.setItem(slot, null);
        }
    }

    private boolean removeOneIfPresent(Inventory inventory, Material material) {
        if (count(inventory, material) <= 0) return false;
        remove(inventory, material, 1);
        return true;
    }

    private void addItems(Inventory inventory, Material material, int amount) {
        if (amount > 0) inventory.addItem(new ItemStack(material, amount));
    }
}
