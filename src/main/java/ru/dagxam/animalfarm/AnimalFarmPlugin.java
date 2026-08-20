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
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
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
        startFeederHudTask();
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
        meta.setLore(List.of(color("&7Обычная бочка с функцией кормушки."), color("&7Нужны корм и вода.")));
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
        if (block.getType() != Material.BARREL || !(block.getState() instanceof Barrel barrel)) return false;
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
            if (item != null && !item.getType().isAir()) event.getBlock().getWorld().dropItemNaturally(location, item.clone());
        }
        int water = getWater(barrel);
        if (water > 0) event.getBlock().getWorld().dropItemNaturally(location, new ItemStack(Material.BUCKET, water));
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
            int max = Math.max(1, getConfig().getInt("feeder.max-water", 64));
            int current = getWater(barrel);
            if (current >= max) {
                player.sendMessage(message("prefix") + message("water-full"));
                return;
            }
            setWater(barrel, current + 1);
            removeOneMainHandItem(player);
            giveItem(player, new ItemStack(Material.BUCKET));
            player.sendMessage(message("prefix") + message("water-added"));
            return;
        }
        // Обычный ПКМ должен открывать стандартную бочку. Информация показывается при наведении.
    }

    @EventHandler(ignoreCancelled = true)
    public void onFeederInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Barrel barrel) || !isFeederBlock(barrel.getBlock())) return;
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        if ((cursor != null && cursor.getType() == Material.WATER_BUCKET) || (current != null && current.getType() == Material.WATER_BUCKET)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFeederInventoryDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof Barrel barrel) || !isFeederBlock(barrel.getBlock())) return;
        if (event.getOldCursor() != null && event.getOldCursor().getType() == Material.WATER_BUCKET) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMilking(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Entity entity = event.getRightClicked();
        Player player = event.getPlayer();
        if (player.getInventory().getItemInMainHand().getType() != Material.BUCKET) return;
        if (!getConfig().getBoolean("milking.enabled", true)) return;
        String animal = getAnimalKey(entity);
        if (animal == null || animal.equals("chicken") || !getConfig().getBoolean("milking.animals." + animal, true)) return;
        long cooldown = Math.max(0L, getConfig().getLong("milking.cooldown-seconds", 30L)) * 1000L;
        long now = System.currentTimeMillis();
        if (now - milkingCooldown.getOrDefault(entity.getUniqueId(), 0L) < cooldown) {
            event.setCancelled(true);
            player.sendMessage(message("prefix") + message("milk-cooldown"));
            return;
        }
        event.setCancelled(true);
        milkingCooldown.put(entity.getUniqueId(), now);
        replaceOneMainHandItem(player, new ItemStack(Material.MILK_BUCKET));
        String key = switch (animal) { case "cow" -> "milk-cow"; case "sheep" -> "milk-sheep"; case "goat" -> "milk-goat"; default -> "milk-cow"; };
        player.sendMessage(message("prefix") + message(key));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        String animal = getAnimalKey(event.getEntity());
        if (animal == null) return;
        if (animal.equals("chicken")) {
            event.getDrops().removeIf(item -> item.getType() == Material.FEATHER);
            int min = getConfig().getInt("drops.chicken.feather.min", 2);
            int max = getConfig().getInt("drops.chicken.feather.max", 5);
            addDrop(event, new ItemStack(Material.FEATHER), min, max);
            return;
        }
        ConfigurationSection section = getConfig().getConfigurationSection("drops." + animal);
        if (section == null) return;
        event.getDrops().clear();
        addConfiguredDrop(event, section, "meat", meatItem(animal));
        addConfiguredDrop(event, section, "leather", new ItemStack(Material.LEATHER));
        addConfiguredDrop(event, section, "bone", new ItemStack(Material.BONE));
        if (animal.equals("sheep")) addConfiguredDrop(event, section, "wool", new ItemStack(Material.WHITE_WOOL));
    }

    private ItemStack meatItem(String animal) {
        return switch (animal) {
            case "cow" -> new ItemStack(Material.BEEF);
            case "sheep" -> new ItemStack(Material.MUTTON);
            case "goat" -> namedItem(Material.MUTTON, "&fКозлятина");
            default -> new ItemStack(Material.BEEF);
        };
    }

    private ItemStack namedItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.setDisplayName(color(name)); item.setItemMeta(meta); }
        return item;
    }

    private void addConfiguredDrop(EntityDeathEvent event, ConfigurationSection section, String key, ItemStack prototype) {
        addDrop(event, prototype, section.getInt(key + ".min", 0), section.getInt(key + ".max", section.getInt(key + ".min", 0)));
    }

    private void addDrop(EntityDeathEvent event, ItemStack prototype, int min, int max) {
        min = Math.max(0, min); max = Math.max(min, max);
        int amount = ThreadLocalRandom.current().nextInt(min, max + 1);
        if (amount > 0) { ItemStack drop = prototype.clone(); drop.setAmount(amount); event.getDrops().add(drop); }
    }

    @EventHandler(ignoreCancelled = true)
    public void onChickenEgg(EntityDropItemEvent event) {
        if (!(event.getEntity() instanceof Chicken chicken)) return;
        PenStatus pen = findActivePenForAnimal(chicken);
        if (!pen.valid()) return;
        int extraMin = getConfig().getInt("chicken.pen-extra-eggs.min", 1);
        int extraMax = getConfig().getInt("chicken.pen-extra-eggs.max", 2);
        int extra = ThreadLocalRandom.current().nextInt(Math.max(0, extraMin), Math.max(0, extraMax) + 1);
        if (extra <= 0) return;
        Location loc = chicken.getLocation();
        for (int i = 0; i < extra; i++) loc.getWorld().dropItemNaturally(loc, new ItemStack(Material.EGG));
    }

    private void startFeederTask() {
        long interval = Math.max(10L, getConfig().getLong("feeder.interval-seconds", 5L) * 20L);
        new BukkitRunnable() { @Override public void run() { if (getConfig().getBoolean("feeder.enabled", true)) processLoadedFeeders(); } }.runTaskTimer(this, interval, interval);
    }

    private void startFeederHudTask() {
        new BukkitRunnable() {
            @Override public void run() {
                for (Player player : getServer().getOnlinePlayers()) {
                    Block target = player.getTargetBlockExact(Math.max(1, getConfig().getInt("hud.range", 6)));
                    if (target != null && isFeederBlock(target)) {
                        Barrel barrel = (Barrel) target.getState();
                        PenStatus status = analyzePen(target.getLocation());
                        player.sendActionBar(formatHud(status, barrel));
                    }
                }
            }
        }.runTaskTimer(this, 5L, 5L);
    }

    private String formatHud(PenStatus status, Barrel barrel) {
        int wheat = count(barrel.getInventory(), Material.WHEAT);
        int seeds = countSeeds(barrel.getInventory());
        int water = getWater(barrel);
        if (!status.valid()) return color("&6Кормушка &7| &cЗагон не готов &8| &7" + penReason(status));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Animals animal : status.animals()) { String type = getAnimalKey(animal); if (type != null) counts.merge(type, 1, Integer::sum); }
        return color("&6Кормушка &7| &aЗагон активен &7| &fЖивотных: &e" + status.animals().size() + " &7| &bВода: " + water + " &7| &eПшеница: " + wheat + " &7| &eСемена: " + seeds);
    }

    private String penReason(PenStatus status) {
        if (status.escaped()) return "граница не замкнута";
        if (status.gateCount() == 0) return "нужна одна калитка";
        if (status.gateCount() > 1) return "нужна только одна калитка";
        return "загон не готов";
    }

    private void processLoadedFeeders() {
        for (World world : getServer().getWorlds()) for (var chunk : world.getLoadedChunks()) for (BlockState state : chunk.getTileEntities()) if (state instanceof Barrel barrel && isFeederBlock(barrel.getBlock())) processFeeder(barrel);
    }

    private void processFeeder(Barrel barrel) {
        PenStatus pen = analyzePen(barrel.getLocation());
        if (!pen.valid() || pen.animals().isEmpty() || getWater(barrel) <= 0) return;
        int births = 0;
        int maxBirths = Math.max(1, getConfig().getInt("feeder.max-births-per-cycle", 1));
        Map<String, List<Animals>> groups = new HashMap<>();
        for (Animals animal : pen.animals()) {
            if (!animal.isAdult() || !animal.canBreed()) continue;
            String type = getAnimalKey(animal);
            if (type != null) groups.computeIfAbsent(type, ignored -> new ArrayList<>()).add(animal);
        }
        for (List<Animals> group : groups.values()) {
            if (group.size() < 2) continue;
            for (int i = 0; i + 1 < group.size() && births < maxBirths; i += 2) {
                Animals a = group.get(i), b = group.get(i + 1);
                Material food = findFoodFor(a, barrel.getInventory());
                if (food == null) continue;
                // Сначала животные подходят к кормушке. Только после этого корм расходуется.
                if (a.getLocation().distanceSquared(barrel.getLocation().add(0.5, 0.0, 0.5)) > 9.0) a.getPathfinder().moveTo(barrel.getLocation().add(0.5, 0.0, 0.5), 1.0);
                if (b.getLocation().distanceSquared(barrel.getLocation().add(0.5, 0.0, 0.5)) > 9.0) b.getPathfinder().moveTo(barrel.getLocation().add(0.5, 0.0, 0.5), 1.0);
                if (a.getLocation().distanceSquared(barrel.getLocation()) > 4.0 || b.getLocation().distanceSquared(barrel.getLocation()) > 4.0) continue;
                removeOneFood(barrel.getInventory(), food);
                setWater(barrel, getWater(barrel) - 1);
                a.setBreed(true); b.setBreed(true); births++;
            }
            if (births >= maxBirths) break;
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
        for (ItemStack item : inventory.getContents()) if (item != null && item.getType() == material && item.getAmount() > 0) return true;
        return false;
    }

    private void removeOneFood(Inventory inventory, Material material) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() != material) continue;
            if (item.getAmount() <= 1) inventory.setItem(slot, null); else item.setAmount(item.getAmount() - 1);
            return;
        }
    }

    private PenStatus findActivePenForAnimal(Animals animal) {
        int radius = Math.max(4, getConfig().getInt("pen.max-radius", 16));
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            Block block = animal.getWorld().getBlockAt(animal.getLocation().getBlockX() + dx, animal.getLocation().getBlockY(), animal.getLocation().getBlockZ() + dz);
            if (isFeederBlock(block)) {
                PenStatus status = analyzePen(block.getLocation());
                if (status.valid() && status.animals().contains(animal)) return status;
            }
        }
        return new PenStatus(false, 0, List.of(), true);
    }

    private PenStatus analyzePen(Location feeder) {
        int radius = Math.max(4, getConfig().getInt("pen.max-radius", 16));
        int sx = feeder.getBlockX(), sz = feeder.getBlockZ();
        Set<String> inside = new HashSet<>(), gates = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{sx, sz}); inside.add(key(sx, sz));
        boolean escaped = false;
        while (!queue.isEmpty()) {
            int[] p = queue.removeFirst();
            int x = p[0], z = p[1];
            for (int[] d : DIRECTIONS) {
                int nx = x + d[0], nz = z + d[1];
                if (Math.abs(nx - sx) > radius || Math.abs(nz - sz) > radius) { escaped = true; continue; }
                Block next = feeder.getWorld().getBlockAt(nx, feeder.getBlockY(), nz);
                if (isFenceGate(next)) { gates.add(key(nx, nz)); if (isGateOpen(next)) escaped = true; continue; }
                if (isFence(next)) continue;
                String k = key(nx, nz);
                if (inside.add(k)) queue.addLast(new int[]{nx, nz});
            }
        }
        boolean valid = !escaped && gates.size() == 1;
        List<Animals> animals = new ArrayList<>();
        if (valid) {
            for (Entity entity : feeder.getWorld().getNearbyEntities(feeder.clone().add(0.5, 0.5, 0.5), radius + 0.5, 3, radius + 0.5)) {
                if (!(entity instanceof Animals animal)) continue;
                if (inside.contains(key(animal.getLocation().getBlockX(), animal.getLocation().getBlockZ()))) animals.add(animal);
            }
        }
        return new PenStatus(valid, gates.size(), animals, escaped);
    }

    private static final int[][] DIRECTIONS = {{1,0},{-1,0},{0,1},{0,-1}};
    private boolean isFence(Block block) { return block.getType().name().endsWith("_FENCE"); }
    private boolean isFenceGate(Block block) { return block.getType().name().endsWith("_FENCE_GATE"); }
    private boolean isGateOpen(Block block) { return block.getBlockData() instanceof Openable openable && openable.isOpen(); }

    private String key(int x, int z) { return x + ":" + z; }
    private int count(Inventory inventory, Material material) { int n = 0; for (ItemStack item : inventory.getContents()) if (item != null && item.getType() == material) n += item.getAmount(); return n; }
    private int countSeeds(Inventory inventory) { int n = 0; for (ItemStack item : inventory.getContents()) if (item != null && item.getType().name().endsWith("_SEEDS")) n += item.getAmount(); return n; }
    private int getWater(Barrel barrel) { Integer value = barrel.getPersistentDataContainer().get(waterKey, PersistentDataType.INTEGER); return value == null ? 0 : Math.max(0, value); }
    private void setWater(Barrel barrel, int value) { barrel.getPersistentDataContainer().set(waterKey, PersistentDataType.INTEGER, Math.max(0, value)); barrel.update(true, false); }
    private void removeOneMainHandItem(Player player) { ItemStack item = player.getInventory().getItemInMainHand(); if (item.getAmount() <= 1) player.getInventory().setItemInMainHand(null); else item.setAmount(item.getAmount() - 1); }
    private void replaceOneMainHandItem(Player player, ItemStack replacement) { ItemStack item = player.getInventory().getItemInMainHand(); if (item.getAmount() <= 1) player.getInventory().setItemInMainHand(replacement); else { item.setAmount(item.getAmount() - 1); player.getInventory().addItem(replacement); } }
    private void giveItem(Player player, ItemStack item) { Map<Integer, ItemStack> left = player.getInventory().addItem(item); for (ItemStack rest : left.values()) player.getWorld().dropItemNaturally(player.getLocation(), rest); }

    private String getAnimalKey(Entity entity) {
        return switch (entity.getType()) { case COW -> "cow"; case SHEEP -> "sheep"; case GOAT -> "goat"; case CHICKEN -> "chicken"; default -> null; };
    }

    private record PenStatus(boolean valid, int gateCount, List<Animals> animals, boolean escaped) {}
}
