package ru.dagxam.animalfarm;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Sheep;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.ThreadLocalRandom;

/** Ежесуточно собирает шерсть взрослых овец прямо в кормушку. */
public final class FeederProductionManager {
    private final JavaPlugin plugin;
    private final NamespacedKey feederKey;
    private final NamespacedKey woolDayKey;

    public FeederProductionManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.feederKey = new NamespacedKey(plugin, "feeder_block");
        this.woolDayKey = new NamespacedKey(plugin, "feeder_wool_day");
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
                        collect(barrel);
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

    private void collect(Barrel barrel) {
        long day = barrel.getWorld().getFullTime() / 24000L;
        long last = barrel.getPersistentDataContainer().getOrDefault(woolDayKey, PersistentDataType.LONG, -1L);
        if (last >= day) return;

        int min = Math.max(0, plugin.getConfig().getInt("production.wool.min", 1));
        int max = Math.max(min, plugin.getConfig().getInt("production.wool.max", 2));
        int total = 0;

        for (Entity entity : barrel.getBlock().getWorld().getNearbyEntities(barrel.getBlock().getLocation(), 17, 6, 17)) {
            if (!(entity instanceof Sheep sheep) || !sheep.isAdult()) continue;
            total += ThreadLocalRandom.current().nextInt(min, max + 1);
        }

        if (total > 0) {
            // Для автоматического сбора используем белую шерсть по умолчанию.
            // Цветная шерсть при ручной стрижке остаётся ванильной.
            barrel.getInventory().addItem(new ItemStack(Material.WHITE_WOOL, total));
        }

        barrel.getPersistentDataContainer().set(woolDayKey, PersistentDataType.LONG, day);
        barrel.update(true, false);
    }
}
