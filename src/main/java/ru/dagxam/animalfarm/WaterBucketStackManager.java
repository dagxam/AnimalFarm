package ru.dagxam.animalfarm;

import org.bukkit.Material;
import org.bukkit.block.Barrel;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Делает обычные полные ведра воды стекаемыми и объединяет их по 16. */
public final class WaterBucketStackManager {
    private static final int MAX_STACK = 16;
    private final JavaPlugin plugin;

    public WaterBucketStackManager(JavaPlugin plugin) {
        this.plugin = plugin;
        new BukkitRunnable() {
            @Override public void run() {
                normalizeOnlinePlayers();
                normalizeLoadedFeeders();
            }
        }.runTaskTimer(plugin, 1L, 20L);
    }

    private void normalizeOnlinePlayers() {
        for (Player player : plugin.getServer().getOnlinePlayers()) normalizeInventory(player.getInventory());
    }

    private void normalizeLoadedFeeders() {
        for (var world : plugin.getServer().getWorlds()) for (var chunk : world.getLoadedChunks())
            for (BlockState state : chunk.getTileEntities()) if (state instanceof Barrel barrel && isFeeder(barrel)) normalizeInventory(barrel.getInventory());
    }

    private boolean isFeeder(Barrel barrel) {
        return barrel.getPersistentDataContainer().has(new org.bukkit.NamespacedKey(plugin, "feeder_block"), org.bukkit.persistence.PersistentDataType.BYTE);
    }

    private void normalizeInventory(Inventory inventory) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) if (item != null && item.getType() == Material.WATER_BUCKET) total += item.getAmount();
        if (total == 0) return;

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && item.getType() == Material.WATER_BUCKET) inventory.setItem(slot, null);
        }

        for (int slot = 0; slot < inventory.getSize() && total > 0; slot++) {
            ItemStack old = inventory.getItem(slot);
            if (old != null && !old.getType().isAir()) continue;
            int amount = Math.min(total, MAX_STACK);
            ItemStack stack = new ItemStack(Material.WATER_BUCKET, amount);
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) { meta.setMaxStackSize(MAX_STACK); stack.setItemMeta(meta); }
            inventory.setItem(slot, stack);
            total -= amount;
        }

        if (total > 0) {
            ItemStack overflow = new ItemStack(Material.WATER_BUCKET, Math.min(total, MAX_STACK));
            ItemMeta meta = overflow.getItemMeta();
            if (meta != null) { meta.setMaxStackSize(MAX_STACK); overflow.setItemMeta(meta); }
            inventory.addItem(overflow);
        }
    }
}
