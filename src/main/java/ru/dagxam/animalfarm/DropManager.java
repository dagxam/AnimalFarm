package ru.dagxam.animalfarm;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

/** Handles all configured animal death drops in one place. */
public final class DropManager implements Listener {

    private final AnimalFarmPlugin plugin;
    private final CustomDropService customDrops;

    public DropManager(AnimalFarmPlugin plugin) {
        this.plugin = plugin;
        this.customDrops = new CustomDropService(plugin);
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
            case "goat" -> customDrops.create(Material.MUTTON, "&fКозлятина", "goat_meat");
            case "horse" -> customDrops.create(Material.BEEF, "&fКонина", "horse_meat");
            case "rabbit" -> customDrops.create(Material.RABBIT, "&fКрольчатина", "rabbit_meat");
            case "chicken" -> new ItemStack(Material.CHICKEN);
            default -> new ItemStack(Material.BEEF);
        };
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
