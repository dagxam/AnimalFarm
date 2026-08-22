package ru.dagxam.animalfarm;

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

import java.util.Comparator;

/** Allows the aquarium owner to take one live fish as a normal fresh-fish item. */
public final class AquariumFishHarvestManager implements Listener {
    private final AnimalFarmPlugin plugin;

    public AquariumFishHarvestManager(AnimalFarmPlugin plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getType() != Material.WATER) return;
        if (isFreshFish(event.getItem())) return;

        Player player = event.getPlayer();
        FarmObjectKey aquariumKey = findAquarium(clicked.getLocation());
        if (aquariumKey == null) return;

        Location aquarium = aquariumKey.location(plugin.getServer());
        Block aquariumBlock = aquarium.getBlock();
        if (!plugin.farmObjectManager().canAccess(player, aquariumBlock)) {
            player.sendMessage(plugin.message("not-owner"));
            event.setCancelled(true);
            return;
        }

        int radius = plugin.settings().aquariumMaxRadius();
        int vertical = plugin.settings().aquariumVerticalRange();
        Location target = clicked.getLocation().add(0.5, 0.5, 0.5);

        Entity fish = clicked.getWorld().getNearbyEntities(target, radius, vertical, radius, entity ->
                entity instanceof Fish && entity.getLocation().distanceSquared(aquarium.clone().add(0.5, 0.5, 0.5)) <= (double) radius * radius
        ).stream().min(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(target))).orElse(null);

        if (fish == null) {
            player.sendMessage(plugin.message("aquarium-no-fish"));
            return;
        }

        ItemStack item = toFishItem(fish.getType());
        if (item == null) return;
        fish.remove();
        event.setCancelled(true);
        var leftovers = player.getInventory().addItem(item);
        leftovers.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        player.sendMessage(plugin.message("aquarium-fish-taken"));
    }

    private FarmObjectKey findAquarium(Location location) {
        int radius = plugin.settings().aquariumMaxRadius();
        return plugin.farmObjectManager().objects().stream()
                .filter(key -> key.worldId().equals(location.getWorld().getUID()))
                .filter(key -> plugin.farmObjectManager().typeOf(key.location(plugin.getServer()).getBlock()) == FarmObjectType.AQUARIUM_SHELF)
                .filter(key -> key.location(plugin.getServer()).distanceSquared(location) <= (double) radius * radius)
                .min(Comparator.comparingDouble(key -> key.location(plugin.getServer()).distanceSquared(location)))
                .orElse(null);
    }

    private boolean isFreshFish(ItemStack item) {
        if (item == null) return false;
        return item.getType() == Material.COD || item.getType() == Material.SALMON
                || item.getType() == Material.TROPICAL_FISH || item.getType() == Material.PUFFERFISH;
    }

    private ItemStack toFishItem(EntityType type) {
        return switch (type) {
            case COD -> new ItemStack(Material.COD);
            case SALMON -> new ItemStack(Material.SALMON);
            case TROPICAL_FISH -> new ItemStack(Material.TROPICAL_FISH);
            case PUFFERFISH -> new ItemStack(Material.PUFFERFISH);
            default -> null;
        };
    }
}
