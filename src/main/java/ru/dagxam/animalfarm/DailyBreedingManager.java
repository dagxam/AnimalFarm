package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Автоматическое размножение строго один раз за игровые сутки.
 * Используется love mode Paper API, а не setBreed(true): setBreed(true)
 * только меняет состояние готовности и не запускает сам процесс размножения.
 */
public final class DailyBreedingManager {
    private static final int PEN_RADIUS = 16;
    private static final int VERTICAL_RANGE = 5;
    private static final int LOVE_TICKS = 600;

    private final JavaPlugin plugin;
    private final NamespacedKey feederKey;
    private final NamespacedKey breedingDayKey;
    private final NamespacedKey waterProgressKey;

    public DailyBreedingManager(JavaPlugin plugin) {
        this.plugin = plugin;
        feederKey = new NamespacedKey(plugin, "feeder_block");
        breedingDayKey = new NamespacedKey(plugin, "daily_breeding_day");
        waterProgressKey = new NamespacedKey(plugin, "water_progress");

        new BukkitRunnable() {
            @Override
            public void run() {
                processAllFeeders();
            }
        }.runTaskTimer(plugin, 40L, 40L);
    }

    private void processAllFeeders() {
        for (World world : plugin.getServer().getWorlds()) {
            for (var chunk : world.getLoadedChunks()) {
                for (var state : chunk.getTileEntities()) {
                    if (state instanceof Barrel barrel && isFeeder(barrel.getBlock())) {
                        processFeeder(barrel);
                    }
                }
            }
        }
    }

    private boolean isFeeder(Block block) {
        return block.getType() == Material.BARREL
                && block.getState() instanceof Barrel barrel
                && barrel.getPersistentDataContainer().has(feederKey, PersistentDataType.BYTE);
    }

    private void processFeeder(Barrel feeder) {
        Pen pen = analyzePen(feeder.getBlock());
        if (!pen.valid()) return;

        long day = feeder.getWorld().getFullTime() / 24000L;
        Long lastDay = feeder.getPersistentDataContainer().get(breedingDayKey, PersistentDataType.LONG);

        // Новая кормушка ждёт окончания текущих суток.
        if (lastDay == null) {
            feeder.getPersistentDataContainer().set(breedingDayKey, PersistentDataType.LONG, day);
            feeder.update(true, false);
            return;
        }

        // Пока игровые сутки не закончились — никакого размножения.
        if (day <= lastDay) return;

        int maxPairs = Math.max(1, plugin.getConfig().getInt("feeder.max-breeding-pairs-per-day", 10));
        int pairs = 0;

        Map<String, List<Animals>> groups = new HashMap<>();
        for (Animals animal : pen.animals()) {
            if (!animal.isAdult() || !isSupportedAnimal(animal) || !animal.canBreed() || animal.isLoveMode()) continue;
            String type = animal.getType().name().toLowerCase(Locale.ROOT);
            groups.computeIfAbsent(type, ignored -> new ArrayList<>()).add(animal);
        }

        for (List<Animals> group : groups.values()) {
            if (pairs >= maxPairs) break;
            if (group.size() < 2) continue;

            for (int i = 0; i + 1 < group.size() && pairs < maxPairs; i += 2) {
                Animals first = group.get(i);
                Animals second = group.get(i + 1);

                if (!first.isValid() || !second.isValid() || !first.isAdult() || !second.isAdult()) continue;
                if (!first.canBreed() || !second.canBreed() || first.isLoveMode() || second.isLoveMode()) continue;

                Material firstFood = findFood(first, feeder.getInventory());
                Material secondFood = findFood(second, feeder.getInventory());
                if (firstFood == null || secondFood == null) continue;
                if (!consumeWaterCharge(feeder)) continue;

                removeOne(feeder.getInventory(), firstFood);
                removeOne(feeder.getInventory(), secondFood);

                // 600 ticks = обычное кормление животного игроком.
                first.setLoveModeTicks(LOVE_TICKS);
                second.setLoveModeTicks(LOVE_TICKS);
                pairs++;
            }
        }

        // Сутки засчитываем только после успешного запуска хотя бы одной пары.
        if (pairs > 0) {
            feeder.getPersistentDataContainer().set(breedingDayKey, PersistentDataType.LONG, day);
            feeder.update(true, false);
        }
    }

    private boolean isSupportedAnimal(Animals animal) {
        return switch (animal.getType()) {
            case COW, SHEEP, GOAT, CHICKEN -> true;
            default -> false;
        };
    }

    private Material findFood(Animals animal, Inventory inventory) {
        String key = animal.getType().name().toLowerCase(Locale.ROOT);
        for (String configured : plugin.getConfig().getStringList("feeding." + key + ".foods")) {
            Material material = Material.matchMaterial(configured);
            if (material != null && contains(inventory, material)) return material;
        }
        return null;
    }

    private boolean contains(Inventory inventory, Material material) {
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() == material && item.getAmount() > 0) return true;
        }
        return false;
    }

    private void removeOne(Inventory inventory, Material material) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() != material) continue;
            if (item.getAmount() <= 1) inventory.setItem(slot, null);
            else item.setAmount(item.getAmount() - 1);
            return;
        }
    }

    private boolean consumeWaterCharge(Barrel feeder) {
        if (!contains(feeder.getInventory(), Material.WATER_BUCKET)) return false;

        int progress = feeder.getPersistentDataContainer().getOrDefault(
                waterProgressKey, PersistentDataType.INTEGER, 0
        ) + 1;
        int feedingsPerBucket = Math.max(1,
                plugin.getConfig().getInt("feeder.water-feedings-per-bucket", 10));

        if (progress >= feedingsPerBucket) {
            removeOne(feeder.getInventory(), Material.WATER_BUCKET);
            Map<Integer, ItemStack> leftovers = feeder.getInventory().addItem(new ItemStack(Material.BUCKET));
            if (!leftovers.isEmpty()) feeder.getBlock().getWorld().dropItemNaturally(feeder.getBlock().getLocation(), leftovers.get(0));
            progress = 0;
        }

        feeder.getPersistentDataContainer().set(waterProgressKey, PersistentDataType.INTEGER, progress);
        feeder.update(true, false);
        return true;
    }

    private Pen analyzePen(Block feeder) {
        int centerX = feeder.getX();
        int centerZ = feeder.getZ();
        Set<String> inside = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{centerX, centerZ});
        inside.add(key(centerX, centerZ));

        int gates = 0;
        boolean openGate = false;
        boolean escaped = false;

        while (!queue.isEmpty()) {
            int[] point = queue.removeFirst();
            for (int[] direction : DIRECTIONS) {
                int nx = point[0] + direction[0];
                int nz = point[1] + direction[1];

                if (Math.abs(nx - centerX) > PEN_RADIUS || Math.abs(nz - centerZ) > PEN_RADIUS) {
                    escaped = true;
                    continue;
                }

                Block next = feeder.getWorld().getBlockAt(nx, feeder.getY(), nz);
                if (isGate(next)) {
                    if (inside.add(key(nx, nz))) {
                        gates++;
                        if (isOpen(next)) openGate = true;
                    }
                    continue;
                }
                if (isFence(next)) continue;

                if (next.getType().isAir() && inside.add(key(nx, nz))) {
                    queue.add(new int[]{nx, nz});
                }
            }
        }

        List<Animals> animals = new ArrayList<>();
        double horizontal = PEN_RADIUS + 1.0;
        for (Entity entity : feeder.getWorld().getNearbyEntities(
                feeder.getLocation(), horizontal, VERTICAL_RANGE, horizontal)) {
            if (!(entity instanceof Animals animal)) continue;
            if (inside.contains(key(animal.getLocation().getBlockX(), animal.getLocation().getBlockZ()))) {
                animals.add(animal);
            }
        }

        boolean valid = !escaped && gates == 1 && !openGate;
        return new Pen(valid, animals);
    }

    private boolean isFence(Block block) {
        String name = block.getType().name();
        return name.endsWith("_FENCE") && !name.endsWith("_FENCE_GATE");
    }

    private boolean isGate(Block block) {
        return block.getBlockData() instanceof Openable
                && block.getType().name().endsWith("_FENCE_GATE");
    }

    private boolean isOpen(Block block) {
        return block.getBlockData() instanceof Openable openable && openable.isOpen();
    }

    private String key(int x, int z) {
        return x + ":" + z;
    }

    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    private record Pen(boolean valid, List<Animals> animals) {}
}
