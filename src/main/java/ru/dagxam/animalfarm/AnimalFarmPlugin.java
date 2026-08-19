package ru.dagxam.animalfarm;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Goat;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class AnimalFarmPlugin extends JavaPlugin implements Listener {

    private NamespacedKey feederKey;
    private final Map<UUID, Long> milkingCooldown = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        feederKey = new NamespacedKey(this, "animal_farm_feeder");
        getServer().getPluginManager().registerEvents(this, this);

        AnimalFarmCommand command = new AnimalFarmCommand(this);
        Objects.requireNonNull(getCommand("animalfarm")).setExecutor(command);
        Objects.requireNonNull(getCommand("animalfarm")).setTabCompleter(command);

        startFeederTask();
        getLogger().info("AnimalFarm включён.");
    }

    @Override
    public void onDisable() {
        getLogger().info("AnimalFarm выключен.");
    }

    public void reloadPluginConfig() {
        reloadConfig();
    }

    private String message(String path) {
        String value = getConfig().getString("messages." + path, "");
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMilk(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Entity entity = event.getRightClicked();
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        if (item.getType() != Material.BUCKET) return;
        if (!getConfig().getBoolean("milking.enabled", true)) return;

        String animalKey = getMilkAnimalKey(entity);
        if (animalKey == null || !getConfig().getBoolean("milking.animals." + animalKey, true)) return;

        long now = System.currentTimeMillis();
        long cooldown = getConfig().getLong("milking.cooldown-seconds", 30L) * 1000L;
        long last = milkingCooldown.getOrDefault(entity.getUniqueId(), 0L);
        if (now - last < cooldown) {
            event.setCancelled(true);
            player.sendMessage(message("prefix") + message("milk-cooldown"));
            return;
        }

        ItemStack milkBucket = new ItemStack(Material.MILK_BUCKET);
        replaceOneMainHandItem(player, milkBucket);
        milkingCooldown.put(entity.getUniqueId(), now);
        event.setCancelled(true);

        String path = switch (animalKey) {
            case "cow" -> "milk-cow";
            case "sheep" -> "milk-sheep";
            case "goat" -> "milk-goat";
            default -> "milk-cow";
        };
        player.sendMessage(message("prefix") + message(path));
    }

    private void replaceOneMainHandItem(Player player, ItemStack replacement) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        int amount = hand.getAmount();
        if (amount <= 1) {
            player.getInventory().setItemInMainHand(replacement);
        } else {
            hand.setAmount(amount - 1);
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(replacement);
            leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        }
    }

    private String getMilkAnimalKey(Entity entity) {
        if (entity instanceof Cow) return "cow";
        if (entity instanceof Sheep) return "sheep";
        if (entity instanceof Goat) return "goat";
        return null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        String animalKey = null;
        if (event.getEntity() instanceof Cow) animalKey = "cow";
        else if (event.getEntity() instanceof Sheep) animalKey = "sheep";
        else if (event.getEntity() instanceof Goat) animalKey = "goat";
        else return;

        // Контролируем дополнительный дроп. Ванильный дроп сохраняется.
        ConfigurationSection section = getConfig().getConfigurationSection("drops." + animalKey);
        if (section == null) return;

        addConfiguredDrop(event, section, "meat", meatMaterial(animalKey));
        addConfiguredDrop(event, section, "leather", Material.LEATHER);
        addConfiguredDrop(event, section, "bone", Material.BONE);
        if (animalKey.equals("sheep")) {
            addConfiguredDrop(event, section, "wool", ((Sheep) event.getEntity()).getColor().getWoolData());
        }
    }

    private Material meatMaterial(String animalKey) {
        return switch (animalKey) {
            case "cow" -> Material.BEEF;
            case "sheep" -> Material.MUTTON;
            case "goat" -> Material.COOKED_MUTTON; // заменяется ниже на сырое мясо при добавлении
            default -> Material.BEEF;
        };
    }

    private void addConfiguredDrop(EntityDeathEvent event, ConfigurationSection section, String name, Material material) {
        int min = section.getInt(name + ".min", 0);
        int max = section.getInt(name + ".max", min);
        if (max < min) max = min;
        int amount = ThreadLocalRandom.current().nextInt(min, max + 1);
        if (amount > 0) event.getDrops().add(new ItemStack(material, amount));
    }

    private void addConfiguredDrop(EntityDeathEvent event, ConfigurationSection section, String name, org.bukkit.material.MaterialData woolData) {
        int min = section.getInt(name + ".min", 0);
        int max = section.getInt(name + ".max", min);
        if (max < min) max = min;
        int amount = ThreadLocalRandom.current().nextInt(min, max + 1);
        if (amount > 0) event.getDrops().add(new ItemStack(Material.WHITE_WOOL, amount));
    }

    private void startFeederTask() {
        long intervalTicks = Math.max(20L, getConfig().getLong("automatic-feeder.interval-seconds", 5L) * 20L);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!getConfig().getBoolean("automatic-feeder.enabled", true)) return;
                feedAllLoadedFeeders();
            }
        }.runTaskTimer(this, intervalTicks, intervalTicks);
    }

    private void feedAllLoadedFeeders() {
        for (var world : getServer().getWorlds()) {
            world.getLoadedChunks();
            for (var chunk : world.getLoadedChunks()) {
                for (var state : chunk.getTileEntities()) {
                    if (!(state instanceof org.bukkit.block.Container)) {
                        if (!(state instanceof org.bukkit.block.BlockState)) continue;
                    }
                    if (state.getType() != Material.COMPOSTER) continue;
                    feedFromComposter(state.getLocation());
                }
            }
        }
    }

    private void feedFromComposter(org.bukkit.Location location) {
        int radius = Math.max(1, getConfig().getInt("automatic-feeder.radius", 8));
        int maxAnimals = Math.max(1, getConfig().getInt("automatic-feeder.max-animals", 16));

        List<Animals> animals = location.getWorld().getNearbyEntities(location, radius, radius, radius).stream()
                .filter(entity -> entity instanceof Animals)
                .map(entity -> (Animals) entity)
                .filter(a -> !a.isAdult() || a.canBreed())
                .limit(maxAnimals)
                .toList();

        if (animals.isEmpty()) return;

        Material food = findFoodInNearbySource(location, animals);
        if (food == null) return;

        for (Animals animal : animals) {
            if (!canFeed(animal, food)) continue;
            if (!removeOneFood(location, food)) return;
            if (animal.isAdult()) animal.setBreed(true);
            else animal.setAge(0);
        }
    }

    private Material findFoodInNearbySource(org.bukkit.Location location, List<Animals> animals) {
        for (Material material : List.of(Material.WHEAT, Material.WHEAT_SEEDS, Material.BEETROOT_SEEDS, Material.PUMPKIN_SEEDS, Material.MELON_SEEDS)) {
            for (Animals animal : animals) {
                if (canFeed(animal, material) && hasFood(location, material)) return material;
            }
        }
        return null;
    }

    private boolean canFeed(Animals animal, Material material) {
        String key = null;
        if (animal instanceof Cow) key = "cow";
        else if (animal instanceof Sheep) key = "sheep";
        else if (animal instanceof Goat) key = "goat";
        else if (animal instanceof Chicken) key = "chicken";
        if (key == null || !getConfig().getBoolean("feeding." + key + ".enabled", true)) return false;

        List<String> foods = getConfig().getStringList("feeding." + key + ".foods");
        return foods.stream().anyMatch(value -> value.equalsIgnoreCase(material.name()));
    }

    private boolean hasFood(org.bukkit.Location location, Material material) {
        org.bukkit.block.Block block = location.getBlock();
        if (!(block.getState() instanceof org.bukkit.block.Container container)) return false;
        return Arrays.stream(container.getInventory().getContents()).filter(Objects::nonNull).anyMatch(i -> i.getType() == material && i.getAmount() > 0);
    }

    private boolean removeOneFood(org.bukkit.Location location, Material material) {
        org.bukkit.block.Block block = location.getBlock();
        if (!(block.getState() instanceof org.bukkit.block.Container container)) return false;
        for (int slot = 0; slot < container.getInventory().getSize(); slot++) {
            ItemStack stack = container.getInventory().getItem(slot);
            if (stack != null && stack.getType() == material && stack.getAmount() > 0) {
                stack.setAmount(stack.getAmount() - 1);
                container.getInventory().setItem(slot, stack.getAmount() == 0 ? null : stack);
                return true;
            }
        }
        return false;
    }
}
