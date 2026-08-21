package ru.dagxam.animalfarm;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Шерсть больше не хранится и не производится кормушкой: овец стригут вручную обычными ножницами. */
public final class ManualWoolManager implements Listener {
    private final JavaPlugin plugin;
    private final NamespacedKey feederKey;

    public ManualWoolManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.feederKey = new NamespacedKey(plugin, "feeder_block");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        new BukkitRunnable() {
            @Override public void run() { clearFeederWool(); }
        }.runTaskTimer(plugin, 1L, 20L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!isFeederInventory(event.getInventory())) return;
        if (isWool(event.getCursor()) || isWool(event.getCurrentItem())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!isFeederInventory(event.getInventory())) return;
        if (isWool(event.getOldCursor())) event.setCancelled(true);
    }

    private boolean isFeederInventory(org.bukkit.inventory.Inventory inventory) {
        return inventory.getHolder() instanceof Barrel barrel
                && barrel.getPersistentDataContainer().has(feederKey, PersistentDataType.BYTE);
    }

    private boolean isWool(ItemStack item) {
        return item != null && item.getType().name().endsWith("_WOOL");
    }

    private void clearFeederWool() {
        for (World world : plugin.getServer().getWorlds()) {
            for (var chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities()) {
                    if (!(state instanceof Barrel barrel)) continue;
                    if (!barrel.getPersistentDataContainer().has(feederKey, PersistentDataType.BYTE)) continue;
                    boolean changed = false;
                    for (int slot = 0; slot < barrel.getInventory().getSize(); slot++) {
                        ItemStack item = barrel.getInventory().getItem(slot);
                        if (isWool(item)) {
                            barrel.getInventory().setItem(slot, null);
                            changed = true;
                        }
                    }
                    if (changed) barrel.update(true, false);
                }
            }
        }
    }
}
