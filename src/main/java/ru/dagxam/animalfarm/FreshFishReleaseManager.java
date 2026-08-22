package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Выпускает свежую ванильную рыбу из руки обратно в воду правой кнопкой. */
public final class FreshFishReleaseManager implements Listener {
    private final AnimalFarmPlugin plugin;

    public FreshFishReleaseManager(AnimalFarmPlugin plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true)
    public void onRelease(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null || item.getAmount() <= 0) return;
        EntityType type = fishType(item.getType());
        if (type == null) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        Block water = clicked.getType() == Material.WATER ? clicked : clicked.getRelative(event.getBlockFace());
        if (water.getType() != Material.WATER) return;

        Location spawn = water.getLocation().add(0.5, 0.2, 0.5);
        water.getWorld().spawnEntity(spawn, type);
        item.setAmount(item.getAmount() - 1);
        event.getPlayer().getInventory().setItemInMainHand(item.getAmount() <= 0 ? null : item);
        event.setCancelled(true);
    }

    private EntityType fishType(Material material) {
        return switch (material) {
            case COD -> EntityType.COD;
            case SALMON -> EntityType.SALMON;
            case TROPICAL_FISH -> EntityType.TROPICAL_FISH;
            case PUFFERFISH -> EntityType.PUFFERFISH;
            default -> null;
        };
    }
}
