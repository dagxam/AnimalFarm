package ru.dagxam.animalfarm;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.type.Composter;
import org.bukkit.configuration.ConfigurationSection;
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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class AnimalFarmPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, Long> milkingCooldown = new HashMap<>();
    private NamespacedKey storedFoodKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        storedFoodKey = new NamespacedKey(this, "stored_food");
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

    public String message(String path) {
        return ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages." + path, ""));
    }

    @EventHandler(ignoreCancelled = true)
    public void onMilk(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Entity entity = event.getRightClicked();
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != Material.BUCKET) return;
        if (!getConfig().getBoolean("milking.enabled", true)) return;

        String animal = getAnimalKey(entity);
        if (animal == null || !getConfig().getBoolean("milking.animals." + animal, true)) return;

        long cooldownMillis = Math.max(0L, getConfig().getLong("milking.cooldown-seconds", 30L)) * 1000L;
        long now = System.currentTimeMillis();
        long last = milkingCooldown.getOrDefault(entity.getUniqueId(), 0L);
        if (now - last < cooldownMillis) {
            event.setCancelled(true);
            player.sendMessage(message("prefix") + message("milk-cooldown"));
            return;
        }

        event.setCancelled(true);
        milkingCooldown.put(entity.getUniqueId(), now);
        replaceOneMainHandItem(player, new ItemStack(Material.MILK_BUCKET));

        String messageKey = switch (animal) {
            case "cow" -> "milk-cow";
            case "sheep" -> "milk-sheep";
            case "goat" -> "milk-goat";
            default -> "milk-cow";
        };
        player.sendMessage(message("prefix") + message(messageKey));
    }

    @EventHandler(ignoreCancelled = true)
    public void onComposterInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.COMPOSTER) return;
        if (!getConfig().getBoolean("automatic-feeder.enabled", true)) return;

        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        Material food = resolveAcceptedFood(hand.getType());
        if (food == null) return;

        event.setCancelled(true);
        Composter composter = (Composter) event.getClickedBlock().getBlockData();
        int level = composter.getLevel();
        if (level >= 7) return;

        storeFood(event.getClickedBlock().getState(), food);
        if (hand.getAmount() > 1) hand.setAmount(hand.getAmount() - 1);
        else player.getInventory().setItemInMainHand(null);

        composter.setLevel(level + 1);
        event.getClickedBlock().setBlockData(composter);
    }

    private Material resolveAcceptedFood(Material material) {
        if (material == Material.WHEAT) return Material.WHEAT;
        if (material == Material.WHEAT_SEEDS || material == Material.BEETROOT_SEEDS || material == Material.PUMPKIN_SEEDS || material == Material.MELON_SEEDS) {
            return material;
        }
        return null;
    }

    private void storeFood(BlockState state, Material food) {
        String existing = state.getPersistentDataContainer().get(storedFoodKey, PersistentDataType.STRING);
        if (existing == null) state.getPersistentDataContainer().set(storedFoodKey, PersistentDataType.STRING, food.name());
        state.update(true, false);
    }

    private Material readStoredFood(BlockState state) {
        String value = state.getPersistentDataContainer().get(storedFoodKey, PersistentDataType.STRING);
        if (value == null) return null;
        return Material.matchMaterial(value);
    }

    private void clearStoredFood(BlockState state) {
        state.getPersistentDataContainer().remove(storedFoodKey);
        state.update(true, false);
    }

    private void replaceOneMainHandItem(Player player, ItemStack replacement) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(replacement);
            return;
        }
        hand.setAmount(hand.getAmount() - 1);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(replacement);
        leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
    }

    private String getAnimalKey(Entity entity) {
        if (entity instanceof Cow) return "cow";
        if (entity instanceof Sheep) return "sheep";
        if (entity instanceof Goat) return "goat";
        return null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        String animal = getAnimalKey(event.getEntity());
        if (animal == null) return;

        ConfigurationSection section = getConfig().getConfigurationSection("drops." + animal);
        if (section == null) return;

        addConfiguredDrop(event, section, "meat", meatMaterial(animal));
        addConfiguredDrop(event, section, "leather", Material.LEATHER);
        addConfiguredDrop(event, section, "bone", Material.BONE);
        if (animal.equals("sheep")) addConfiguredDrop(event, section, "wool", Material.WHITE_WOOL);
    }

    private Material meatMaterial(String animal) {
        return switch (animal) {
            case "cow" -> Material.BEEF;
            case "sheep" -> Material.MUTTON;
            case "goat" -> Material.MUTTON;
            default -> Material.BEEF;
        };
    }

    private void addConfiguredDrop(EntityDeathEvent event, ConfigurationSection section, String key, Material material) {
        int min = Math.max(0, section.getInt(key + ".min", 0));
        int max = Math.max(min, section.getInt(key + ".max", min));
        int amount = ThreadLocalRandom.current().nextInt(min, max + 1);
        if (amount > 0) event.getDrops().add(new ItemStack(material, amount));
    }

    private void startFeederTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!getConfig().getBoolean("automatic-feeder.enabled", true)) return;
                feedAllLoadedFeeders();
            }
        }.runTaskTimer(this, 20L, Math.max(20L, getConfig().getLong("automatic-feeder.interval-seconds", 5L) * 20L));
    }

    private void feedAllLoadedFeeders() {
        for (var world : getServer().getWorlds()) {
            for (var chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities()) {
                    if (state.getType() != Material.COMPOSTER) continue;
                    feedFromComposter(state);
                }
            }
        }
    }

    private void feedFromComposter(BlockState state) {
        Material food = readStoredFood(state);
        if (food == null) return;

        int radius = Math.max(1, getConfig().getInt("automatic-feeder.radius", 8));
        int maxAnimals = Math.max(1, getConfig().getInt("automatic-feeder.max-animals", 16));
        org.bukkit.Location location = state.getLocation().add(0.5, 0.5, 0.5);

        List<Animals> animals = location.getWorld().getNearbyEntities(location, radius, radius, radius).stream()
                .filter(entity -> entity instanceof Animals)
                .map(entity -> (Animals) entity)
                .filter(this::eligibleForAutomaticFeeding)
                .sorted(Comparator.comparingDouble(a -> a.getLocation().distanceSquared(location)))
                .limit(maxAnimals)
                .toList();

        for (Animals animal : animals) {
            if (!canFeed(animal, food)) continue;
            if (animal.isAdult()) animal.setBreed(true);
            else animal.setAge(0);
            clearStoredFood(state);
            return;
        }
    }

    private boolean eligibleForAutomaticFeeding(Animals animal) {
        return !animal.isAdult() || animal.canBreed();
    }

    private boolean canFeed(Animals animal, Material food) {
        String key = null;
        if (animal instanceof Cow) key = "cow";
        else if (animal instanceof Sheep) key = "sheep";
        else if (animal instanceof Goat) key = "goat";
        else if (animal instanceof Chicken) key = "chicken";
        if (key == null || !getConfig().getBoolean("feeding." + key + ".enabled", true)) return false;

        return getConfig().getStringList("feeding." + key + ".foods").stream()
                .anyMatch(name -> name.equalsIgnoreCase(food.name()));
    }
}
