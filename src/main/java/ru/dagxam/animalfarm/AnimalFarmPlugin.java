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
import org.bukkit.entity.Mob;
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
    private NamespacedKey feederItemKey, feederBlockKey, waterProgressKey;

    @Override public void onEnable() {
        saveDefaultConfig();
        feederItemKey = new NamespacedKey(this, "feeder_item");
        feederBlockKey = new NamespacedKey(this, "feeder_block");
        waterProgressKey = new NamespacedKey(this, "water_progress");
        getServer().getPluginManager().registerEvents(this, this);
        AnimalFarmCommand command = new AnimalFarmCommand(this);
        Objects.requireNonNull(getCommand("animalfarm")).setExecutor(command);
        Objects.requireNonNull(getCommand("animalfarm")).setTabCompleter(command);
        registerFeederRecipe();
        startFeederTask();
        startHudTask();
        getLogger().info("AnimalFarm включён. Paper 26.2 / Java 26.");
    }

    @Override public void onDisable() { getLogger().info("AnimalFarm выключен."); }
    public void reloadPluginConfig() { reloadConfig(); }
    public String message(String path) { return color(getConfig().getString("messages." + path, "")); }
    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

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
        if (meta != null) {
            meta.setDisplayName(color("&6Кормушка"));
            meta.setLore(List.of(
                    color("&7Обычная бочка с функцией кормушки."),
                    color("&7Корм и вода помещаются внутрь.")
            ));
            meta.getPersistentDataContainer().set(feederItemKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isFeederItem(ItemStack item) {
        if (item == null || item.getType() != Material.BARREL || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(feederItemKey, PersistentDataType.BYTE);
    }

    private boolean isFeederBlock(Block block) {
        if (block == null || block.getType() != Material.BARREL || !(block.getState() instanceof Barrel barrel)) return false;
        return barrel.getPersistentDataContainer().has(feederBlockKey, PersistentDataType.BYTE);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!isFeederItem(event.getItemInHand()) || !(event.getBlockPlaced().getState() instanceof Barrel barrel)) return;
        barrel.getPersistentDataContainer().set(feederBlockKey, PersistentDataType.BYTE, (byte) 1);
        barrel.getPersistentDataContainer().set(waterProgressKey, PersistentDataType.INTEGER, 0);
        barrel.setCustomName("Кормушка");
        barrel.update(true, false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
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
    }

    @EventHandler(ignoreCancelled = true)
    public void onFeederInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (!isFeederBlock(block)) return;
        // Ведро воды теперь помещается непосредственно в инвентарь бочки.
        // Обычный ПКМ открывает стандартный инвентарь бочки.
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Barrel barrel) || !isFeederBlock(barrel.getBlock())) return;
        // Ведро воды, пшеница и семена являются обычными предметами инвентаря кормушки.
        // Лаву в кормушку не разрешаем.
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        if ((cursor != null && cursor.getType() == Material.LAVA_BUCKET)
                || (current != null && current.getType() == Material.LAVA_BUCKET)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof Barrel barrel) || !isFeederBlock(barrel.getBlock())) return;
        if (event.getOldCursor() != null && event.getOldCursor().getType() == Material.LAVA_BUCKET) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMilk(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (player.getInventory().getItemInMainHand().getType() != Material.BUCKET) return;
        String animal = getAnimalKey(event.getRightClicked());
        if (animal == null || animal.equals("chicken") || !getConfig().getBoolean("milking.animals." + animal, true)) return;

        long cooldown = Math.max(0, getConfig().getLong("milking.cooldown-seconds", 30)) * 1000L;
        long now = System.currentTimeMillis();
        long last = milkingCooldown.getOrDefault(event.getRightClicked().getUniqueId(), 0L);
        if (now - last < cooldown) {
            event.setCancelled(true);
            player.sendMessage(message("prefix") + message("milk-cooldown"));
            return;
        }

        event.setCancelled(true);
        milkingCooldown.put(event.getRightClicked().getUniqueId(), now);
        replaceOneMainHandItem(player, new ItemStack(Material.MILK_BUCKET));
        String messageKey = switch (animal) {
            case "cow" -> "milk-cow";
            case "sheep" -> "milk-sheep";
            default -> "milk-goat";
        };
        player.sendMessage(message("prefix") + message(messageKey));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        String animal = getAnimalKey(event.getEntity());
        if (animal == null) return;

        if (animal.equals("chicken")) {
            event.getDrops().removeIf(item -> item.getType() == Material.FEATHER);
            addDrop(event,
                    new ItemStack(Material.FEATHER),
                    getConfig().getInt("drops.chicken.feather.min", 2),
                    getConfig().getInt("drops.chicken.feather.max", 5));
            return;
        }

        ConfigurationSection section = getConfig().getConfigurationSection("drops." + animal);
        if (section == null) return;
        event.getDrops().clear();
        addConfiguredDrop(event, section, "meat", meat(animal));
        addConfiguredDrop(event, section, "leather", new ItemStack(Material.LEATHER));
        addConfiguredDrop(event, section, "bone", new ItemStack(Material.BONE));
        if (animal.equals("sheep")) addConfiguredDrop(event, section, "wool", new ItemStack(Material.WHITE_WOOL));
    }

    private ItemStack meat(String animal) {
        return switch (animal) {
            case "cow" -> new ItemStack(Material.BEEF);
            case "sheep" -> new ItemStack(Material.MUTTON);
            case "goat" -> named(Material.MUTTON, "&fКозлятина");
            default -> new ItemStack(Material.BEEF);
        };
    }

    private ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void addConfiguredDrop(EntityDeathEvent event, ConfigurationSection section, String key, ItemStack prototype) {
        addDrop(event, prototype,
                section.getInt(key + ".min", 0),
                section.getInt(key + ".max", section.getInt(key + ".min", 0)));
    }

    private void addDrop(EntityDeathEvent event, ItemStack prototype, int min, int max) {
        min = Math.max(0, min);
        max = Math.max(min, max);
        int amount = ThreadLocalRandom.current().nextInt(min, max + 1);
        if (amount > 0) {
            ItemStack drop = prototype.clone();
            drop.setAmount(amount);
            event.getDrops().add(drop);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onChickenEgg(EntityDropItemEvent event) {
        if (!(event.getEntity() instanceof Chicken chicken)) return;
        PenStatus pen = findActivePen(chicken);
        if (!pen.valid()) return;

        int min = getConfig().getInt("chicken.pen-extra-eggs.min", 1);
        int max = getConfig().getInt("chicken.pen-extra-eggs.max", 2);
        int extra = ThreadLocalRandom.current().nextInt(Math.max(0, min), Math.max(min, max) + 1);
        for (int i = 0; i < extra; i++) {
            chicken.getWorld().dropItemNaturally(chicken.getLocation(), new ItemStack(Material.EGG));
        }
    }

    private void startFeederTask() {
        long ticks = Math.max(10, getConfig().getLong("feeder.check-interval-seconds", 2) * 20L);
        new BukkitRunnable() {
            @Override public void run() {
                if (getConfig().getBoolean("feeder.enabled", true)) processFeeders();
            }
        }.runTaskTimer(this, ticks, ticks);
    }

    private void startHudTask() {
        new BukkitRunnable() {
            @Override public void run() {
                for (Player player : getServer().getOnlinePlayers()) {
                    Block target = player.getTargetBlockExact(getConfig().getInt("hud.range", 6));
                    if (target != null && isFeederBlock(target)) {
                        Barrel barrel = (Barrel) target.getState();
                        player.sendActionBar(formatHud(analyzePen(target.getLocation()), barrel));
                    }
                }
            }
        }.runTaskTimer(this, 5L, 5L);
    }

    private void processFeeders() {
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
        if (!pen.valid()) return;

        int eatingDistance = (int) Math.pow(Math.max(1, getConfig().getDouble("feeder.eating-distance", 2.5)), 2);
        int maxFeedings = Math.max(1, getConfig().getInt("feeder.max-feedings-per-cycle", 2));
        int fed = 0;
        Map<String, List<Animals>> groups = new HashMap<>();

        for (Animals animal : pen.animals()) {
            if (!animal.isAdult() || !animal.canBreed()) continue;
            String type = getAnimalKey(animal);
            if (type != null) groups.computeIfAbsent(type, ignored -> new ArrayList<>()).add(animal);
        }

        for (List<Animals> group : groups.values()) {
            if (group.size() < 2) continue;

            for (Animals animal : group) {
                if (fed >= maxFeedings || !animal.isAdult() || !animal.canBreed()) break;
                Material food = findFood(animal, barrel.getInventory());
                if (food == null) continue;

                Location target = barrel.getLocation().add(0.5, 0.5, 0.5);
                double distance = animal.getLocation().distanceSquared(target);
                if (distance > eatingDistance) {
                    if (animal instanceof Mob mob) {
                        mob.getPathfinder().moveTo(target, getConfig().getDouble("feeder.path-speed", 1.1));
                    }
                    continue;
                }

                // Животное дошло до кормушки. Теперь происходит настоящее автоматическое кормление.
                // После этого setBreed(true) передаёт размножение обычному ванильному AI Minecraft.
                if (!consumeWaterCharge(barrel)) continue;
                removeOneFood(barrel.getInventory(), food);
                animal.setBreed(true);
                fed++;
            }
        }
    }

    private Material findFood(Animals animal, Inventory inventory) {
        String key = getAnimalKey(animal);
        if (key == null) return null;
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

    // Одно полное водяное ведро даёт несколько автоматических кормлений.
    // Само ведро хранится внутри кормушки как обычный предмет.
    // После заданного количества кормлений оно превращается в пустое ведро.
    private boolean consumeWaterCharge(Barrel barrel) {
        if (!contains(barrel.getInventory(), Material.WATER_BUCKET)) return false;

        int progress = getWaterProgress(barrel) + 1;
        int feedingsPerBucket = Math.max(1, getConfig().getInt("feeder.water-feedings-per-bucket", 10));

        if (progress >= feedingsPerBucket) {
            removeOneFood(barrel.getInventory(), Material.WATER_BUCKET);
            barrel.getInventory().addItem(new ItemStack(Material.BUCKET));
            progress = 0;
        }

        setWaterProgress(barrel, progress);
        return true;
    }

    private int getWaterProgress(Barrel barrel) {
        return barrel.getPersistentDataContainer().getOrDefault(waterProgressKey, PersistentDataType.INTEGER, 0);
    }

    private void setWaterProgress(Barrel barrel, int value) {
        barrel.getPersistentDataContainer().set(waterProgressKey, PersistentDataType.INTEGER, value);
        barrel.update(true, false);
    }

    private int count(Inventory inventory, Material material) {
        int count = 0;
        for (ItemStack item : inventory.getContents()) if (item != null && item.getType() == material) count += item.getAmount();
        return count;
    }

    private int countSeeds(Inventory inventory) {
        int count = 0;
        for (ItemStack item : inventory.getContents()) if (item != null && isSeed(item.getType())) count += item.getAmount();
        return count;
    }

    private boolean isSeed(Material material) {
        String name = material.name();
        return name.endsWith("_SEEDS") || material == Material.PITCHER_POD;
    }

    private String formatHud(PenStatus status, Barrel barrel) {
        String base = "&6Кормушка &7| ";
        if (!status.valid()) return color(base + "&cЗагон не готов &7| " + penReason(status));
        return color(base + "&aЗагон активен &7| &fЖивотных: &e" + status.animals().size()
                + " &7| &bВода: &e" + count(barrel.getInventory(), Material.WATER_BUCKET)
                + " вед. &7| &eПшеница: " + count(barrel.getInventory(), Material.WHEAT)
                + " &7| &eСемена: " + countSeeds(barrel.getInventory()));
    }

    private String penReason(PenStatus status) {
        if (status.escaped()) return "граница не замкнута";
        if (status.gates() == 0) return "нужна ровно одна калитка";
        if (status.gates() > 1) return "должна быть только одна калитка";
        if (status.openGate()) return "калитка должна быть закрыта";
        return "загон не готов";
    }

    private PenStatus analyzePen(Location feeder) {
        int radius = Math.max(4, getConfig().getInt("pen.max-radius", 16));
        int startX = feeder.getBlockX();
        int startZ = feeder.getBlockZ();
        Set<String> inside = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{startX, startZ});
        inside.add(key(startX, startZ));

        int gates = 0;
        boolean openGate = false;
        boolean escaped = false;

        while (!queue.isEmpty()) {
            int[] point = queue.removeFirst();
            int x = point[0];
            int z = point[1];

            for (int[] direction : DIRECTIONS) {
                int nx = x + direction[0];
                int nz = z + direction[1];

                if (Math.abs(nx - startX) > radius || Math.abs(nz - startZ) > radius) {
                    escaped = true;
                    continue;
                }

                Block next = feeder.getWorld().getBlockAt(nx, feeder.getBlockY(), nz);

                if (isGate(next)) {
                    String gateKey = key(nx, nz);
                    if (inside.add(gateKey)) {
                        gates++;
                        if (isOpen(next)) openGate = true;
                    }
                    continue;
                }

                if (isFence(next)) continue;

                if (next.getType().isAir() && inside.add(key(nx, nz))) {
                    queue.add(new int[]{nx, nz});
                }
            }
        }

        List<Animals> animals = new ArrayList<>();
        double range = radius + 1.0;
        for (Entity entity : feeder.getWorld().getNearbyEntities(feeder, range,
                Math.max(3, getConfig().getInt("pen.vertical-range", 5)), range)) {
            if (entity instanceof Animals animal
                    && inside.contains(key(animal.getLocation().getBlockX(), animal.getLocation().getBlockZ()))) {
                animals.add(animal);
            }
        }

        boolean valid = !escaped && gates == 1 && !openGate;
        return new PenStatus(valid, escaped, gates, openGate, animals);
    }

    private boolean isFence(Block block) {
        String name = block.getType().name();
        return name.endsWith("_FENCE") && !name.endsWith("_FENCE_GATE");
    }

    private boolean isGate(Block block) {
        return block.getBlockData() instanceof Openable && block.getType().name().endsWith("_FENCE_GATE");
    }

    private boolean isOpen(Block block) {
        return block.getBlockData() instanceof Openable openable && openable.isOpen();
    }

    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private String key(int x, int z) { return x + ":" + z; }

    private PenStatus findActivePen(Animals animal) {
        for (World world : getServer().getWorlds()) {
            for (var chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities()) {
                    if (state instanceof Barrel barrel && isFeederBlock(barrel.getBlock())) {
                        PenStatus status = analyzePen(barrel.getLocation());
                        if (status.valid() && status.animals().contains(animal)) return status;
                    }
                }
            }
        }
        return new PenStatus(false, false, 0, false, List.of());
    }

    private String getAnimalKey(Entity entity) {
        return switch (entity.getType()) {
            case COW -> "cow";
            case SHEEP -> "sheep";
            case GOAT -> "goat";
            case CHICKEN -> "chicken";
            default -> null;
        };
    }

    private void replaceOneMainHandItem(Player player, ItemStack replacement) {
        ItemStack old = player.getInventory().getItemInMainHand();
        if (old.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(replacement);
        } else {
            old.setAmount(old.getAmount() - 1);
            player.getInventory().setItemInMainHand(old);
            player.getInventory().addItem(replacement);
        }
    }

    private record PenStatus(boolean valid, boolean escaped, int gates, boolean openGate, List<Animals> animals) {}
}
