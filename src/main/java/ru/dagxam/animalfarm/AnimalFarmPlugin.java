package ru.dagxam.animalfarm;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
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
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class AnimalFarmPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, Long> milkingCooldown = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
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

        if (animal.equals("sheep")) {
            addConfiguredDrop(event, section, "wool", Material.WHITE_WOOL);
        }
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
                    if (!(state.getBlockData() instanceof Composter)) continue;
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
                .filter(this::eligibleForAutomaticFeeding)
                .sorted(Comparator.comparingDouble(a -> a.getLocation().distanceSquared(location)))
                .limit(maxAnimals)
                .toList();

        if (animals.isEmpty()) return;
        if (!(location.getBlock().getState() instanceof BlockState state)) return;
        if (!(state instanceof org.bukkit.block.BlockState)) return;

        for (Animals animal : animals) {
            Material food = findFoodForAnimal(animal, location);
            if (food == null) continue;
            if (!removeOneFoodFromNearbyContainer(location, food)) continue;
            if (animal.isAdult()) {
                if (animal.canBreed()) animal.setBreed(true);
            } else {
                animal.setAge(0);
            }
        }
    }

    private boolean eligibleForAutomaticFeeding(Animals animal) {
        if (!animal.isAdult()) return true;
        return animal.canBreed();
    }

    private Material findFoodForAnimal(Animals animal, org.bukkit.Location location) {
        String key = null;
        if (animal instanceof Cow) key = "cow";
        else if (animal instanceof Sheep) key = "sheep";
        else if (animal instanceof Goat) key = "goat";
        else if (animal instanceof Chicken) key = "chicken";
        if (key == null || !getConfig().getBoolean("feeding." + key + ".enabled", true)) return null;

        for (String foodName : getConfig().getStringList("feeding." + key + ".foods")) {
            Material material = Material.matchMaterial(foodName);
            if (material != null && hasFoodInNearbyContainer(location, material)) return material;
        }
        return null;
    }

    private boolean hasFoodInNearbyContainer(org.bukkit.Location location, Material material) {
        // По умолчанию предметы ищутся в самом компостере через внутренний инвентарь только там,
        // где сервер предоставляет Container. Для ванильного компостера это специально не используется.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    var block = location.getWorld().getBlockAt(location.getBlockX() + dx, location.getBlockY() + dy, location.getBlockZ() + dz);
                    if (block.getState() instanceof Container container) {
                        if (contains(container, material)) return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean removeOneFoodFromNearbyContainer(org.bukkit.Location location, Material material) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    var block = location.getWorld().getBlockAt(location.getBlockX() + dx, location.getBlockY() + dy, location.getBlockZ() + dz);
                    if (!(block.getState() instanceof Container container)) continue;
                    for (int slot = 0; slot < container.getInventory().getSize(); slot++) {
                        ItemStack stack = container.getInventory().getItem(slot);
                        if (stack == null || stack.getType() != material) continue;
                        if (stack.getAmount() <= 1) container.getInventory().setItem(slot, null);
                        else stack.setAmount(stack.getAmount() - 1);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean contains(Container container, Material material) {
        for (ItemStack stack : container.getInventory().getContents()) {
            if (stack != null && stack.getType() == material && stack.getAmount() > 0) return true;
        }
        return false;
    }
}
