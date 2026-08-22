package ru.dagxam.animalfarm;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fish;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;

import java.util.Comparator;

/** Allows taking one supported aquarium creature with a right click on water. */
public final class AquariumFishHarvestManager implements Listener {
    private static final double MAX_INTERACTION_DISTANCE = 6.0D;

    private final AnimalFarmPlugin plugin;

    public AquariumFishHarvestManager(AnimalFarmPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) return;
        if (isFreshFish(event.getItem())) return;

        Player player = event.getPlayer();
        Block water = findTargetWater(player, event.getClickedBlock());
        if (water == null) return;

        FarmObjectKey aquariumKey = findAquarium(water.getLocation());
        if (aquariumKey == null) return;

        Location aquarium = aquariumKey.location(plugin.getServer());
        if (plugin.settings().aquariumOwnerOnlyHarvest()
                && !plugin.farmObjectManager().canAccess(player, aquarium.getBlock())) {
            player.sendMessage(plugin.message("not-owner"));
            event.setCancelled(true);
            return;
        }

        int radius = plugin.settings().aquariumMaxRadius();
        int vertical = plugin.settings().aquariumVerticalRange();
        Location target = water.getLocation().add(0.5, 0.5, 0.5);
        Location center = aquarium.clone().add(0.5, 0.5, 0.5);

        Entity creature = water.getWorld().getNearbyEntities(target, radius, vertical, radius, entity ->
                        isSupportedAquariumCreature(entity) && isInsideAquarium(entity.getLocation(), center, radius, vertical))
                .stream()
                .min(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(target)))
                .orElse(null);

        if (creature == null) {
            player.sendMessage(plugin.message("aquarium-no-fish"));
            event.setCancelled(true);
            return;
        }

        ItemStack item = toHarvestItem(creature.getType());
        if (item == null) {
            player.sendMessage(plugin.message("aquarium-no-fish"));
            event.setCancelled(true);
            return;
        }

        creature.remove();
        event.setCancelled(true);
        var leftovers = player.getInventory().addItem(item);
        leftovers.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        player.sendMessage(plugin.message("aquarium-fish-taken"));
    }

    private Block findTargetWater(Player player, Block clicked) {
        if (clicked != null && clicked.getType() == Material.WATER) return clicked;

        RayTraceResult result = player.rayTraceBlocks(MAX_INTERACTION_DISTANCE, FluidCollisionMode.ALWAYS);
        if (result == null || result.getHitBlock() == null) return null;

        Block hit = result.getHitBlock();
        if (hit.getType() == Material.WATER) return hit;

        if (result.getHitBlockFace() != null) {
            Block adjacent = hit.getRelative(result.getHitBlockFace());
            if (adjacent.getType() == Material.WATER) return adjacent;
        }
        return null;
    }

    private boolean isInsideAquarium(Location location, Location center, int radius, int vertical) {
        return Math.abs(location.getX() - center.getX()) <= radius
                && Math.abs(location.getY() - center.getY()) <= vertical
                && Math.abs(location.getZ() - center.getZ()) <= radius;
    }

    private FarmObjectKey findAquarium(Location location) {
        int radius = plugin.settings().aquariumMaxRadius();
        int vertical = plugin.settings().aquariumVerticalRange();

        return plugin.farmObjectManager().objects().stream()
                .filter(key -> key.worldId().equals(location.getWorld().getUID()))
                .filter(key -> {
                    Location keyLocation = key.location(plugin.getServer());
                    return plugin.farmObjectManager().typeOf(keyLocation.getBlock()) == FarmObjectType.AQUARIUM_SHELF;
                })
                .filter(key -> {
                    Location keyLocation = key.location(plugin.getServer());
                    return Math.abs(keyLocation.getX() - location.getX()) <= radius
                            && Math.abs(keyLocation.getY() - location.getY()) <= vertical
                            && Math.abs(keyLocation.getZ() - location.getZ()) <= radius;
                })
                .min(Comparator.comparingDouble(key -> key.location(plugin.getServer()).distanceSquared(location)))
                .orElse(null);
    }

    private boolean isFreshFish(ItemStack item) {
        if (item == null) return false;
        return item.getType() == Material.COD
                || item.getType() == Material.SALMON
                || item.getType() == Material.TROPICAL_FISH
                || item.getType() == Material.PUFFERFISH;
    }

    private boolean isSupportedAquariumCreature(Entity entity) {
        return entity instanceof Fish || toHarvestItem(entity.getType()) != null;
    }

    private ItemStack toHarvestItem(EntityType type) {
        return switch (type) {
            case COD -> new ItemStack(Material.COD);
            case SALMON -> new ItemStack(Material.SALMON);
            case TROPICAL_FISH -> new ItemStack(Material.TROPICAL_FISH);
            case PUFFERFISH -> new ItemStack(Material.PUFFERFISH);
            case AXOLOTL -> new ItemStack(Material.AXOLOTL_SPAWN_EGG);
            case SQUID -> new ItemStack(Material.SQUID_SPAWN_EGG);
            case GLOW_SQUID -> new ItemStack(Material.GLOW_SQUID_SPAWN_EGG);
            case TURTLE -> new ItemStack(Material.TURTLE_SPAWN_EGG);
            case DOLPHIN -> new ItemStack(Material.DOLPHIN_SPAWN_EGG);
            case FROG -> new ItemStack(Material.FROG_SPAWN_EGG);
            default -> null;
        };
    }
}
