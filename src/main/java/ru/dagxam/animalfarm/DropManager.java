package ru.dagxam.animalfarm;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.concurrent.ThreadLocalRandom;

/** Handles all configured animal death drops in one place. */
public final class DropManager implements Listener {

    private final AnimalFarmPlugin plugin;

    public DropManager(AnimalFarmPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        String animal = animalKey(event.getEntity());
        if (animal == null) return;

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("drops." + animal);
        if (section == null) return;

        event.getDrops().clear();
        addConfiguredDrop(event, section, "meat", meat(animal));
        if (animal.equals("rabbit")) {
            addConfiguredDrop(event, section, "rabbit-hide", new ItemStack(Material.RABBIT_HIDE));
        } else if (!animal.equals("chicken")) {
            addConfiguredDrop(event, section, "leather", new ItemStack(Material.LEATHER));
        }
        addConfiguredDrop(event, section, "bone", new ItemStack(Material.BONE));
        if (animal.equals("sheep")) addConfiguredDrop(event, section, "wool", new ItemStack(Material.WHITE_WOOL));
        if (animal.equals("chicken")) addConfiguredDrop(event, section, "feather", new ItemStack(Material.FEATHER));
    }

    private String animalKey(Entity entity) {
        return switch (entity.getType()) {
            case COW -> "cow";
            case SHEEP -> "sheep";
            case GOAT -> "goat";
            case HORSE -> "horse";
            case RABBIT -> "rabbit";
            case CHICKEN -> "chicken";
            default -> null;
        };
    }

    private ItemStack meat(String animal) {
        return switch (animal) {
            case "cow" -> new ItemStack(Material.BEEF);
            case "sheep" -> new ItemStack(Material.MUTTON);
            case "goat" -> named(Material.MUTTON, "&fКозлятина");
            case "horse" -> named(Material.BEEF, "&fКонина");
            case "rabbit" -> named(Material.RABBIT, "&fКрольчатина");
            case "chicken" -> new ItemStack(Material.CHICKEN);
            default -> new ItemStack(Material.BEEF);
        };
    }

    private ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void addConfiguredDrop(EntityDeathEvent event, ConfigurationSection section, String key, ItemStack prototype) {
        int min = section.getInt(key + ".min", 0);
        int max = section.getInt(key + ".max", min);
        int amount = random(Math.max(0, min), Math.max(Math.max(0, min), max));
        if (amount <= 0) return;

        ItemStack drop = prototype.clone();
        drop.setAmount(amount);
        event.getDrops().add(drop);
    }

    private int random(int min, int max) {
        return min >= max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
    }
}
