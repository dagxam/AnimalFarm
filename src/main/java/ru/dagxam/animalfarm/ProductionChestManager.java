package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Sheep;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Автоматический ежедневный сбор продукции загона.
 * Сундук должен быть обычным сундуком, установленным прямо на/у забора загона.
 */
public final class ProductionChestManager implements Listener {
    private final JavaPlugin plugin;
    private final NamespacedKey feederKey;
    private final NamespacedKey productionDayKey;

    public ProductionChestManager(JavaPlugin plugin) {
        this.plugin = plugin;
        feederKey = new NamespacedKey(plugin, "feeder_block");
        productionDayKey = new NamespacedKey(plugin, "production_chest_day");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        new BukkitRunnable() {
            @Override public void run() {
                collectAll();
            }
        }.runTaskTimer(plugin, 100L, 100L);
    }

    private void collectAll() {
        for (World world : plugin.getServer().getWorlds()) {
            for (var chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities()) {
                    if (state instanceof Barrel barrel && isFeeder(barrel.getBlock())) {
                        Chest chest = findCollectionChest(barrel.getBlock());
                        if (chest != null) collectDaily(barrel, chest);
                    }
                }
            }
        }
    }

    private boolean isFeeder(Block block) {
        return block.getState() instanceof Barrel barrel
                && barrel.getPersistentDataContainer().has(feederKey, PersistentDataType.BYTE);
    }

    /**
     * Поддерживаются основные варианты расположения:
     * 1) сундук непосредственно на блоке забора;
     * 2) сундук рядом с забором на той же высоте;
     * 3) сундук на один блок выше/ниже кормушки, если он касается забора.
     */
    private Chest findCollectionChest(Block feeder) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    Block block = feeder.getRelative(dx, dy, dz);
                    if (!(block.getState() instanceof Chest chest)) continue;
                    if (touchesFence(block) || chestOnFence(block)) return chest;
                }
            }
        }
        return null;
    }

    private boolean chestOnFence(Block chest) {
        // Сундук, стоящий непосредственно над/на линии забора.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                String below = chest.getRelative(dx, -1, dz).getType().name();
                String same = chest.getRelative(dx, 0, dz).getType().name();
                if (below.endsWith("_FENCE") || below.endsWith("_FENCE_GATE")
                        || same.endsWith("_FENCE") || same.endsWith("_FENCE_GATE")) return true;
            }
        }
        return false;
    }

    private boolean touchesFence(Block chest) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    String name = chest.getRelative(dx, dy, dz).getType().name();
                    if (name.endsWith("_FENCE") || name.endsWith("_FENCE_GATE")) return true;
                }
            }
        }
        return false;
    }

    private void collectDaily(Barrel feeder, Chest chest) {
        long day = feeder.getWorld().getFullTime() / 24000L;
        long last = chest.getPersistentDataContainer().getOrDefault(productionDayKey, PersistentDataType.LONG, -1L);
        if (last >= day) return;

        List<ItemStack> production = new ArrayList<>();
        int milkMin = Math.max(0, plugin.getConfig().getInt("production.chest.milk-min", 5));
        int milkMax = Math.max(milkMin, plugin.getConfig().getInt("production.chest.milk-max", 10));
        int woolMin = Math.max(0, plugin.getConfig().getInt("production.chest.wool-min", 1));
        int woolMax = Math.max(woolMin, plugin.getConfig().getInt("production.chest.wool-max", 2));
        int eggMin = Math.max(5, plugin.getConfig().getInt("production.chest.egg-min", 5));
        int eggMax = Math.max(eggMin, plugin.getConfig().getInt("production.chest.egg-max", 10));

        // Производство берём только из животных, находящихся рядом с конкретной кормушкой.
        // Одна овца сама по себе может производить шерсть; для молока и яиц действуют обычные
        // суточные правила продукции, независимо от количества пары для размножения.
        for (Entity entity : feeder.getBlock().getWorld().getNearbyEntities(
                feeder.getBlock().getLocation().add(0.5, 0.5, 0.5), 16, 6, 16)) {
            if (!(entity instanceof Animals animal) || !animal.isAdult()) continue;

            if (entity instanceof org.bukkit.entity.Cow
                    || entity instanceof org.bukkit.entity.Goat
                    || entity instanceof org.bukkit.entity.Sheep) {
                production.add(new ItemStack(Material.MILK_BUCKET, random(milkMin, milkMax)));
            }

            if (entity instanceof Sheep sheep) {
                production.add(new ItemStack(woolMaterial(sheep), random(woolMin, woolMax)));
            }

            if (entity instanceof Chicken) {
                production.add(new ItemStack(Material.EGG, random(eggMin, eggMax)));
            }
        }

        // Даже если животных нет, день не отмечаем: как только животное появится,
        // следующий проход сможет собрать продукцию.
        if (production.isEmpty()) return;
        if (!canFit(chest.getBlockInventory(), production)) return;

        for (ItemStack item : production) {
            MapUtil.addAll(chest.getBlockInventory(), item);
        }

        chest.getPersistentDataContainer().set(productionDayKey, PersistentDataType.LONG, day);
        chest.update(true, false);
    }

    private boolean canFit(Inventory inventory, List<ItemStack> items) {
        Inventory test = inventory;
        int free = 0;
        int maxStack = Math.max(1, test.getMaxStackSize());
        for (ItemStack stack : test.getStorageContents()) {
            if (stack == null || stack.getType().isAir()) free += maxStack;
            else free += Math.max(0, maxStack - stack.getAmount());
        }
        int need = 0;
        for (ItemStack item : items) need += item.getAmount();
        return free >= need;
    }

    private Material woolMaterial(Sheep sheep) {
        return switch (sheep.getColor()) {
            case WHITE -> Material.WHITE_WOOL;
            case ORANGE -> Material.ORANGE_WOOL;
            case MAGENTA -> Material.MAGENTA_WOOL;
            case LIGHT_BLUE -> Material.LIGHT_BLUE_WOOL;
            case YELLOW -> Material.YELLOW_WOOL;
            case LIME -> Material.LIME_WOOL;
            case PINK -> Material.PINK_WOOL;
            case GRAY -> Material.GRAY_WOOL;
            case LIGHT_GRAY -> Material.LIGHT_GRAY_WOOL;
            case CYAN -> Material.CYAN_WOOL;
            case PURPLE -> Material.PURPLE_WOOL;
            case BLUE -> Material.BLUE_WOOL;
            case BROWN -> Material.BROWN_WOOL;
            case GREEN -> Material.GREEN_WOOL;
            case RED -> Material.RED_WOOL;
            case BLACK -> Material.BLACK_WOOL;
        };
    }

    private int random(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChickenEgg(EntityDropItemEvent event) {
        if (!(event.getEntity() instanceof Chicken chicken)) return;
        ItemStack item = event.getItemDrop().getItemStack();
        if (item.getType() != Material.EGG) return;
        Chest chest = findCollectionChestNearAnimal(chicken);
        if (chest != null) {
            // Не удаляем яйцо: при наличии сундука оно переносится в сундук.
            event.setCancelled(true);
            if (!addToChest(chest.getBlockInventory(), item.clone())) {
                chicken.getWorld().dropItemNaturally(chicken.getLocation(), item.clone());
            }
        }
        // Без сундука ванильное яйцо остаётся на полу загона.
    }

    private Chest findCollectionChestNearAnimal(Chicken chicken) {
        Location location = chicken.getLocation();
        for (int dx = -20; dx <= 20; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -20; dz <= 20; dz++) {
                    Block block = location.getBlock().getRelative(dx, dy, dz);
                    if (block.getState() instanceof Chest chest && (touchesFence(block) || chestOnFence(block))) return chest;
                }
            }
        }
        return null;
    }

    private boolean addToChest(Inventory inventory, ItemStack item) {
        return inventory.addItem(item).isEmpty();
    }

    private static final class MapUtil {
        private MapUtil() {}
        static void addAll(Inventory inventory, ItemStack item) {
            int remaining = item.getAmount();
            while (remaining > 0) {
                int amount = Math.min(remaining, item.getMaxStackSize());
                ItemStack stack = item.clone();
                stack.setAmount(amount);
                var leftovers = inventory.addItem(stack);
                int left = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
                if (left >= amount) return;
                remaining -= amount - left;
            }
        }
    }
}
