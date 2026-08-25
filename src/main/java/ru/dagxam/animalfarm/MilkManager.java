package ru.dagxam.animalfarm;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.concurrent.ThreadLocalRandom;

/** Ручное доение взрослых самок и кормление малышей молоком. */
public final class MilkManager implements Listener {
    private final AnimalFarmPlugin plugin;
    private FarmSettings settings;
    private final NamespacedKey milkDayKey;
    private final NamespacedKey milkFeedDayKey;
    private final NamespacedKey milkFeedCountKey;
    private final NamespacedKey milkFeedRequiredKey;

    public MilkManager(AnimalFarmPlugin plugin, FarmSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        milkDayKey = new NamespacedKey(plugin, "milk_day");
        milkFeedDayKey = new NamespacedKey(plugin, "milk_feed_day");
        milkFeedCountKey = new NamespacedKey(plugin, "milk_feed_count");
        milkFeedRequiredKey = new NamespacedKey(plugin, "milk_feed_required");
    }

    public void setSettings(FarmSettings settings) {
        this.settings = settings;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        // Обрабатываем только основную руку, чтобы событие второй руки не срабатывало повторно.
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;

        Entity target = event.getRightClicked();
        if (!(target instanceof Animals animal) || !isMilkAnimal(animal)) return;

        ItemStack hand = event.getPlayer().getInventory().getItemInMainHand();

        // Детёныша можно кормить молоком обычной правой кнопкой без Shift.
        if (!animal.isAdult() && hand.getType() == Material.MILK_BUCKET) {
            event.setCancelled(true);
            feedBaby(event, animal);
            return;
        }

        // Доение взрослой самки — только Shift + правая кнопка + пустое ведро.
        if (!animal.isAdult() || hand.getType() != Material.BUCKET || !event.getPlayer().isSneaking()) return;

        // Доить можно только самок: коров, овец, коз и лошадей.
        if (plugin.getConfig().getBoolean("animal-genders.milk.females-only", true)
                && !plugin.genderManager().canGiveMilk(animal)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.message("prefix") + plugin.message("milk-male"));
            return;
        }

        event.setCancelled(true);
        long day = animal.getWorld().getFullTime() / 24000L;
        long last = animal.getPersistentDataContainer().getOrDefault(milkDayKey, PersistentDataType.LONG, -1L);
        if (last >= day) {
            event.getPlayer().sendMessage(plugin.message("prefix") + plugin.message("milk-cooldown"));
            return;
        }

        animal.getPersistentDataContainer().set(milkDayKey, PersistentDataType.LONG, day);
        replaceOne(event.getPlayer(), new ItemStack(Material.MILK_BUCKET));
        event.getPlayer().sendMessage(plugin.message("prefix") + milkMessage(animal));
    }

    private void feedBaby(PlayerInteractEntityEvent event, Animals baby) {
        long day = baby.getWorld().getFullTime() / 24000L;
        long last = baby.getPersistentDataContainer().getOrDefault(milkFeedDayKey, PersistentDataType.LONG, -1L);
        if (last >= day) {
            event.getPlayer().sendMessage(plugin.message("prefix") + plugin.message("milk-baby-once"));
            return;
        }

        int required = baby.getPersistentDataContainer().getOrDefault(milkFeedRequiredKey, PersistentDataType.INTEGER, 0);
        if (required <= 0) {
            required = ThreadLocalRandom.current().nextInt(settings.milkFeedingsMin(), settings.milkFeedingsMax() + 1);
            baby.getPersistentDataContainer().set(milkFeedRequiredKey, PersistentDataType.INTEGER, required);
        }

        consumeOne(event.getPlayer());
        int count = baby.getPersistentDataContainer().getOrDefault(milkFeedCountKey, PersistentDataType.INTEGER, 0) + 1;
        baby.getPersistentDataContainer().set(milkFeedCountKey, PersistentDataType.INTEGER, count);
        baby.getPersistentDataContainer().set(milkFeedDayKey, PersistentDataType.LONG, day);

        if (count >= required) {
            baby.setAdult();
            baby.getPersistentDataContainer().remove(milkFeedCountKey);
            baby.getPersistentDataContainer().remove(milkFeedRequiredKey);
            baby.getPersistentDataContainer().remove(milkFeedDayKey);
            event.getPlayer().sendMessage(plugin.message("prefix") + plugin.message("milk-baby-grown"));
        } else {
            event.getPlayer().sendMessage(plugin.message("prefix") + plugin.message("milk-baby"));
        }
    }

    private boolean isMilkAnimal(Animals animal) {
        return switch (animal.getType()) {
            case COW, SHEEP, GOAT, HORSE -> true;
            default -> false;
        };
    }

    private String milkMessage(Animals animal) {
        return switch (animal.getType()) {
            case COW -> plugin.message("milk-cow");
            case SHEEP -> plugin.message("milk-sheep");
            case GOAT -> plugin.message("milk-goat");
            case HORSE -> plugin.message("milk-horse");
            default -> plugin.message("milk-cow");
        };
    }

    private void consumeOne(org.bukkit.entity.Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.BUCKET));
        } else {
            hand.setAmount(hand.getAmount() - 1);
            player.getInventory().addItem(new ItemStack(Material.BUCKET));
        }
    }

    private void replaceOne(org.bukkit.entity.Player player, ItemStack result) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(result);
        } else {
            hand.setAmount(hand.getAmount() - 1);
            var leftovers = player.getInventory().addItem(result);
            for (ItemStack left : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            }
        }
    }
}
