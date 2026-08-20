package ru.dagxam.animalfarm;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Ежесуточное производство продукции прямо в кормушке.
 * Курица: 10 яиц. Овца/баран: 2-3 шерсти и 2-3 ведра молока.
 * Корова: 2-3 ведра молока. Коза: 2-3 ведра молока.
 */
public final class FeederProductionManager implements Listener {
    private static final int MILK_MAX_STACK = 16;

    private final JavaPlugin plugin;
    private final NamespacedKey feederKey;
    private final NamespacedKey productionDayKey;

    public FeederProductionManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.feederKey = new NamespacedKey(plugin, "feeder_block");
        this.productionDayKey = new NamespacedKey(plugin, "production_day");

        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (plugin.getConfig().getBoolean("feeder.enabled", true)) {
                    processAllFeeders();
                    normalizeMilkBuckets();
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void processAllFeeders() {
        for (World world : plugin.getServer().getWorlds()) {
            for (var chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities()) {
                    if (state instanceof Barrel barrel && isFeeder(barrel.getBlock())) {
                        produce(barrel);
                    }
                }
            }
        }
    }

    private boolean isFeeder(Block block) {
        if (block == null || block.getType() != Material.BARREL) return false;
        if (!(block.getState() instanceof Barrel barrel)) return false;
        return barrel.getPersistentDataContainer().has(feederKey, PersistentDataType.BYTE);
    }

    private void produce(Barrel barrel) {
        long day = barrel.getWorld().getFullTime() / 24000L;
        long lastDay = barrel.getPersistentDataContainer().getOrDefault(
                productionDayKey, PersistentDataType.LONG, -1L
        );
        if (lastDay >= day) return;

        int eggs = 0;
        int wool = 0;
        int milk = 0;
        boolean hasAnimals = false;

        for (Entity entity : barrel.getWorld().getNearbyEntities(
                barrel.getBlock().getLocation(), 17, 6, 17)) {
            if (!(entity instanceof org.bukkit.entity.Animals animal) || !animal.isAdult()) continue;

            if (entity.getType() == EntityType.CHICKEN) {
                eggs += 10;
                hasAnimals = true;
            } else if (entity.getType() == EntityType.SHEEP) {
                wool += random(2, 3);
                milk += random(2, 3);
                hasAnimals = true;
            } else if (entity.getType() == EntityType.COW || entity.getType() == EntityType.GOAT) {
                milk += random(2, 3);
                hasAnimals = true;
            }
        }

        if (!hasAnimals) return;

        Inventory inventory = barrel.getInventory();

        if (eggs > 0) {
            addStackable(inventory, new ItemStack(Material.EGG), eggs);
        }
        if (wool > 0) {
            addStackable(inventory, new ItemStack(Material.WHITE_WOOL), wool);
        }
        if (milk > 0) {
            addStackable(inventory, createMilkBucket(1), milk);
        }

        barrel.getPersistentDataContainer().set(productionDayKey, PersistentDataType.LONG, day);
        barrel.update(true, false);
    }

    private int random(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private ItemStack createMilkBucket(int amount) {
        ItemStack item = new ItemStack(Material.MILK_BUCKET, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setMaxStackSize(MILK_MAX_STACK);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void addStackable(Inventory inventory, ItemStack prototype, int amount) {
        while (amount > 0) {
            int add = Math.min(amount, prototype.getMaxStackSize());
            ItemStack stack = prototype.clone();
            stack.setAmount(add);
            var leftovers = inventory.addItem(stack);
            int inserted = add;
            for (ItemStack item : leftovers.values()) {
                if (item != null) inserted -= item.getAmount();
            }
            if (inserted <= 0) break;
            amount -= inserted;
            if (!leftovers.isEmpty()) break;
        }
    }

    private void normalizeMilkBuckets() {
        for (var player : plugin.getServer().getOnlinePlayers()) {
            normalizeInventory(player.getInventory());
        }

        for (World world : plugin.getServer().getWorlds()) {
            for (var chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities()) {
                    if (state instanceof Barrel barrel && isFeeder(barrel.getBlock())) {
                        normalizeInventory(barrel.getInventory());
                    }
                }
            }
        }
    }

    private void normalizeInventory(Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() != Material.MILK_BUCKET) continue;
            ItemMeta meta = item.getItemMeta();
            if (meta == null) continue;
            if (!meta.hasMaxStackSize() || meta.getMaxStackSize() != MILK_MAX_STACK) {
                meta.setMaxStackSize(MILK_MAX_STACK);
                item.setItemMeta(meta);
                inventory.setItem(slot, item);
            }
        }
    }

    /** Не даём яйцам от кур внутри кормушки падать на землю. */
    @EventHandler(ignoreCancelled = true)
    public void onChickenEggDrop(EntityDropItemEvent event) {
        if (!(event.getEntity() instanceof Chicken chicken)) return;

        for (World world : plugin.getServer().getWorlds()) {
            for (var chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities()) {
                    if (!(state instanceof Barrel barrel) || !isFeeder(barrel.getBlock())) continue;
                    if (barrel.getWorld() != chicken.getWorld()) continue;
                    if (barrel.getLocation().distanceSquared(chicken.getLocation()) > 17 * 17 + 6 * 6) continue;

                    ItemStack drop = event.getItemDrop().getItemStack();
                    if (drop.getType() == Material.EGG) {
                        event.setCancelled(true);
                    }
                    return;
                }
            }
        }
    }
}
