package ru.dagxam.animalfarm;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Openable;
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
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class AnimalFarmPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, Long> milkingCooldown = new HashMap<>();
    private NamespacedKey feederItemKey;
    private NamespacedKey feederBlockKey;
    private NamespacedKey waterKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        feederItemKey = new NamespacedKey(this, "feeder_item");
        feederBlockKey = new NamespacedKey(this, "feeder_block");
        waterKey = new NamespacedKey(this, "water_units");

        getServer().getPluginManager().registerEvents(this, this);
        AnimalFarmCommand command = new AnimalFarmCommand(this);
        Objects.requireNonNull(getCommand("animalfarm")).setExecutor(command);
        Objects.requireNonNull(getCommand("animalfarm")).setTabCompleter(command);

        registerFeederRecipe();
        startFeederTask();
        getLogger().info("AnimalFarm включён. Paper 26.2 / Java 26.");
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

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private void registerFeederRecipe() {
        NamespacedKey key = new NamespacedKey(this, "feeder");
        getServer().removeRecipe(key);
        ShapedRecipe recipe = new ShapedRecipe(key, createFeederItem());
        recipe.shape("BB", "BB");
        recipe.setIngredient('B', Material.BARREL);
        getServer().addRecipe(recipe);
    }

    public ItemStack createFeederItem() {
        ItemStack item = new ItemStack(Material.BARREL);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(color("&6Кормушка"));
        meta.setLore(List.of(
                color("&7Внешне — обычная бочка."),
                color("&7Автоматически кормит животных."),
                color("&7Для размножения нужны корм и вода."),
                color("&8AnimalFarm")
        ));
        meta.getPersistentDataContainer().set(feederItemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isFeederItem(ItemStack item) {
        if (item == null || item.getType() != Material.BARREL || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(feederItemKey, PersistentDataType.BYTE);
    }

    private boolean isFeederBlock(Block block) {
        if (block.getType() != Material.BARREL) return false;
        if (!(block.getState() instanceof Barrel barrel)) return false;
        return barrel.getPersistentDataContainer().has(feederBlockKey, PersistentDataType.BYTE);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFeederPlace(BlockPlaceEvent event) {
        if (!isFeederItem(event.getItemInHand())) return;
        if (!(event.getBlockPlaced().getState() instanceof Barrel barrel)) return;
        barrel.getPersistentDataContainer().set(feederBlockKey, PersistentDataType.BYTE, (byte) 1);
        barrel.getPersistentDataContainer().set(waterKey, PersistentDataType.INTEGER, 0);
        barrel.setCustomName("Кормушка");
        barrel.update(true, false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFeederBreak(BlockBreakEvent event) {
        if (!isFeederBlock(event.getBlock())) return;
        event.setDropItems(false);
        Barrel barrel = (Barrel) event.getBlock().getState();
        Location location = event.getBlock().getLocation();
        event.getBlock().getWorld().dropItemNaturally(location, createFeederItem());
        for (ItemStack item : barrel.getInventory().getContents()) {
            if (item != null && !item.getType().isAir()) {
                event.getBlock().getWorld().dropItemNaturally(location, item.clone());
            }
        }
        int water = getWater(barrel);
        if (water > 0) {
            event.getBlock().getWorld().dropItemNaturally(location, new ItemStack(Material.BUCKET, water));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFeederInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || !isFeederBlock(block)) return;

        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        Barrel barrel = (Barrel) block.getState();

        if (hand.getType() == Material.WATER_BUCKET) {
            event.setCancelled(true);
            int maxWater = Math.max(1, getConfig().getInt("feeder.max-water", 64));
            int current = getWater(barrel);
            if (current >= maxWater) {
                player.sendMessage(message("prefix") + message("water-full"));
                return;
            }
            setWater(barrel, current + 1);
            removeOneMainHandItem(player);
            giveItem(player, new ItemStack(Material.BUCKET));
            player.sendMessage(message("prefix") + message("water-added"));
            return;
        }

        // Обычный ПКМ открывает бочку. Shift + ПКМ показывает состояние загона.
        if (player.isSneaking() && hand.getType().isAir()) {
            event.setCancelled(true);
            PenStatus status = analyzePen(block.getLocation());
            player.sendMessage(message("prefix") + formatStatus(status, barrel));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFeederInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Barrel barrel)) return;
        if (!isFeederBlock(barrel.getBlock())) return;
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        if ((cursor != null && cursor.getType() == Material.WATER_BUCKET)
                || (current != null && current.getType() == Material.WATER_BUCKET)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFeederInventoryDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof Barrel barrel)) return;
        if (!isFeederBlock(barrel.getBlock())) return;
        ItemStack oldCursor = event.getOldCursor();
        if (oldCursor != null && oldCursor.getType() == Material.WATER_BUCKET) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMilking(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Entity entity = event.getRightClicked();
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != Material.BUCKET) return;
        if (!getConfig().getBoolean("milking.enabled", true)) return;

        String animal = getAnimalKey(entity);
        if (animal == null || animal.equals("chicken")) return;
        if (!getConfig().getBoolean("milking.animals." + animal, true)) return;

        long cooldown = Math.max(0L, getConfig().getLong("milking.cooldown-seconds", 30L)) * 1000L;
        long now = System.currentTimeMillis();
        long last = milkingCooldown.getOrDefault(entity.getUniqueId(), 0L);
        if (now - last < cooldown) {
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
    public void onDeath(EntityDeathEvent event) {
        String animal = getAnimalKey(event.getEntity());
        if (animal == null || animal.equals("chicken")) return;
        ConfigurationSection section = getConfig().getConfigurationSection("drops." + animal);
        if (section == null) return;
        addConfiguredDrop(event, section, "meat", meatItem(animal));
        addConfiguredDrop(event, section, "leather", new ItemStack(Material.LEATHER));
        addConfiguredDrop(event, section, "bone", new ItemStack(Material.BONE));
        if (animal.equals("sheep")) addConfiguredDrop(event, section, "wool", new ItemStack(Material.WHITE_WOOL));
    }

    private ItemStack meatItem(String animal) {
        // В Paper/Minecraft 26.2 отдельного ванильного предмета козлятины нет.
        // Для первой версии козье мясо временно представлено сырой бараниной.
        return switch (animal) {
            case "cow" -> new ItemStack(Material.BEEF);
            case "sheep", "goat" -> new ItemStack(Material.MUTTON);
            default -> new ItemStack(Material.BEEF);
        };
    }

    private void addConfiguredDrop(EntityDeathEvent event, ConfigurationSection section, String key, ItemStack prototype) {
        int min = Math.max(0, section.getInt(key + ".min", 0));
        int max = Math.max(min, section.getInt(key + ".max", min));
        int amount = ThreadLocalRandom.current().nextInt(min, max + 1);
        if (amount > 0) {
            ItemStack drop = prototype.clone();
            drop.setAmount(amount);
            event.getDrops().add(drop);
        }
    }

    private void startFeederTask() {
        long interval = Math.max(20L, getConfig().getLong("feeder.interval-seconds", 5L) * 20L);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (getConfig().getBoolean("feeder.enabled", true)) processLoadedFeeders();
            }
        }.runTaskTimer(this, interval, interval);
    }

    private void processLoadedFeeders() {
        for (World world : getServer().getWorlds()) {
            for (var chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities()) {
                    if (state instanceof Barrel barrel && isFeederBlock(barrel.getBlock())) processFeeder(barrel);
                }
            }
        }
    }

    private void processFeeder(Barrel barrel) {
        PenStatus pen = analyzePen(barrel.getLocation());
        if (!pen.valid() || pen.animals().isEmpty()) return;

        int water = getWater(barrel);
        if (water <= 0) return;

        int maxBirths = Math.max(1, getConfig().getInt("feeder.max-births-per-cycle", 1));
        int births = 0;
        Map<String, List<Animals>> byType = new HashMap<>();

        for (Animals animal : pen.animals()) {
            if (!animal.isAdult() || !animal.canBreed()) continue;
            String type = getAnimalKey(animal);
            if (type != null) byType.computeIfAbsent(type, ignored -> new ArrayList<>()).add(animal);
        }

        for (List<Animals> group : byType.values()) {
            for (int i = 0; i + 1 < group.size() && births < maxBirths; i += 2) {
                Animals first = group.get(i);
                Animals second = group.get(i + 1);
                Material food = findFoodFor(first, barrel.getInventory());
                if (food == null) continue;
                removeOneFood(barrel.getInventory(), food);
                water--;
                setWater(barrel, water);
                first.setBreed(true);
                second.setBreed(true);
                births++;
            }
            if (births >= maxBirths || water <= 0) break;
        }
    }

    private Material findFoodFor(Animals animal, Inventory inventory) {
        String key = getAnimalKey(animal);
        if (key == null || !getConfig().getBoolean("feeding." + key + ".enabled", true)) return null;
        for (String configured : getConfig().getStringList("feeding." + key + ".foods")) {
            Material material = Material.matchMaterial(configured);
            if (material != null && contains(inventory, material)) return material;
        }
        return null;
    }

    private boolean contains(Inventory inventory, Material material) {
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() == material && item.getAmount() > 0) return true;
        }
        return false;
    }

    private void removeOneFood(Inventory inventory, Material material) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() != material) continue;
            if (item.getAmount() <= 1) inventory.setItem(slot, null);
            else item.setAmount(item.getAmount() - 1);
            return;
        }
    }

    private PenStatus analyzePen(Location feeder) {
        int maxRadius = Math.max(4, getConfig().getInt("pen.max-radius", 16));
        int startX = feeder.getBlockX();
        int startZ = feeder.getBlockZ();
        Set<String> inside = new HashSet<>();
        Set<String> gates = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{startX, startZ});
        inside.add(key(startX, startZ));
        boolean escaped = false;

        while (!queue.isEmpty()) {
            int[] point = queue.removeFirst();
            int x = point[0];
            int z = point[1];
            if (Math.abs(x - startX) > maxRadius || Math.abs(z - startZ) > maxRadius) {
                escaped = true;
                continue;
            }

            for (int[] direction : DIRECTIONS) {
                int nx = x + direction[0];
                int nz = z + direction[1];
                if (Math.abs(nx - startX) > maxRadius || Math.abs(nz - startZ) > maxRadius) {
                    escaped = true;
                    continue;
                }

                Block next = feeder.getWorld().getBlockAt(nx, feeder.getBlockY(), nz);
                if (isFenceGate(next)) {
                    gates.add(key(nx, nz));
                    if (isGateOpen(next)) escaped = true;
                    continue;
                }
                if (isFence(next)) continue;

                String nextKey = key(nx, nz);
                if (inside.add(nextKey)) queue.addLast(new int[]{nx, nz});
            }
        }

        int gateCount = gates.size();
        boolean valid = !escaped && gateCount == 1;
        List<Animals> animals = new ArrayList<>();

        if (valid) {
            double radius = maxRadius + 0.5;
            Location center = feeder.clone().add(0.5, 0.5, 0.5);
            for (Entity entity : feeder.getWorld().getNearbyEntities(center, radius, 3, radius)) {
                if (!(entity instanceof Animals animal)) continue;
                String animalKey = key(animal.getLocation().getBlockX(), animal.getLocation().getBlockZ());
                if (inside.contains(animalKey)) animals.add(animal);
            }
        }
        return new PenStatus(valid, gateCount, animals, escaped);
    }

    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private boolean isFence(Block block) {
        return block.getType().name().endsWith("_FENCE");
    }

    private boolean isFenceGate(Block block) {
        return block.getType().name().endsWith("_FENCE_GATE");
    }

    private boolean isGateOpen(Block block) {
        return block.getBlockData() instanceof Openable openable && openable.isOpen();
    }

    private String formatStatus(PenStatus status, Barrel barrel) {
        if (status.escaped()) return message("pen-open");
        if (status.gateCount() == 0) return message("pen-no-gate");
        if (status.gateCount() > 1) return message("pen-many-gates");
        return message("pen-ready")
                .replace("%animals%", String.valueOf(status.animals().size()))
                .replace("%water%", String.valueOf(getWater(barrel)))
                .replace("%wheat%", String.valueOf(count(barrel.getInventory(), Material.WHEAT)))
                .replace("%seeds%", String.valueOf(countSeeds(barrel.getInventory())));
    }

    private String getAnimalKey(Entity entity) {
        if (entity instanceof Cow) return "cow";
        if (entity instanceof Sheep) return "sheep";
        if (entity instanceof Goat) return "goat";
        if (entity instanceof Chicken) return "chicken";
        return null;
    }

    private String key(int x, int z) {
        return x + ":" + z;
    }

    private int getWater(Barrel barrel) {
        return barrel.getPersistentDataContainer().getOrDefault(waterKey, PersistentDataType.INTEGER, 0);
    }

    private void setWater(Barrel barrel, int amount) {
        barrel.getPersistentDataContainer().set(waterKey, PersistentDataType.INTEGER, Math.max(0, amount));
        barrel.update(true, false);
    }

    private int count(Inventory inventory, Material material) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() == material) total += item.getAmount();
        }
        return total;
    }

    private int countSeeds(Inventory inventory) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && isSeed(item.getType())) total += item.getAmount();
        }
        return total;
    }

    private boolean isSeed(Material material) {
        return material == Material.WHEAT_SEEDS
                || material == Material.BEETROOT_SEEDS
                || material == Material.PUMPKIN_SEEDS
                || material == Material.MELON_SEEDS;
    }

    private void removeOneMainHandItem(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getAmount() <= 1) player.getInventory().setItemInMainHand(null);
        else hand.setAmount(hand.getAmount() - 1);
    }

    private void replaceOneMainHandItem(Player player, ItemStack replacement) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getAmount() <= 1) player.getInventory().setItemInMainHand(replacement);
        else {
            hand.setAmount(hand.getAmount() - 1);
            giveItem(player, replacement);
        }
    }

    private void giveItem(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private record PenStatus(boolean valid, int gateCount, List<Animals> animals, boolean escaped) {}
}
