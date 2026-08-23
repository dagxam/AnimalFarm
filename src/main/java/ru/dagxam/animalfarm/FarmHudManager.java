package ru.dagxam.animalfarm;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Расширенный HUD фермы: количество, вместимость, ресурсы и состояние. */
public final class FarmHudManager implements Listener {
    private static final long UPDATE_INTERVAL_MS = 250L;
    private final AnimalFarmPlugin plugin;
    private final Map<UUID, Long> next = new HashMap<>();
    private final Map<UUID, String> last = new HashMap<>();

    public FarmHudManager(AnimalFarmPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(org.bukkit.event.player.PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;
        update(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            send(event.getPlayer(), event.getClickedBlock());
        }
    }

    private void update(Player player) {
        if (!plugin.getConfig().getBoolean("hud.enabled", true)) return;
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        if (now < next.getOrDefault(id, 0L)) return;
        next.put(id, now + UPDATE_INTERVAL_MS);

        Block block = player.getTargetBlockExact(plugin.settings().hudRange());
        if (block == null) return;
        String key = block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
        if (key.equals(last.get(id))) return;
        last.put(id, key);
        send(player, block);
    }

    private void send(Player player, Block block) {
        FarmObjectType type = plugin.farmObjectManager().typeOf(block);
        if (type == null || !(block.getState() instanceof Barrel barrel)) return;

        Location location = block.getLocation();
        Inventory inventory = barrel.getInventory();
        String state = barrel.getPersistentDataContainer().getOrDefault(
                new NamespacedKey(plugin, "farm_state"), PersistentDataType.STRING, "HEALTHY");

        String message;
        if (type == FarmObjectType.LAND_FEEDER) {
            int animals = countAnimals(location);
            int food = countAnimalFood(inventory);
            int water = count(inventory, Material.WATER_BUCKET);
            message = "§6Ферма §7| §fЖивотные: §e" + animals + "§7/§e" + plugin.settings().animalCapacity()
                    + " §7| §fКорм: §e" + food + " §7| §fВода: §b" + water + " §7| " + stateText(state);
        } else {
            int fish = countFish(location);
            int food = countFishFood(inventory);
            message = "§bАквакультура §7| §fРыбы: §e" + fish + "§7/§e" + plugin.settings().fishCapacity()
                    + " §7| §fКорм: §e" + food + " §7| " + stateText(state);
        }
        sendActionBar(player, message);
    }

    private void sendActionBar(Player player, String message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(message));
    }

    private String stateText(String state) {
        return switch (state) {
            case "FULL" -> "§cЗаполнено";
            case "NO_FOOD" -> "§cНет корма";
            case "NO_WATER" -> "§cНет воды";
            default -> "§aВ норме";
        };
    }

    private int countAnimals(Location location) {
        int count = 0;
        for (Entity entity : location.getWorld().getNearbyEntities(location,
                plugin.settings().penMaxRadius() + 1,
                plugin.settings().penVerticalRange(),
                plugin.settings().penMaxRadius() + 1)) {
            if (entity instanceof Animals animal && switch (animal.getType()) {
                case COW, SHEEP, GOAT, CHICKEN, HORSE, RABBIT -> true;
                default -> false;
            }) count++;
        }
        return count;
    }

    private int countFish(Location location) {
        int count = 0;
        for (Entity entity : location.getWorld().getNearbyEntities(location,
                plugin.settings().aquariumMaxRadius(),
                plugin.settings().aquariumVerticalRange(),
                plugin.settings().aquariumMaxRadius())) {
            if (entity instanceof Fish fish && fish.getLocation().getBlock().getType() == Material.WATER) count++;
        }
        return count;
    }

    private int countAnimalFood(Inventory inventory) {
        int count = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && isAnimalFood(item.getType())) count += item.getAmount();
        }
        return count;
    }

    private int countFishFood(Inventory inventory) {
        int count = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && isFishFood(item.getType())) count += item.getAmount();
        }
        return count;
    }

    private boolean isSeed(Material material) {
        return material.name().endsWith("_SEEDS") || material == Material.PITCHER_POD;
    }

    private boolean isFishFood(Material material) {
        return isSeed(material) || material == Material.SEAGRASS
                || material == Material.KELP || material == Material.SEA_PICKLE;
    }

    private boolean isAnimalFood(Material material) {
        return isFishFood(material) || material == Material.WHEAT || material == Material.HAY_BLOCK
                || material == Material.APPLE || material == Material.CARROT || material == Material.MELON_SLICE
                || material == Material.PUMPKIN || material == Material.MELON || material == Material.GOLDEN_APPLE
                || material == Material.GOLDEN_CARROT;
    }

    private int count(Inventory inventory, Material material) {
        int count = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() == material) count += item.getAmount();
        }
        return count;
    }
}
