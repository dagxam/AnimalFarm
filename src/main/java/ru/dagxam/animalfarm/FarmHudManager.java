package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.block.Barrel;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fish;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;

import java.util.List;

/** Показывает состояние кормушки/аквариума при наведении взглядом на объект. */
public final class FarmHudManager implements Listener {
    private final AnimalFarmPlugin plugin;

    public FarmHudManager(AnimalFarmPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location target = player.getTargetBlockExact(Math.max(1, plugin.getConfig().getInt("hud.range", 6)));
        sendStatus(player, target == null ? null : target.getLocation());
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        sendStatus(event.getPlayer(), event.getClickedBlock().getLocation());
    }

    private void sendStatus(Player player, Location location) {
        if (!plugin.getConfig().getBoolean("hud.enabled", true) || location == null) return;
        FarmObjectType type = plugin.farmObjectManager().typeOf(location.getBlock());
        if (type == null || !(location.getBlock().getState() instanceof Barrel barrel)) return;

        Inventory inventory = barrel.getInventory();
        if (type == FarmObjectType.LAND_FEEDER) {
            int animals = countAnimals(location);
            int food = countAnimalFood(inventory);
            int water = count(inventory, Material.WATER_BUCKET);
            player.sendActionBar("§6Кормушка §7| §fЖивотных: §e" + animals
                    + " §7| §fКорма: §e" + food + " §7| §fВоды: §b" + water);
        } else {
            int fish = countFish(location);
            int food = countFishFood(inventory);
            player.sendActionBar("§bАквариум §7| §fРыб: §e" + fish
                    + " §7| §fКорма: §e" + food);
        }
    }

    private int countAnimals(Location location) {
        int total = 0;
        for (Entity entity : location.getWorld().getNearbyEntities(location, plugin.settings().penMaxRadius() + 1,
                plugin.settings().penVerticalRange(), plugin.settings().penMaxRadius() + 1)) {
            if (entity instanceof Animals animal && animal.getType() != null) total++;
        }
        return total;
    }

    private int countFish(Location location) {
        int total = 0;
        for (Entity entity : location.getWorld().getNearbyEntities(location, plugin.settings().aquariumMaxRadius(),
                plugin.settings().aquariumVerticalRange(), plugin.settings().aquariumMaxRadius())) {
            if (entity instanceof Fish && entity.getLocation().getBlock().getType() == Material.WATER) total++;
        }
        return total;
    }

    private int countAnimalFood(Inventory inventory) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item == null) continue;
            Material m = item.getType();
            if (m == Material.WHEAT || m == Material.HAY_BLOCK || m == Material.APPLE
                    || m == Material.MELON_SLICE || m == Material.PUMPKIN || m == Material.MELON
                    || m == Material.CARROT || m == Material.GOLDEN_CARROT || m == Material.GOLDEN_APPLE
                    || m.name().endsWith("_SEEDS") || m == Material.PITCHER_POD) total += item.getAmount();
        }
        return total;
    }

    private int countFishFood(Inventory inventory) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item == null) continue;
            Material m = item.getType();
            if (m.name().endsWith("_SEEDS") || m == Material.PITCHER_POD
                    || m == Material.SEAGRASS || m == Material.KELP || m == Material.SEA_PICKLE) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private int count(Inventory inventory, Material material) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) if (item != null && item.getType() == material) total += item.getAmount();
        return total;
    }
}
