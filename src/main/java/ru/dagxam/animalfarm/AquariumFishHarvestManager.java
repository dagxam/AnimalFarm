package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Забирает существо только при прямом ПКМ по нему внутри зарегистрированного аквариума.
 * Обычные рыбы возвращаются предметами рыбы, остальные поддерживаемые существа — яйцами призыва.
 */
public final class AquariumFishHarvestManager implements Listener {
    private final AnimalFarmPlugin plugin;
    private final FarmAccessService accessService;

    public AquariumFishHarvestManager(AnimalFarmPlugin plugin, FarmAccessService accessService) {
        this.plugin = plugin;
        this.accessService = accessService;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        Entity creature = event.getRightClicked();
        ItemStack item = toHarvestItem(creature.getType());
        if (item == null) return;
        if (isFreshFish(player.getInventory().getItemInMainHand())) return;

        FarmObjectKey aquariumKey = findAquarium(creature.getLocation());
        if (aquariumKey == null) return;

        Location aquariumLocation = aquariumKey.location(plugin.getServer());
        if (aquariumLocation.getWorld() == null) return;
        Block aquariumBlock = aquariumLocation.getBlock();

        if (plugin.settings().aquariumOwnerOnlyHarvest()
                && !accessService.canHarvest(player, aquariumBlock)) {
            player.sendMessage(plugin.message("not-owner"));
            event.setCancelled(true);
            return;
        }

        creature.remove();
        event.setCancelled(true);
        var leftovers = player.getInventory().addItem(item);
        leftovers.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        player.sendMessage(plugin.message("aquarium-fish-taken"));
    }

    private FarmObjectKey findAquarium(Location location) {
        if (location.getWorld() == null) return null;

        int radius = plugin.settings().aquariumMaxRadius();
        int vertical = plugin.settings().aquariumVerticalRange();

        return plugin.farmObjectManager().objects().stream()
                .filter(key -> key.worldId().equals(location.getWorld().getUID()))
                .filter(key -> {
                    Location keyLocation = key.location(plugin.getServer());
                    return keyLocation.getWorld() != null
                            && plugin.farmObjectManager().typeOf(keyLocation.getBlock()) == FarmObjectType.AQUARIUM_SHELF;
                })
                .filter(key -> {
                    Location keyLocation = key.location(plugin.getServer());
                    return Math.abs(keyLocation.getX() - location.getX()) <= radius
                            && Math.abs(keyLocation.getY() - location.getY()) <= vertical
                            && Math.abs(keyLocation.getZ() - location.getZ()) <= radius;
                })
                .min(java.util.Comparator.comparingDouble(key -> key.location(plugin.getServer()).distanceSquared(location)))
                .orElse(null);
    }

    private boolean isFreshFish(ItemStack item) {
        if (item == null) return false;
        return item.getType() == Material.COD
                || item.getType() == Material.SALMON
                || item.getType() == Material.TROPICAL_FISH
                || item.getType() == Material.PUFFERFISH;
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