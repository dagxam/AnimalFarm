package ru.dagxam.animalfarm;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FarmObjectManager implements Listener {
    private final AnimalFarmPlugin plugin; private FarmAreaAnalyzer areaAnalyzer; private final Set<FarmObjectKey> objects = ConcurrentHashMap.newKeySet();
    public FarmObjectManager(AnimalFarmPlugin plugin, FarmAreaAnalyzer areaAnalyzer) { this.plugin = plugin; this.areaAnalyzer = areaAnalyzer; }
    public void setAreaAnalyzer(FarmAreaAnalyzer areaAnalyzer) { this.areaAnalyzer = areaAnalyzer; }
    public void registerLoaded() { for (var world : plugin.getServer().getWorlds()) for (Chunk chunk : world.getLoadedChunks()) registerChunk(chunk); }
    public void registerChunk(Chunk chunk) { for (BlockState state : chunk.getTileEntities()) if (state instanceof Barrel barrel && typeOf(barrel.getBlock()) != null) objects.add(FarmObjectKey.of(barrel.getLocation())); }
    public Set<FarmObjectKey> objects() { return Set.copyOf(objects); }
    public FarmObjectType typeOf(Block block) { if (hasKey(block, "feeder_block")) return FarmObjectType.LAND_FEEDER; if (hasKey(block, "aquarium_shelf_block")) return FarmObjectType.AQUARIUM_SHELF; return null; }
    public boolean isFarmObject(Block block) { return typeOf(block) != null; }
    public boolean isFeederItem(ItemStack item) { return hasItemKey(item, "feeder_item"); }
    public boolean isAquariumShelfItem(ItemStack item) { return hasItemKey(item, "aquarium_shelf_item"); }
    public UUID ownerOf(Block block) { if (!(block.getState() instanceof Barrel barrel)) return null; String value = barrel.getPersistentDataContainer().get(keyOf("owner_uuid"), PersistentDataType.STRING); if (value == null || value.isBlank()) return null; try { return UUID.fromString(value); } catch (IllegalArgumentException ignored) { return null; } }
    public String ownerNameOf(Block block) { if (!(block.getState() instanceof Barrel barrel)) return null; return barrel.getPersistentDataContainer().get(keyOf("owner_name"), PersistentDataType.STRING); }
    public boolean canAccess(Player player, Block block) { return plugin.ownershipManager().canUse(player, block); }
    public void clear() { objects.clear(); if (areaAnalyzer != null) areaAnalyzer.clear(); }
    public void remove(FarmObjectKey key) { objects.remove(key); if (areaAnalyzer != null) areaAnalyzer.invalidate(key); }
    @EventHandler(ignoreCancelled = true) public void onChunkLoad(ChunkLoadEvent event) { registerChunk(event.getChunk()); }
    @EventHandler(ignoreCancelled = true) public void onPlace(BlockPlaceEvent event) { Block block = event.getBlockPlaced(); if (!(block.getState() instanceof Barrel barrel)) return; boolean feeder = isFeederItem(event.getItemInHand()); boolean aquarium = isAquariumShelfItem(event.getItemInHand()); if (!feeder && !aquarium) return; barrel.getPersistentDataContainer().set(keyOf(feeder ? "feeder_block" : "aquarium_shelf_block"), PersistentDataType.BYTE, (byte) 1); barrel.getPersistentDataContainer().set(keyOf("owner_uuid"), PersistentDataType.STRING, event.getPlayer().getUniqueId().toString()); barrel.getPersistentDataContainer().set(keyOf("owner_name"), PersistentDataType.STRING, event.getPlayer().getName()); barrel.setCustomName(feeder ? "Кормушка" : "Аквариумная кормушка"); barrel.update(true, false); FarmObjectKey key = FarmObjectKey.of(block.getLocation()); objects.add(key); if (areaAnalyzer != null) areaAnalyzer.invalidate(key); }
    @EventHandler(ignoreCancelled = true) public void onBreak(BlockBreakEvent event) { Block block = event.getBlock(); FarmObjectType type = typeOf(block); if (type == null) return; if (!plugin.ownershipManager().canManage(event.getPlayer(), block)) { event.setCancelled(true); event.getPlayer().sendMessage(plugin.message("not-owner")); return; } FarmObjectKey key = FarmObjectKey.of(block.getLocation()); objects.remove(key); if (areaAnalyzer != null) areaAnalyzer.invalidate(key); if (!(block.getState() instanceof Barrel barrel)) return; event.setDropItems(false); ItemStack objectItem = type == FarmObjectType.LAND_FEEDER ? plugin.createFeederItem() : plugin.createAquariumShelfItem(); Location location = block.getLocation(); block.getWorld().dropItemNaturally(location, objectItem); for (ItemStack item : barrel.getInventory().getContents()) if (item != null && !item.getType().isAir()) block.getWorld().dropItemNaturally(location, item.clone()); }
    private boolean hasKey(Block block, String key) { if (block == null || block.getType() != Material.BARREL || !(block.getState() instanceof Barrel barrel)) return false; return barrel.getPersistentDataContainer().has(keyOf(key), PersistentDataType.BYTE); }
    private boolean hasItemKey(ItemStack item, String key) { if (item == null || item.getType() != Material.BARREL || !item.hasItemMeta()) return false; ItemMeta meta = item.getItemMeta(); return meta != null && meta.getPersistentDataContainer().has(keyOf(key), PersistentDataType.BYTE); }
    private NamespacedKey keyOf(String key) { return new NamespacedKey(plugin, key); }
}
