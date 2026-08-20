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
import org.bukkit.entity.Ageable;
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
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class AnimalFarmPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, Long> milkingCooldown = new HashMap<>();
    private NamespacedKey feederItemKey, feederBlockKey, waterProgressKey;
    private NamespacedKey breedDayKey, milkFeedCountKey, milkFeedDayKey, productionDayKey, mobBucketKey;

    @Override public void onEnable() {
        saveDefaultConfig();
        feederItemKey = new NamespacedKey(this, "feeder_item");
        feederBlockKey = new NamespacedKey(this, "feeder_block");
        waterProgressKey = new NamespacedKey(this, "water_progress");
        breedDayKey = new NamespacedKey(this, "breed_day");
        milkFeedCountKey = new NamespacedKey(this, "milk_feed_count");
        milkFeedDayKey = new NamespacedKey(this, "milk_feed_day");
        productionDayKey = new NamespacedKey(this, "production_day");
        mobBucketKey = new NamespacedKey(this, "mob_bucket");

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
            meta.setLore(List.of(color("&7Автоматический загон для животных."), color("&7Корм, вода и молоко помещаются внутрь.")));
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

    private boolean isMobBucket(ItemStack item) {
        if (item == null || item.getType() != Material.BUCKET || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(mobBucketKey, PersistentDataType.BYTE);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!isFeederItem(event.getItemInHand()) || !(event.getBlockPlaced().getState() instanceof Barrel barrel)) return;
        barrel.getPersistentDataContainer().set(feederBlockKey, PersistentDataType.BYTE, (byte) 1);
        barrel.getPersistentDataContainer().set(waterProgressKey, PersistentDataType.INTEGER, 0);
        barrel.getPersistentDataContainer().set(productionDayKey, PersistentDataType.LONG, -1L);
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
            if (item != null && !item.getType().isAir()) event.getBlock().getWorld().dropItemNaturally(location, item.clone());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFeederInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isFeederBlock(event.getClickedBlock())) return;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Barrel barrel) || !isFeederBlock(barrel.getBlock())) return;
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        if (isMobBucket(cursor) || isMobBucket(current)
                || (cursor != null && cursor.getType() == Material.LAVA_BUCKET)
                || (current != null && current.getType() == Material.LAVA_BUCKET)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof Barrel barrel) || !isFeederBlock(barrel.getBlock())) return;
        if (isMobBucket(event.getOldCursor()) || (event.getOldCursor() != null && event.getOldCursor().getType() == Material.LAVA_BUCKET)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMilk(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (isMobBucket(hand) || hand.getType() != Material.BUCKET) return;

        Entity target = event.getRightClicked();
        String animal = getAnimalKey(target);
        if (animal == null || animal.equals("chicken") || !getConfig().getBoolean("milking.animals." + animal, true)) return;

        // Малышей можно поить молоком вручную. Два кормления = взрослое животное.
        if (target instanceof Animals baby && !baby.isAdult()) {
            feedBabyWithMilk(player, baby);
            return;
        }

        long cooldown = Math.max(0, getConfig().getLong("milking.cooldown-seconds", 30)) * 1000L;
        long now = System.currentTimeMillis();
        long last = milkingCooldown.getOrDefault(target.getUniqueId(), 0L);
        if (now - last < cooldown) {
            event.setCancelled(true);
            player.sendMessage(message("prefix") + message("milk-cooldown"));
            return;
        }

        event.setCancelled(true);
        milkingCooldown.put(target.getUniqueId(), now);
        replaceOneMainHandItem(player, new ItemStack(Material.MILK_BUCKET));
        String messageKey = switch (animal) {
            case "cow" -> "milk-cow";
            case "sheep" -> "milk-sheep";
            default -> "milk-goat";
        };
        player.sendMessage(message("prefix") + message(messageKey));
    }

    private void feedBabyWithMilk(Player player, Animals baby) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != Material.MILK_BUCKET || isMobBucket(hand)) return;
        player.getInventory().setItemInMainHand(hand.getAmount() <= 1 ? new ItemStack(Material.BUCKET) : hand);
        if (hand.getAmount() > 1) hand.setAmount(hand.getAmount() - 1);
        addMilkFeed(baby, baby.getWorld().getFullTime() / 24000L);
        player.sendMessage(message("prefix") + message("milk-baby"));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        String animal = getAnimalKey(event.getEntity());
        if (animal == null) return;
        if (animal.equals("chicken")) {
            event.getDrops().removeIf(item -> item.getType() == Material.FEATHER);
            addDrop(event, new ItemStack(Material.FEATHER), getConfig().getInt("drops.chicken.feather.min", 2), getConfig().getInt("drops.chicken.feather.max", 5));
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
        // Ванильные яйца оставляем. Суточная квота автоматически добавляется в кормушку отдельно.
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
                if (!getConfig().getBoolean("hud.enabled", true)) return;
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

        collectDailyProduction(barrel, pen);
        processBabyMilk(barrel, pen);
        processBreeding(barrel, pen);
    }

    private void processBreeding(Barrel barrel, PenStatus pen) {
        long day = barrel.getWorld().getFullTime() / 24000L;
        Map<String, List<Animals>> groups = new HashMap<>();
        for (Animals animal : pen.animals()) {
            if (!animal.isAdult() || !animal.canBreed()) continue;
            String type = getAnimalKey(animal);
            if (type != null && getConfig().getBoolean("feeding." + type + ".enabled", true)) groups.computeIfAbsent(type, ignored -> new ArrayList<>()).add(animal);
        }

        int maxPairs = Math.max(1, getConfig().getInt("feeder.max-breeding-pairs-per-day", 10));
        int pairs = 0;
        for (List<Animals> group : groups.values()) {
            if (group.size() < 2 || pairs >= maxPairs) continue;
            for (int i = 0; i + 1 < group.size() && pairs < maxPairs; i += 2) {
                Animals a = group.get(i);
                Animals b = group.get(i + 1);
                if (getBreedDay(a) >= day || getBreedDay(b) >= day) continue;
                Material foodA = findFood(a, barrel.getInventory());
                Material foodB = findFood(b, barrel.getInventory());
                if (foodA == null || foodB == null) continue;

                Location target = barrel.getLocation().add(0.5, 0.5, 0.5);
                if (a.getLocation().distanceSquared(target) > 6.25) {
                    moveToFeeder(a, target);
                    continue;
                }
                if (b.getLocation().distanceSquared(target) > 6.25) {
                    moveToFeeder(b, target);
                    continue;
                }
                if (!consumeWaterCharge(barrel)) continue;

                removeOneFood(barrel.getInventory(), foodA);
                removeOneFood(barrel.getInventory(), foodB);
                a.setBreed(true);
                b.setBreed(true);
                setBreedDay(a, day);
                setBreedDay(b, day);
                pairs++;
            }
        }
    }

    private void moveToFeeder(Animals animal, Location target) {
        if (animal instanceof Mob mob) mob.getPathfinder().moveTo(target, getConfig().getDouble("feeder.path-speed", 1.1));
    }

    private void collectDailyProduction(Barrel barrel, PenStatus pen) {
        long day = barrel.getWorld().getFullTime() / 24000L;
        long last = barrel.getPersistentDataContainer().getOrDefault(productionDayKey, PersistentDataType.LONG, -1L);
        if (last >= day) return;

        int eggMin = getConfig().getInt("production.chicken.eggs-min", 5);
        int eggMax = getConfig().getInt("production.chicken.eggs-max", 10);
        int milkMin = getConfig().getInt("production.dairy.milk-min", 5);
        int milkMax = getConfig().getInt("production.dairy.milk-max", 10);

        for (Animals animal : pen.animals()) {
            if (!animal.isAdult()) continue;
            String type = getAnimalKey(animal);
            if (type == null) continue;
            if (type.equals("chicken")) {
                int eggs = randomAmount(eggMin, eggMax);
                addToFeeder(barrel.getInventory(), new ItemStack(Material.EGG), eggs);
            } else if (type.equals("cow") || type.equals("sheep") || type.equals("goat")) {
                int milk = randomAmount(milkMin, milkMax);
                addToFeeder(barrel.getInventory(), new ItemStack(Material.MILK_BUCKET), milk);
            }
        }

        barrel.getPersistentDataContainer().set(productionDayKey, PersistentDataType.LONG, day);
        barrel.update(true, false);
    }

    private int randomAmount(int min, int max) {
        min = Math.max(0, min); max = Math.max(min, max);
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private void addToFeeder(Inventory inventory, ItemStack prototype, int amount) {
        int maxStack = prototype.getMaxStackSize();
        while (amount > 0) {
            int add = Math.min(amount, maxStack);
            ItemStack stack = prototype.clone();
            stack.setAmount(add);
            Map<Integer, ItemStack> leftovers = inventory.addItem(stack);
            int left = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
            amount = left;
            if (left > 0) break;
        }
    }

    private void processBabyMilk(Barrel barrel, PenStatus pen) {
        long day = barrel.getWorld().getFullTime() / 24000L;
        for (Animals animal : pen.animals()) {
            if (animal.isAdult() || !isMilkAnimal(animal)) continue;
            if (!contains(barrel.getInventory(), Material.MILK_BUCKET)) continue;
            long lastDay = getMilkFeedDay(animal);
            if (lastDay >= day) continue;

            removeOneFood(barrel.getInventory(), Material.MILK_BUCKET);
            int feeds = getMilkFeedCount(animal) + 1;
            setMilkFeedDay(animal, day);
            setMilkFeedCount(animal, feeds);
            if (feeds >= 2) {
                animal.setAge(0);
                setMilkFeedCount(animal, 0);
            }
        }
    }

    private boolean isMilkAnimal(Entity entity) {
        String key = getAnimalKey(entity);
        return key != null && (key.equals("cow") || key.equals("sheep") || key.equals("goat"));
    }

    private void addMilkFeed(Animals animal, long day) {
        long lastDay = getMilkFeedDay(animal);
        if (lastDay == day) return;
        int feeds = getMilkFeedCount(animal) + 1;
        setMilkFeedDay(animal, day);
        setMilkFeedCount(animal, feeds);
        if (feeds >= 2) {
            animal.setAge(0);
            setMilkFeedCount(animal, 0);
        }
    }

    private int getMilkFeedCount(Animals animal) {
        return animal.getPersistentDataContainer().getOrDefault(milkFeedCountKey, PersistentDataType.INTEGER, 0);
    }
    private void setMilkFeedCount(Animals animal, int value) {
        animal.getPersistentDataContainer().set(milkFeedCountKey, PersistentDataType.INTEGER, value);
    }
    private long getMilkFeedDay(Animals animal) {
        return animal.getPersistentDataContainer().getOrDefault(milkFeedDayKey, PersistentDataType.LONG, -1L);
    }
    private void setMilkFeedDay(Animals animal, long day) {
        animal.getPersistentDataContainer().set(milkFeedDayKey, PersistentDataType.LONG, day);
    }
    private long getBreedDay(Animals animal) {
        return animal.getPersistentDataContainer().getOrDefault(breedDayKey, PersistentDataType.LONG, -1L);
    }
    private void setBreedDay(Animals animal, long day) {
        animal.getPersistentDataContainer().set(breedDayKey, PersistentDataType.LONG, day);
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

    private boolean consumeWaterCharge(Barrel barrel) {
        if (!contains(barrel.getInventory(), Material.WATER_BUCKET)) return true;
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
                + " &7| &eПшеница: " + count(barrel.getInventory(), Material.WHEAT)
                + " &7| &eСемена: " + countSeeds(barrel.getInventory())
                + " &7| &dМолоко: &e" + count(barrel.getInventory(), Material.MILK_BUCKET)
                + " &7| &fЯйца: &e" + count(barrel.getInventory(), Material.EGG));
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
        int startX = feeder.getBlockX(), startZ = feeder.getBlockZ();
        Set<String> inside = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{startX, startZ});
        inside.add(key(startX, startZ));
        int gates = 0; boolean openGate = false, escaped = false;

        while (!queue.isEmpty()) {
            int[] point = queue.removeFirst();
            for (int[] direction : DIRECTIONS) {
                int nx = point[0] + direction[0], nz = point[1] + direction[1];
                if (Math.abs(nx - startX) > radius || Math.abs(nz - startZ) > radius) { escaped = true; continue; }
                Block next = feeder.getWorld().getBlockAt(nx, feeder.getBlockY(), nz);
                if (isGate(next)) {
                    String gateKey = key(nx, nz);
                    if (inside.add(gateKey)) { gates++; if (isOpen(next)) openGate = true; }
                    continue;
                }
                if (isFence(next)) continue;
                if (next.getType().isAir() && inside.add(key(nx, nz))) queue.add(new int[]{nx, nz});
            }
        }

        List<Animals> animals = new ArrayList<>();
        double range = radius + 1.0;
        for (Entity entity : feeder.getWorld().getNearbyEntities(feeder, range, Math.max(3, getConfig().getInt("pen.vertical-range", 5)), range)) {
            if (entity instanceof Animals animal && inside.contains(key(animal.getLocation().getBlockX(), animal.getLocation().getBlockZ()))) animals.add(animal);
        }
        return new PenStatus(!escaped && gates == 1 && !openGate, escaped, gates, openGate, animals);
    }

    private boolean isFence(Block block) {
        String name = block.getType().name();
        return name.endsWith("_FENCE") && !name.endsWith("_FENCE_GATE");
    }
    private boolean isGate(Block block) { return block.getBlockData() instanceof Openable && block.getType().name().endsWith("_FENCE_GATE"); }
    private boolean isOpen(Block block) { return block.getBlockData() instanceof Openable openable && openable.isOpen(); }
    private static final int[][] DIRECTIONS = {{1,0},{-1,0},{0,1},{0,-1}};
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
        if (old.getAmount() <= 1) player.getInventory().setItemInMainHand(replacement);
        else { old.setAmount(old.getAmount() - 1); player.getInventory().setItemInMainHand(old); player.getInventory().addItem(replacement); }
    }

    private record PenStatus(boolean valid, boolean escaped, int gates, boolean openGate, List<Animals> animals) {}
}
