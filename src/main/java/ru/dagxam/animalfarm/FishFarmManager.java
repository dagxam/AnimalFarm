package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fish;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/** Аквариум: закрытый квадрат из стекла с кормушкой внутри. */
public final class FishFarmManager implements Listener {
    private final JavaPlugin plugin;
    private final NamespacedKey feederKey;
    private final NamespacedKey dayKey;

    public FishFarmManager(JavaPlugin plugin) {
        this.plugin = plugin;
        feederKey = new NamespacedKey(plugin, "feeder_block");
        dayKey = new NamespacedKey(plugin, "fish_breeding_day");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        new BukkitRunnable() {
            @Override public void run() { processAquariums(); }
        }.runTaskTimer(plugin, 40L, 40L);
    }

    private void processAquariums() {
        for (World world : plugin.getServer().getWorlds()) {
            for (var chunk : world.getLoadedChunks()) {
                for (var state : chunk.getTileEntities()) {
                    if (state instanceof Barrel barrel && isFeeder(barrel.getBlock())) processAquarium(barrel);
                }
            }
        }
    }

    private void processAquarium(Barrel feeder) {
        Aquarium aquarium = findAquarium(feeder.getBlock());
        if (aquarium == null) return;
        long day = feeder.getWorld().getFullTime() / 24000L;
        long last = feeder.getPersistentDataContainer().getOrDefault(dayKey, PersistentDataType.LONG, -1L);
        if (last >= day) return;

        List<Fish> fish = new ArrayList<>();
        for (Entity entity : feeder.getWorld().getNearbyEntities(feeder.getLocation(), 16, 8, 16)) {
            if (!(entity instanceof Fish f) || !aquarium.inside(f.getLocation())) continue;
            if (!isWater(f.getLocation().getBlock())) continue;
            fish.add(f);
        }

        Map<EntityType, List<Fish>> groups = new HashMap<>();
        for (Fish f : fish) groups.computeIfAbsent(f.getType(), ignored -> new ArrayList<>()).add(f);
        int pairs = 0;
        int maxPairs = Math.max(1, plugin.getConfig().getInt("feeder.max-breeding-pairs-per-day", 10));

        for (var entry : groups.entrySet()) {
            List<Fish> group = entry.getValue();
            for (int i = 0; i + 1 < group.size() && pairs < maxPairs; i += 2) {
                if (!consumeSeeds(feeder.getInventory(), 2) || !consumeWater(feeder)) break;
                Location spawn = findWater(feeder, aquarium);
                if (spawn == null) break;
                feeder.getWorld().spawnEntity(spawn, entry.getKey());
                pairs++;
            }
            if (pairs >= maxPairs) break;
        }

        feeder.getPersistentDataContainer().set(dayKey, PersistentDataType.LONG, day);
        feeder.update(true, false);
    }

    private boolean consumeSeeds(Inventory inv, int amount) {
        int available = 0;
        for (ItemStack item : inv.getContents()) if (item != null && isSeed(item.getType())) available += item.getAmount();
        if (available < amount) return false;
        for (int slot = 0; slot < inv.getSize() && amount > 0; slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null || !isSeed(item.getType())) continue;
            int take = Math.min(amount, item.getAmount());
            if (take == item.getAmount()) inv.setItem(slot, null); else item.setAmount(item.getAmount() - take);
            amount -= take;
        }
        return true;
    }

    private boolean isSeed(Material m) {
        return m.name().endsWith("_SEEDS") || m == Material.PITCHER_POD;
    }

    private boolean consumeWater(Barrel feeder) {
        Inventory inv = feeder.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType() != Material.WATER_BUCKET) continue;
            if (item.getAmount() == 1) inv.setItem(slot, null); else item.setAmount(item.getAmount() - 1);
            Map<Integer, ItemStack> left = inv.addItem(new ItemStack(Material.BUCKET));
            if (!left.isEmpty()) feeder.getWorld().dropItemNaturally(feeder.getLocation(), left.values().iterator().next());
            return true;
        }
        return false;
    }

    private Location findWater(Barrel feeder, Aquarium a) {
        for (int x = a.minX + 1; x < a.maxX; x++) for (int z = a.minZ + 1; z < a.maxZ; z++) {
            Block b = feeder.getWorld().getBlockAt(x, feeder.getY(), z);
            if (isWater(b)) return b.getLocation().add(0.5, 0.2, 0.5);
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFishPickup(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!player.getInventory().getItemInMainHand().getType().isAir()) return;
        if (!(event.getRightClicked() instanceof Fish fish)) return;
        Aquarium aquarium = findAquariumNear(fish);
        if (aquarium == null) return;
        event.setCancelled(true);
        Material item = switch (fish.getType()) {
            case COD -> Material.COD;
            case SALMON -> Material.SALMON;
            case TROPICAL_FISH -> Material.TROPICAL_FISH;
            case PUFFERFISH -> Material.PUFFERFISH;
            default -> null;
        };
        if (item == null) return;
        fish.remove();
        Map<Integer, ItemStack> left = player.getInventory().addItem(new ItemStack(item));
        for (ItemStack stack : left.values()) player.getWorld().dropItemNaturally(player.getLocation(), stack);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onReleaseRawFish(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        EntityType type = switch (hand.getType()) {
            case COD -> EntityType.COD;
            case SALMON -> EntityType.SALMON;
            case TROPICAL_FISH -> EntityType.TROPICAL_FISH;
            case PUFFERFISH -> EntityType.PUFFERFISH;
            default -> null;
        };
        if (type == null) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        Block water = isWater(clicked) ? clicked : clicked.getRelative(event.getBlockFace());
        if (!isWater(water)) return; // На земле предмет не трогаем.
        event.setCancelled(true);
        Location spawn = water.getLocation().add(0.5, 0.2, 0.5);
        player.getWorld().spawnEntity(spawn, type);
        if (hand.getAmount() <= 1) player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        else hand.setAmount(hand.getAmount() - 1);
    }

    private Aquarium findAquariumNear(Fish fish) {
        for (var chunk : fish.getWorld().getLoadedChunks()) for (var state : chunk.getTileEntities())
            if (state instanceof Barrel barrel && isFeeder(barrel.getBlock())) {
                Aquarium a = findAquarium(barrel.getBlock());
                if (a != null && a.inside(fish.getLocation())) return a;
            }
        return null;
    }

    private Aquarium findAquarium(Block feeder) {
        int max = 16;
        int left = -1, right = -1, north = -1, south = -1;
        for (int d = 1; d <= max; d++) {
            if (left == -1 && isGlass(feeder.getWorld().getBlockAt(feeder.getX() - d, feeder.getY(), feeder.getZ()))) left = feeder.getX() - d;
            if (right == -1 && isGlass(feeder.getWorld().getBlockAt(feeder.getX() + d, feeder.getY(), feeder.getZ()))) right = feeder.getX() + d;
            if (north == -1 && isGlass(feeder.getWorld().getBlockAt(feeder.getX(), feeder.getY(), feeder.getZ() - d))) north = feeder.getZ() - d;
            if (south == -1 && isGlass(feeder.getWorld().getBlockAt(feeder.getX(), feeder.getY(), feeder.getZ() + d))) south = feeder.getZ() + d;
        }
        if (left < 0 || right < 0 || north < 0 || south < 0) return null;
        for (int x = left; x <= right; x++) {
            if (!isGlass(feeder.getWorld().getBlockAt(x, feeder.getY(), north))) return null;
            if (!isGlass(feeder.getWorld().getBlockAt(x, feeder.getY(), south))) return null;
        }
        for (int z = north; z <= south; z++) {
            if (!isGlass(feeder.getWorld().getBlockAt(left, feeder.getY(), z))) return null;
            if (!isGlass(feeder.getWorld().getBlockAt(right, feeder.getY(), z))) return null;
        }
        return new Aquarium(left, right, north, south, feeder.getY());
    }

    private boolean isGlass(Block b) {
        String n = b.getType().name();
        return n.equals("GLASS") || n.endsWith("_GLASS") || n.endsWith("_GLASS_PANE");
    }

    private boolean isWater(Block b) {
        return b.getType() == Material.WATER;
    }

    private boolean isFeeder(Block b) {
        return b.getType() == Material.BARREL && b.getState() instanceof Barrel barrel
                && barrel.getPersistentDataContainer().has(feederKey, PersistentDataType.BYTE);
    }

    private record Aquarium(int minX, int maxX, int minZ, int maxZ, int y) {
        boolean inside(Location l) {
            return l.getBlockX() > minX && l.getBlockX() < maxX && l.getBlockZ() > minZ && l.getBlockZ() < maxZ && Math.abs(l.getBlockY() - y) <= 2;
        }
    }
}
