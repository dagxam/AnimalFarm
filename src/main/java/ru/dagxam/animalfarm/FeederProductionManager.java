package ru.dagxam.animalfarm;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Суточная молочная механика кормушки.
 * Яйца и шерсть автоматически в кормушку НЕ добавляются.
 * Игрок заранее кладёт пустые ведра в кормушку; один раз за игровые сутки
 * они заполняются молоком от взрослых коров, овец и коз.
 */
public final class FeederProductionManager implements Listener {
    private static final int MILK_MAX_STACK = 16;

    private final JavaPlugin plugin;
    private final NamespacedKey feederKey;
    private final NamespacedKey milkProductionDayKey;
    private final Map<UUID, Long> milkedAnimals = new HashMap<>();

    public FeederProductionManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.feederKey = new NamespacedKey(plugin, "feeder_block");
        this.milkProductionDayKey = new NamespacedKey(plugin, "milk_production_day");

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
                        fillMilk(barrel);
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

    private void fillMilk(Barrel barrel) {
        long day = barrel.getWorld().getFullTime() / 24000L;
        long lastDay = barrel.getPersistentDataContainer().getOrDefault(
                milkProductionDayKey, PersistentDataType.LONG, -1L
        );
        if (lastDay >= day) return;

        Inventory inventory = barrel.getInventory();
        int emptyBuckets = count(inventory, Material.BUCKET);
        if (emptyBuckets <= 0) return;

        int milkBuckets = 0;
        int min = Math.max(0, plugin.getConfig().getInt("milking.milk-min", 2));
        int max = Math.max(min, plugin.getConfig().getInt("milking.milk-max", 3));

        for (Entity entity : barrel.getWorld().getNearbyEntities(
                barrel.getBlock().getLocation(), 17, 6, 17)) {
            if (!(entity instanceof Animals animal) || !animal.isAdult()) continue;
            String type = animal.getType().name();
            if (!type.equals("COW") && !type.equals("SHEEP") && !type.equals("GOAT")) continue;
            milkBuckets += ThreadLocalRandom.current().nextInt(min, max + 1);
        }

        if (milkBuckets <= 0) return;
        milkBuckets = Math.min(milkBuckets, emptyBuckets);

        remove(inventory, Material.BUCKET, milkBuckets);
        addMilk(inventory, milkBuckets);

        barrel.getPersistentDataContainer().set(milkProductionDayKey, PersistentDataType.LONG, day);
        barrel.update(true, false);
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
            int take = Math.min(amount, item.getAmount());
            if (take == item.getAmount()) inventory.setItem(slot, null);
            else item.setAmount(item.getAmount() - take);
            amount -= take;
        }
    }

    private void addMilk(Inventory inventory, int amount) {
        ItemStack milk = new ItemStack(Material.MILK_BUCKET);
        ItemMeta meta = milk.getItemMeta();
        if (meta != null) {
            meta.setMaxStackSize(MILK_MAX_STACK);
            milk.setItemMeta(meta);
        }

        while (amount > 0) {
            int add = Math.min(amount, MILK_MAX_STACK);
            ItemStack stack = milk.clone();
            stack.setAmount(add);
            Map<Integer, ItemStack> leftovers = inventory.addItem(stack);
            if (!leftovers.isEmpty()) {
                // Если кормушка заполнена, не теряем молоко: возвращаем пустые ведра.
                int returned = 0;
                for (ItemStack left : leftovers.values()) returned += left.getAmount();
                if (returned > 0) addEmptyBuckets(inventory, returned);
                break;
            }
            amount -= add;
        }
    }

    private void addEmptyBuckets(Inventory inventory, int amount) {
        while (amount > 0) {
            int add = Math.min(amount, 16);
            inventory.addItem(new ItemStack(Material.BUCKET, add));
            amount -= add;
        }
    }

    private void normalizeMilkBuckets() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
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

    /** Яйца остаются обычными и могут появляться на полу. */
    @EventHandler(ignoreCancelled = true)
    public void onChickenEggDrop(EntityDropItemEvent event) {
        // Ничего не перехватываем.
    }

    /** Один и тот же взрослый моб может быть надоен вручную только один раз за игровые сутки. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDailyManualMilking(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() != Material.BUCKET) return;

        Entity target = event.getRightClicked();
        if (!(target instanceof Animals animal) || !animal.isAdult()) return;
        String type = animal.getType().name();
        if (!type.equals("COW") && !type.equals("SHEEP") && !type.equals("GOAT")) return;

        long day = animal.getWorld().getFullTime() / 24000L;
        long lastDay = milkedAnimals.getOrDefault(animal.getUniqueId(), -1L);
        if (lastDay >= day) {
            event.setCancelled(true);
            player.sendMessage(plugin.getConfig().getString("messages.prefix", "")
                    + plugin.getConfig().getString("messages.milk-cooldown", "&eВы уже доили сегодня."));
            return;
        }

        milkedAnimals.put(animal.getUniqueId(), day);
    }
}
