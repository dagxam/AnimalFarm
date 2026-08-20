package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Animals;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Автоматический сбор продукции загона в сундук, установленный у забора. */
public final class ProductionChestManager implements Listener {
    private final JavaPlugin plugin;
    private final NamespacedKey productionDayKey;

    public ProductionChestManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.productionDayKey = new NamespacedKey(plugin, "production_chest_day");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startTask();
    }

    private void startTask() {
        new BukkitRunnable() {
            @Override public void run() { collectAll(); }
        }.runTaskTimer(plugin, 100L, 100L);
    }

    private void collectAll() {
        for (World world : plugin.getServer().getWorlds()) {
            for (var chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities()) {
                    if (!(state instanceof Barrel barrel) || !isFeeder(barrel.getBlock())) continue;
                    Chest chest = findCollectionChest(barrel.getBlock());
                    if (chest == null) continue;
                    collectDaily(barrel, chest);
                }
            }
        }
    }

    private boolean isFeeder(Block block) {
        if (!(block.getState() instanceof Barrel barrel)) return false;
        return barrel.getPersistentDataContainer().has(new NamespacedKey(plugin, "feeder_block"), PersistentDataType.BYTE);
    }

    /** Сундук должен находиться рядом с кормушкой и касаться забора загона. */
    private Chest findCollectionChest(Block feeder) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                Block block = feeder.getRelative(dx, 0, dz);
                if (!(block.getState() instanceof Chest chest)) continue;
                if (touchesFence(block)) return chest;
            }
        }
        return null;
    }

    private boolean touchesFence(Block chest) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                String name = chest.getRelative(dx, 0, dz).getType().name();
                if (name.endsWith("_FENCE") || name.endsWith("_FENCE_GATE")) return true;
            }
        }
        return false;
    }

    private void collectDaily(Barrel feeder, Chest chest) {
        long day = feeder.getWorld().getFullTime() / 24000L;
        long last = chest.getPersistentDataContainer().getOrDefault(productionDayKey, PersistentDataType.LONG, -1L);
        if (last >= day) return;

        List<ItemStack> production = new ArrayList<>();
        int eggsMin = Math.max(0, plugin.getConfig().getInt("production.chicken.eggs-min", 5));
        int eggsMax = Math.max(eggsMin, plugin.getConfig().getInt("production.chicken.eggs-max", 10));
        int milkMin = Math.max(0, plugin.getConfig().getInt("production.dairy.milk-min", 5));
        int milkMax = Math.max(milkMin, plugin.getConfig().getInt("production.dairy.milk-max", 10));
        int woolMin = Math.max(0, plugin.getConfig().getInt("production.sheep.wool-min", 1));
        int woolMax = Math.max(woolMin, plugin.getConfig().getInt("production.sheep.wool-max", 2));

        for (Entity entity : nearbyAnimals(feeder.getBlock().getLocation())) {
            if (!(entity instanceof Animals animal) || !animal.isAdult()) continue;
            if (animal instanceof Chicken) production.add(new ItemStack(Material.EGG, random(eggsMin, eggsMax)));
            else if (animal instanceof org.bukkit.entity.Cow || animal instanceof org.bukkit.entity.Goat || animal instanceof Sheep) {
                production.add(new ItemStack(Material.MILK_BUCKET, random(milkMin, milkMax)));
                if (animal instanceof Sheep sheep) {
                    Material wool = woolMaterial(sheep);
                    production.add(new ItemStack(wool, random(woolMin, woolMax)));
                }
            }
        }

        if (production.isEmpty()) return;
        if (!canFit(chest.getBlockInventory(), production)) return;
        for (ItemStack item : production) chest.getBlockInventory().addItem(item);
        chest.getPersistentDataContainer().set(productionDayKey, PersistentDataType.LONG, day);
        chest.update(true, false);
    }

    private List<Entity> nearbyAnimals(Location location) {
        return new ArrayList<>(location.getWorld().getNearbyEntities(location, 18, 8, 18));
    }

    private boolean canFit(Inventory inventory, List<ItemStack> items) {
        Inventory test = new org.bukkit.inventory.Inventory() {
            // This anonymous inventory is intentionally not used; capacity is checked conservatively below.
            @Override public int getSize() { return inventory.getSize(); }
            @Override public int getMaxStackSize() { return inventory.getMaxStackSize(); }
            @Override public void setMaxStackSize(int size) {}
            @Override public ItemStack getItem(int index) { return inventory.getItem(index); }
            @Override public void setItem(int index, ItemStack item) { inventory.setItem(index, item); }
            @Override public java.util.HashMap<Integer, ItemStack> addItem(ItemStack... items) { return inventory.addItem(items); }
            @Override public java.util.HashMap<Integer, ItemStack> removeItem(ItemStack... items) { return inventory.removeItem(items); }
            @Override public ItemStack[] getContents() { return inventory.getContents(); }
            @Override public void setContents(ItemStack[] contents) { inventory.setContents(contents); }
            @Override public ItemStack[] getStorageContents() { return inventory.getStorageContents(); }
            @Override public void setStorageContents(ItemStack[] contents) { inventory.setStorageContents(contents); }
            @Override public boolean contains(Material material) { return inventory.contains(material); }
            @Override public boolean contains(ItemStack item) { return inventory.contains(item); }
            @Override public boolean contains(Material material, int amount) { return inventory.contains(material, amount); }
            @Override public boolean contains(ItemStack item, int amount) { return inventory.contains(item, amount); }
            @Override public int first(Material material) { return inventory.first(material); }
            @Override public int first(ItemStack item) { return inventory.first(item); }
            @Override public int firstEmpty() { return inventory.firstEmpty(); }
            @Override public java.util.List<org.bukkit.inventory.HumanEntity> getViewers() { return inventory.getViewers(); }
            @Override public String getType() { return inventory.getType().name(); }
            @Override public org.bukkit.event.inventory.InventoryType getType() { return inventory.getType(); }
            @Override public org.bukkit.inventory.InventoryHolder getHolder() { return inventory.getHolder(); }
            @Override public void clear(int index) { inventory.clear(index); }
            @Override public void clear() { inventory.clear(); }
            @Override public java.util.List<org.bukkit.inventory.ItemStack> all(Material material) { return new ArrayList<>(inventory.all(material).values()); }
            @Override public java.util.HashMap<Integer, ItemStack> all(ItemStack item) { return inventory.all(item); }
            @Override public void close() {}
            @Override public java.util.List<org.bukkit.inventory.HumanEntity> getViewers() { return inventory.getViewers(); }
            @Override public org.bukkit.inventory.ItemStack[] getStorageContents() { return inventory.getStorageContents(); }
        };
        // Для надёжности используем свободные слоты и вместимость существующих стопок.
        int free = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack == null || stack.getType().isAir()) free += 64;
            else free += Math.max(0, 64 - stack.getAmount());
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

    /** Если у загона есть сборочный сундук — ванильное яйцо забирается в него. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChickenEgg(EntityDropItemEvent event) {
        if (!(event.getEntity() instanceof Chicken chicken)) return;
        Chest chest = findCollectionChestNearAnimal(chicken);
        if (chest == null) return;
        ItemStack item = event.getItemDrop().getItemStack();
        if (item.getType() != Material.EGG) return;
        if (chest.getBlockInventory().firstEmpty() == -1 && !chest.getBlockInventory().contains(Material.EGG, 1)) return;
        event.setCancelled(true);
        chest.getBlockInventory().addItem(item.clone());
    }

    private Chest findCollectionChestNearAnimal(Chicken chicken) {
        for (int dx = -18; dx <= 18; dx++) for (int dz = -18; dz <= 18; dz++) {
            Block block = chicken.getLocation().getBlock().getRelative(dx, 0, dz);
            if (block.getState() instanceof Chest chest && touchesFence(block)) return chest;
        }
        return null;
    }
}
