package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fish;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Золотое питание: золотое яблоко и золотая морковь подходят всем поддерживаемым животным и рыбам.
 * Одна золотая единица корма для пары сразу даёт 2-4 детёныша и ставит обычную паузу размножения 1-3 игровых дня.
 */
public final class GoldenBoostManager {
    private static final double RADIUS = 17.0;
    private final JavaPlugin plugin;
    private final NamespacedKey feederKey;
    private final NamespacedKey dayKey;
    private final NamespacedKey nextBreedDayKey;

    public GoldenBoostManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.feederKey = new NamespacedKey(plugin, "feeder_block");
        this.dayKey = new NamespacedKey(plugin, "golden_boost_day");
        this.nextBreedDayKey = new NamespacedKey(plugin, "next_breed_day");
        new BukkitRunnable() {
            @Override public void run() { processAll(); }
        }.runTaskTimer(plugin, 1L, 20L);
    }

    private void processAll() {
        for (World world : plugin.getServer().getWorlds()) {
            for (var chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities()) {
                    if (state instanceof Barrel barrel && isFeeder(barrel)) process(barrel);
                }
            }
        }
    }

    private boolean isFeeder(Barrel barrel) {
        return barrel.getPersistentDataContainer().has(feederKey, PersistentDataType.BYTE);
    }

    private void process(Barrel feeder) {
        long day = feeder.getWorld().getFullTime() / 24000L;
        long last = feeder.getPersistentDataContainer().getOrDefault(dayKey, PersistentDataType.LONG, -1L);
        if (last >= day) return;
        if (!hasGoldenFood(feeder.getInventory())) return;

        boolean aquarium = hasNearbyFish(feeder);
        int produced = aquarium ? breedFish(feeder, day) : breedAnimals(feeder, day);
        if (produced > 0) {
            feeder.getPersistentDataContainer().set(dayKey, PersistentDataType.LONG, day);
            feeder.update(true, false);
        }
    }

    private boolean hasNearbyFish(Barrel feeder) {
        for (Entity entity : feeder.getWorld().getNearbyEntities(feeder.getLocation(), RADIUS, 8, RADIUS)) {
            if (entity instanceof Fish fish && fish.getLocation().getBlock().getType() == Material.WATER) return true;
        }
        return false;
    }

    private int breedAnimals(Barrel feeder, long day) {
        Map<String, List<Animals>> groups = new HashMap<>();
        for (Entity entity : feeder.getWorld().getNearbyEntities(feeder.getLocation(), RADIUS, 6, RADIUS)) {
            if (!(entity instanceof Animals animal) || !animal.isAdult() || !supported(animal)) continue;
            groups.computeIfAbsent(animal.getType().name(), ignored -> new ArrayList<>()).add(animal);
        }

        int maxPairs = Math.max(1, plugin.getConfig().getInt("feeder.max-breeding-pairs-per-day", 10));
        int pairs = 0;
        int children = 0;
        for (List<Animals> group : groups.values()) {
            for (int i = 0; i + 1 < group.size() && pairs < maxPairs; i += 2) {
                if (!consumeGoldenFood(feeder.getInventory())) return children;
                Animals first = group.get(i);
                Animals second = group.get(i + 1);
                Location spawn = first.getLocation().add(second.getLocation()).multiply(0.5);
                spawn.setY(Math.max(first.getLocation().getY(), second.getLocation().getY()));
                int amount = random(2, 4);
                for (int childIndex = 0; childIndex < amount; childIndex++) {
                    Entity child = first.getWorld().spawnEntity(spawn.clone().add(randomOffset(), 0, randomOffset()), first.getType());
                    if (child instanceof Ageable ageable) ageable.setBaby();
                    children++;
                }
                scheduleNext(first, day);
                scheduleNext(second, day);
                pairs++;
            }
        }
        return children;
    }

    private int breedFish(Barrel feeder, long day) {
        Map<String, List<Fish>> groups = new HashMap<>();
        for (Entity entity : feeder.getWorld().getNearbyEntities(feeder.getLocation(), RADIUS, 8, RADIUS)) {
            if (entity instanceof Fish fish && fish.getLocation().getBlock().getType() == Material.WATER) {
                groups.computeIfAbsent(fish.getType().name(), ignored -> new ArrayList<>()).add(fish);
            }
        }

        int maxPairs = Math.max(1, plugin.getConfig().getInt("feeder.max-breeding-pairs-per-day", 10));
        int pairs = 0;
        int children = 0;
        for (List<Fish> group : groups.values()) {
            for (int i = 0; i + 1 < group.size() && pairs < maxPairs; i += 2) {
                if (!consumeGoldenFood(feeder.getInventory())) return children;
                Fish first = group.get(i);
                Fish second = group.get(i + 1);
                Location spawn = first.getLocation().add(second.getLocation()).multiply(0.5);
                int amount = random(2, 4);
                for (int childIndex = 0; childIndex < amount; childIndex++) {
                    first.getWorld().spawnEntity(spawn.clone().add(randomOffset(), 0, randomOffset()), first.getType());
                    children++;
                }
                scheduleNext(first, day);
                scheduleNext(second, day);
                pairs++;
            }
        }
        return children;
    }

    private boolean supported(Animals animal) {
        return switch (animal.getType()) {
            case COW, SHEEP, GOAT, CHICKEN, HORSE, RABBIT -> true;
            default -> false;
        };
    }

    private boolean hasGoldenFood(Inventory inventory) {
        return count(inventory, Material.GOLDEN_APPLE) > 0 || count(inventory, Material.GOLDEN_CARROT) > 0;
    }

    private boolean consumeGoldenFood(Inventory inventory) {
        return consumeOne(inventory, Material.GOLDEN_APPLE) || consumeOne(inventory, Material.GOLDEN_CARROT);
    }

    private int count(Inventory inventory, Material material) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) if (item != null && item.getType() == material) total += item.getAmount();
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
