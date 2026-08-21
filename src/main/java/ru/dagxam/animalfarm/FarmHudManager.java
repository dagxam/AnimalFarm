package ru.dagxam.animalfarm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fish;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Показывает состояние кормушки или аквариума при наведении на объект.
 * Обновление ограничено, чтобы не выполнять тяжёлый поиск сущностей на каждый пиксель движения игрока.
 */
public final class FarmHudManager implements Listener {
    private static final long UPDATE_INTERVAL_MS = 250L;

    private final AnimalFarmPlugin plugin;
    private final Map<UUID, Long> nextUpdate = new HashMap<>();
    private final Map<UUID, String> lastTarget = new HashMap<>();

    public FarmHudManager(AnimalFarmPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // Не реагируем на поворот головы: только на смену блока, в котором находится игрок.
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        updateStatus(event.getPlayer(), false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        sendStatus(event.getPlayer(), event.getClickedBlock());
    }

    private void updateStatus(Player player, boolean force) {
        if (!plugin.getConfig().getBoolean("hud.enabled", true)) return;

        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        if (!force && now < nextUpdate.getOrDefault(uuid, 0L)) return;
        nextUpdate.put(uuid, now + UPDATE_INTERVAL_MS);

        Block target = player.getTargetBlockExact(Math.max(1, plugin.getConfig().getInt("hud.range", 6)));
        if (target == null) return;

        String targetId = target.getWorld().getUID() + ":" + target.getX() + ":" + target.getY() + ":" + target.getZ();
        if (!force && targetId.equals(lastTarget.get(uuid))) return;
        lastTarget.put(uuid, targetId);
        sendStatus(player, target);
    }

    private void sendStatus(Player player, Block target) {
        if (!plugin.getConfig().getBoolean("hud.enabled", true) || target == null) return;

        FarmObjectType type = plugin.farmObjectManager().typeOf(target);
        if (type == null || !(target.getState() instanceof Barrel barrel)) return;

        Location location = target.getLocation();
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
        for (Entity entity : location.getWorld().getNearbyEntities(location,
                plugin.settings().penMaxRadius() + 1,
                plugin.settings().penVerticalRange(),
                plugin.settings().penMaxRadius() + 1)) {
            if (entity instanceof Animals animal && supportedAnimal(animal)) total++;
        }
        return total;
    }

    private boolean supportedAnimal(Animals animal) {
        return switch (animal.getType()) {
            case COW, SHEEP, GOAT, CHICKEN, HORSE, RABBIT -> true;
            default -> false;
        };
    }

    private int countFish(Location location) {
        int total = 0;
        for (Entity entity : location.getWorld().getNearbyEntities(location,
                plugin.settings().aquariumMaxRadius(),
                plugin.settings().aquariumVerticalRange(),
                plugin.settings().aquariumMaxRadius())) {
            if (entity instanceof Fish fish
                    && fish.getLocation().getBlock().getType() == Material.WATER) {
                total++;
            }
        }
        return total;
    }

    private int countAnimalFood(Inventory inventory) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item == null) continue;
            Material material = item.getType();
            if (material == Material.WHEAT || material == Material.HAY_BLOCK || material == Material.APPLE
                    || material == Material.MELON_SLICE || material == Material.PUMPKIN || material == Material.MELON
                    || material == Material.CARROT || material == Material.GOLDEN_CARROT || material == Material.GOLDEN_APPLE
                    || material.name().endsWith("_SEEDS") || material == Material.PITCHER_POD) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private int countFishFood(Inventory inventory) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item == null) continue;
            Material material = item.getType();
            if (material.name().endsWith("_SEEDS") || material == Material.PITCHER_POD
                    || material == Material.SEAGRASS || material == Material.KELP || material == Material.SEA_PICKLE) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private int count(Inventory inventory, Material material) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() == material) total += item.getAmount();
        }
        return total;
    }
}
