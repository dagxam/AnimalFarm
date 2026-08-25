package ru.dagxam.animalfarm;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** HUD фермы с единым scheduler вместо обработки каждого PlayerMoveEvent. */
public final class FarmHudManager implements Listener {
    private final AnimalFarmPlugin plugin;
    private final Map<UUID, String> lastTarget = new HashMap<>();
    private final Map<UUID, String> lastMessage = new HashMap<>();
    private BukkitTask task;

    public FarmHudManager(AnimalFarmPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) {
            return;
        }
        long interval = Math.max(5L, plugin.getConfig().getLong("hud.update-interval-ticks", 10L));
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateAll, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        lastTarget.clear();
        lastMessage.clear();
    }

    private void updateAll() {
        if (!plugin.getConfig().getBoolean("hud.enabled", true)) {
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            update(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            send(event.getPlayer(), event.getClickedBlock(), true);
        }
    }

    private void update(Player player) {
        Block block = player.getTargetBlockExact(plugin.settings().hudRange());
        if (block == null) {
            lastTarget.remove(player.getUniqueId());
            lastMessage.remove(player.getUniqueId());
            return;
        }

        String key = blockKey(block);
        if (key.equals(lastTarget.get(player.getUniqueId()))) {
            return;
        }
        send(player, block, false);
    }

    private void send(Player player, Block block, boolean force) {
        FarmObjectType type = plugin.farmObjectManager().typeOf(block);
        if (type == null || !(block.getState() instanceof Barrel barrel)) {
            return;
        }

        Location location = block.getLocation();
        Inventory inventory = barrel.getInventory();
        String state = barrel.getPersistentDataContainer().getOrDefault(
                new NamespacedKey(plugin, "farm_state"), PersistentDataType.STRING, "HEALTHY");

        String message;
        if (type == FarmObjectType.LAND_FEEDER) {
            int animals = plugin.farmEntityService().getAnimals(location).size();
            int food = countAnimalFood(inventory);
            int water = count(inventory, Material.WATER_BUCKET);
            message = "§6Ферма §7| §fЖивотные: §e" + animals + "§7/§e" + plugin.settings().animalCapacity()
                    + " §7| §fКорм: §e" + food + " §7| §fВода: §b" + water + " §7| " + stateText(state);
        } else {
            int fish = plugin.farmEntityService().getFish(location).size();
            int food = countFishFood(inventory);
            message = "§bАквакультура §7| §fРыбы: §e" + fish + "§7/§e" + plugin.settings().fishCapacity()
                    + " §7| §fКорм: §e" + food + " §7| " + stateText(state);
        }

        UUID id = player.getUniqueId();
        String key = blockKey(block);
        if (!force && key.equals(lastTarget.get(id)) && message.equals(lastMessage.get(id))) {
            return;
        }

        lastTarget.put(id, key);
        lastMessage.put(id, message);
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(message));
    }

    private String blockKey(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private String stateText(String state) {
        return switch (state) {
            case "FULL" -> "§cЗаполнено";
            case "NO_FOOD" -> "§cНет корма";
            case "NO_WATER" -> "§cНет воды";
            default -> "§aВ норме";
        };
    }

    private int countAnimalFood(Inventory inventory) {
        int count = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && plugin.farmFoodService().isAnimalFood(item.getType())) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private int countFishFood(Inventory inventory) {
        int count = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && plugin.farmFoodService().isFishFood(item.getType())) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private int count(Inventory inventory, Material material) {
        int count = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        return count;
    }
}
