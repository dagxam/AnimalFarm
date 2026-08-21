package ru.dagxam.animalfarm;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class FarmObjectManager2 implements Listener {
    private final AnimalFarmPlugin plugin;
    private final NamespacedKey feederItemKey;
    private final NamespacedKey feederBlockKey;
    private final NamespacedKey aquariumItemKey;
    private final NamespacedKey aquariumBlockKey;
    private final Set<FarmObjectKey> objects = ConcurrentHashMap.newKeySet();

    public FarmObjectManager2(AnimalFarmPlugin plugin) {
        this.plugin = plugin;
        feederItemKey = new NamespacedKey(plugin, "feeder_item");
        feederBlockKey = new NamespacedKey(plugin, "feeder_block");
        aquariumItemKey = new NamespacedKey(plugin, "aquarium_shelf_item");
        aquariumBlockKey = new NamespacedKey(plugin, "aquarium_shelf_block");
    }

    public void registerLoaded() {
        for (var world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) registerChunk(chunk);
        }
    }

    public void registerChunk(Chunk chunk) {
        for (BlockState state : chunk.getTileEntities()) {
            if (state instanceof Barrel barrel && typeOf(barrel.getBlock()) != null) {
                objects.add(FarmObjectKey.of(barrel.getLocation()));
            }
        }
    }

    public Set<FarmObjectKey> objects() { return Set.copyOf(objects); }

    public FarmObjectType typeOf(Block block) {
        if (hasBlockKey(block, feederBlockKey)) return FarmObjectType.LAND_FEEDER;
        if (hasBlockKey(block, aquariumBlockKey)) return FarmObjectType.AQUARIUM_SHELF;
        return null;
    }

    public boolean isFarmObject(Block block) { return typeOf(block) != null; }

    public boolean isFeederItem(ItemStack item) { return hasItemKey(item, feederItemKey); }
    public boolean isAquariumShelfItem(ItemStack item) { return hasItemKey(item, aquariumItemKey); }

    public void clear() { objects.clear(); }

    public void remove(Location location) { objects.remove(FarmObjectKey.of(location)); }

    @EventHandler(ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) { registerChunk(event.getChunk()); }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!(event.getBlockPlaced().getState() instanceof Barrel barrel)) return;
        boolean feeder = isFeederItem(event.getItemInHand());
        boolean aquarium = isAquariumShelfItem(event.getItemInHand());
        if (!feeder && !aquarium) return;

        barrel.getPersistentDataContainer().set(
                feeder ? feederBlockKey : aquariumBlockKey,
                PersistentDataType.BYTE,
                (byte) 1
        );
        barrel.setCustomName(feeder ? "Кормушка" : "Аквариумная полка");
        barrel.update(true, false);
        objects.add(FarmObjectKey.of(event.getBlockPlaced().getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        FarmObjectType type = typeOf(block);
        if (type == null || !(block.getState() instanceof Barrel barrel)) return;
        objects.remove(FarmObjectKey.of(block.getLocation()));
        event.setDropItems(false);

        ItemStack objectItem = type == FarmObjectType.LAND_FEEDER
                ? plugin.createFeederItem()
                : plugin.createAquariumShelfItem();
        block.getWorld().dropItemNaturally(block.getLocation(), objectItem);
        for (ItemStack item : barrel.getInventory().getContents()) {
            if (item != null && !item.getType().isAir()) {
                block.getWorld().dropItemNaturally(block.getLocation(), item.clone());
            }
        }
    }

    private boolean hasBlockKey(Block block, NamespacedKey key) {
        if (block == null || block.getType() != Material.BARREL || !(block.getState() instanceof Barrel barrel)) return false;
        return barrel.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private boolean hasItemKey(ItemStack item, NamespacedKey key) {
        if (item == null || item.getType() != Material.BARREL || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }
}
