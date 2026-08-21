package ru.dagxam.animalfarm;

import org.bukkit.ChatColor;
import org.bukkit.Chunk;
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
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fish;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Оптимизированная логика AnimalFarm. */
public final class AnimalFarmPlugin extends JavaPlugin implements Listener {
    private NamespacedKey feederItemKey, feederBlockKey, aquariumFeederItemKey, aquariumFeederBlockKey;
    private NamespacedKey nextBreedDayKey, milkDayKey, milkFeedDayKey, milkFeedCountKey, milkFeedRequiredKey;
    private NamespacedKey dailyFeedDayKey, dailyWaterDayKey, productionDayKey;
    private NamespacedKey mobBucketKey;

    private final Set<FeederKey> feeders = ConcurrentHashMap.newKeySet();
    private final Map<FeederKey, CachedArea> areaCache = new ConcurrentHashMap<>();
    private final Map<UUID, String> hudTargets = new ConcurrentHashMap<>();
    private long serverTick;

    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final long AREA_CACHE_TICKS = 40L;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        feederItemKey = new NamespacedKey(this, "feeder_item");
        feederBlockKey = new NamespacedKey(this, "feeder_block");
        aquariumFeederItemKey = new NamespacedKey(this, "aquarium_feeder_item");
        aquariumFeederBlockKey = new NamespacedKey(this, "aquarium_feeder_block");
        nextBreedDayKey = new NamespacedKey(this, "next_breed_day");
        milkDayKey = new NamespacedKey(this, "milk_day");
        milkFeedDayKey = new NamespacedKey(this, "milk_feed_day");
        milkFeedCountKey = new NamespacedKey(this, "milk_feed_count");
        milkFeedRequiredKey = new NamespacedKey(this, "milk_feed_required");
        dailyFeedDayKey = new NamespacedKey(this, "daily_feed_day");
        dailyWaterDayKey = new NamespacedKey(this, "daily_water_day");
        productionDayKey = new NamespacedKey(this, "production_day");
        mobBucketKey = new NamespacedKey(this, "mob_bucket");

        getServer().getPluginManager().registerEvents(this, this);
        AnimalFarmCommand command = new AnimalFarmCommand(this);
        Objects.requireNonNull(getCommand("animalfarm")).setExecutor(command);
        Objects.requireNonNull(getCommand("animalfarm")).setTabCompleter(command);
        registerFeederRecipe();
        registerAquariumFeederRecipe();
        registerLoadedFeedersOnce();
        startTickTask();
        startFeederTask();
        startHudTask();
        startBucketStackTask();
        getLogger().info("AnimalFarm включён. Оптимизировано для Paper 26.2 / Java 26.");
    }

    @Override
    public void onDisable() {
        feeders.clear();
        areaCache.clear();
        hudTargets.clear();
        getLogger().info("AnimalFarm выключен.");
    }

    public void reloadPluginConfig() { reloadConfig(); areaCache.clear(); }
    public String message(String path) { return color(getConfig().getString("messages." + path, "")); }
    private String color(String value) { return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value); }

    private void registerFeederRecipe() {
        NamespacedKey key = new NamespacedKey(this, "feeder");
        getServer().removeRecipe(key);
        ShapedRecipe recipe = new ShapedRecipe(key, createFeederItem());
        recipe.shape("BB", "BB");
        recipe.setIngredient('B', Material.BARREL);
        getServer().addRecipe(recipe);
    }

    private void registerAquariumFeederRecipe() {
        NamespacedKey key = new NamespacedKey(this, "aquarium_feeder");
        getServer().removeRecipe(key);
        ShapedRecipe recipe = new ShapedRecipe(key, createAquariumFeederItem());
        recipe.shape("SS", "SS");
        recipe.setIngredient('S', Material.BOOKSHELF);
        getServer().addRecipe(recipe);
    }

    public ItemStack createFeederItem() {
        ItemStack item = new ItemStack(Material.BARREL);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color("&6Кормушка"));
            meta.setLore(List.of(color("&7Автоматическая кормушка только для наземных животных."), color("&7Для загона нужна одна закрытая калитка.")));
            meta.getPersistentDataContainer().set(feederItemKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public ItemStack createAquariumFeederItem() {
        ItemStack item = new ItemStack(Material.BARREL);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color("&bКормушка для аквариума"));
            meta.setLore(List.of(color("&7Крафтится из 4 обычных книжных полок."), color("&7Достаточно установить одну кормушку внутри аквариума."), color("&7Калитка для аквариума не нужна.")));
            meta.getPersistentDataContainer().set(aquariumFeederItemKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isFeederItem(ItemStack item) {
        if (item == null || item.getType() != Material.BARREL || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(feederItemKey, PersistentDataType.BYTE);
    }

    private boolean isAquariumFeederItem(ItemStack item) {
        if (item == null || item.getType() != Material.BARREL || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(aquariumFeederItemKey, PersistentDataType.BYTE);
    }

    private boolean isFeederBlock(Block block) {
        if (block == null || block.getType() != Material.BARREL || !(block.getState() instanceof Barrel barrel)) return false;
        return barrel.getPersistentDataContainer().has(feederBlockKey, PersistentDataType.BYTE);
    }

    private boolean isAquariumFeederBlock(Block block) {
        if (block == null || block.getType() != Material.BARREL || !(block.getState() instanceof Barrel barrel)) return false;
        return barrel.getPersistentDataContainer().has(aquariumFeederBlockKey, PersistentDataType.BYTE);
    }

    private boolean isAnyFarmFeeder(Block block) {
        return isFeederBlock(block) || isAquariumFeederBlock(block);
    }

    private boolean isMobBucket(ItemStack item) {
        if (item == null || item.getType() != Material.BUCKET || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(mobBucketKey, PersistentDataType.BYTE);
    }

    @EventHandler(ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) { registerFeedersInChunk(event.getChunk()); }

    private void registerLoadedFeedersOnce() {
        for (World world : getServer().getWorlds()) for (Chunk chunk : world.getLoadedChunks()) registerFeedersInChunk(chunk);
    }

    private void registerFeedersInChunk(Chunk chunk) {
        for (BlockState state : chunk.getTileEntities()) if (state instanceof Barrel barrel && isAnyFarmFeeder(barrel.getBlock())) feeders.add(FeederKey.of(barrel.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!(event.getBlockPlaced().getState() instanceof Barrel barrel)) return;
        boolean land = isFeederItem(event.getItemInHand());
        boolean aquarium = isAquariumFeederItem(event.getItemInHand());
        if (!land && !aquarium) return;
        barrel.getPersistentDataContainer().set(land ? feederBlockKey : aquariumFeederBlockKey, PersistentDataType.BYTE, (byte) 1);
        barrel.getPersistentDataContainer().set(dailyFeedDayKey, PersistentDataType.LONG, -1L);
        barrel.getPersistentDataContainer().set(dailyWaterDayKey, PersistentDataType.LONG, -1L);
        barrel.getPersistentDataContainer().set(productionDayKey, PersistentDataType.LONG, -1L);
        barrel.setCustomName(land ? "Кормушка" : "Кормушка для аквариума");
        barrel.update(true, false);
        FeederKey key = FeederKey.of(event.getBlockPlaced().getLocation());
        feeders.add(key);
        areaCache.remove(key);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!isAnyFarmFeeder(event.getBlock())) return;
        FeederKey key = FeederKey.of(event.getBlock().getLocation());
        feeders.remove(key);
        areaCache.remove(key);
        event.setDropItems(false);
        Barrel barrel = (Barrel) event.getBlock().getState();
        Location location = event.getBlock().getLocation();
        event.getBlock().getWorld().dropItemNaturally(location, isAquariumFeederBlock(event.getBlock()) ? createAquariumFeederItem() : createFeederItem());
        for (ItemStack item : barrel.getInventory().getContents()) if (item != null && !item.getType().isAir()) event.getBlock().getWorld().dropItemNaturally(location, item.clone());
    }

    @EventHandler(ignoreCancelled = true)
    public void onFeederInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isAnyFarmFeeder(event.getClickedBlock())) return;
        scheduleNormalize(event.getPlayer().getInventory());
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof Barrel barrel && isAnyFarmFeeder(barrel.getBlock())) {
            if (isMobBucket(event.getCursor()) || isMobBucket(event.getCurrentItem())) event.setCancelled(true);
            ItemStack cursor = event.getCursor(), current = event.getCurrentItem();
            if ((cursor != null && cursor.getType() == Material.LAVA_BUCKET) || (current != null && current.getType() == Material.LAVA_BUCKET)) event.setCancelled(true);
        }
        getServer().getScheduler().runTask(this, () -> normalizeBuckets(event.getWhoClicked().getInventory()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Barrel barrel && isAnyFarmFeeder(barrel.getBlock())) {
            if (isMobBucket(event.getOldCursor()) || (event.getOldCursor() != null && event.getOldCursor().getType() == Material.LAVA_BUCKET)) event.setCancelled(true);
        }
        if (event.getWhoClicked() instanceof Player player) getServer().getScheduler().runTask(this, () -> normalizeBuckets(player.getInventory()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Barrel barrel && isAnyFarmFeeder(barrel.getBlock())) normalizeBuckets(barrel.getInventory());
    }

    private void scheduleNormalize(Inventory inventory) { getServer().getScheduler().runTask(this, () -> normalizeBuckets(inventory)); }

    @EventHandler(ignoreCancelled = true)
    public void onMilk(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Entity target = event.getRightClicked(); Player player = event.getPlayer(); ItemStack hand = player.getInventory().getItemInMainHand();
        if (isMobBucket(hand)) return;
        String animal = getAnimalKey(target);
        if (animal == null || animal.equals("chicken") || animal.equals("horse") || animal.equals("rabbit")) return;
        if (target instanceof Animals baby && !baby.isAdult()) {
            if (!isMilkAnimal(target) || hand.getType() != Material.MILK_BUCKET) return;
            event.setCancelled(true); feedBabyWithMilk(player, baby); return;
        }
        if (!isMilkAnimal(target) || hand.getType() != Material.BUCKET) return;
        event.setCancelled(true);
        long day = target.getWorld().getFullTime() / 24000L;
        long last = target.getPersistentDataContainer().getOrDefault(milkDayKey, PersistentDataType.LONG, -1L);
        if (last >= day) { player.sendMessage(message("prefix") + message("milk-cooldown")); return; }
        target.getPersistentDataContainer().set(milkDayKey, PersistentDataType.LONG, day);
        replaceOneMainHandItem(player, stackableBucket(Material.MILK_BUCKET, 1));
        String key = switch (animal) { case "cow" -> "milk-cow"; case "sheep" -> "milk-sheep"; default -> "milk-goat"; };
        player.sendMessage(message("prefix") + message(key));
    }

    private void feedBabyWithMilk(Player player, Animals baby) {
        long day = baby.getWorld().getFullTime() / 24000L;
        long last = baby.getPersistentDataContainer().getOrDefault(milkFeedDayKey, PersistentDataType.LONG, -1L);
        if (last >= day) { player.sendMessage(message("prefix") + message("milk-baby-once")); return; }
        int required = baby.getPersistentDataContainer().getOrDefault(milkFeedRequiredKey, PersistentDataType.INTEGER, 0);
        if (required <= 0) { required = random(1, 3); baby.getPersistentDataContainer().set(milkFeedRequiredKey, PersistentDataType.INTEGER, required); }
        consumeOneFromHand(player, Material.MILK_BUCKET, Material.BUCKET);
        int count = baby.getPersistentDataContainer().getOrDefault(milkFeedCountKey, PersistentDataType.INTEGER, 0) + 1;
        baby.getPersistentDataContainer().set(milkFeedDayKey, PersistentDataType.LONG, day);
        baby.getPersistentDataContainer().set(milkFeedCountKey, PersistentDataType.INTEGER, count);
        if (count >= required) { baby.setAdult(); baby.getPersistentDataContainer().remove(milkFeedCountKey); baby.getPersistentDataContainer().remove(milkFeedRequiredKey); player.sendMessage(message("prefix") + message("milk-baby-grown")); }
        else player.sendMessage(message("prefix") + message("milk-baby"));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        String animal = getAnimalKey(event.getEntity()); if (animal == null) return;
        ConfigurationSection section = getConfig().getConfigurationSection("drops." + animal); if (section == null) return;
        event.getDrops().clear(); addConfiguredDrop(event, section, "meat", meat(animal));
        if (animal.equals("rabbit")) addConfiguredDrop(event, section, "rabbit-hide", new ItemStack(Material.RABBIT_HIDE));
        else if (!animal.equals("chicken")) addConfiguredDrop(event, section, "leather", new ItemStack(Material.LEATHER));
        addConfiguredDrop(event, section, "bone", new ItemStack(Material.BONE));
        if (animal.equals("sheep")) addConfiguredDrop(event, section, "wool", new ItemStack(Material.WHITE_WOOL));
        if (animal.equals("chicken")) addConfiguredDrop(event, section, "feather", new ItemStack(Material.FEATHER));
    }

    private ItemStack meat(String animal) {
        return switch (animal) { case "cow" -> new ItemStack(Material.BEEF); case "sheep" -> new ItemStack(Material.MUTTON); case "goat" -> named(Material.MUTTON, "&fКозлятина"); case "horse" -> named(Material.BEEF, "&fКонина"); case "rabbit" -> named(Material.RABBIT, "&fКрольчатина"); case "chicken" -> new ItemStack(Material.CHICKEN); default -> new ItemStack(Material.BEEF); };
    }
    private ItemStack named(Material material, String name) { ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta(); if (meta != null) { meta.setDisplayName(color(name)); item.setItemMeta(meta); } return item; }
    private void addConfiguredDrop(EntityDeathEvent event, ConfigurationSection section, String key, ItemStack prototype) { addDrop(event, prototype, section.getInt(key + ".min", 0), section.getInt(key + ".max", section.getInt(key + ".min", 0))); }
    private void addDrop(EntityDeathEvent event, ItemStack prototype, int min, int max) { int amount = random(Math.max(0, min), Math.max(Math.max(0, min), max)); if (amount > 0) { ItemStack drop = prototype.clone(); drop.setAmount(amount); event.getDrops().add(drop); } }

    private void startTickTask() { new BukkitRunnable() { @Override public void run() { serverTick++; if (serverTick % 1200L == 0L) cleanupCaches(); } }.runTaskTimer(this, 1L, 1L); }
    private void cleanupCaches() { areaCache.entrySet().removeIf(e -> !feeders.contains(e.getKey()) || e.getValue().expiresAt < serverTick); }

    private void startFeederTask() {
        long ticks = Math.max(20L, getConfig().getLong("feeder.check-interval-seconds", 5L) * 20L);
        new BukkitRunnable() { @Override public void run() { processRegisteredFeeders(); } }.runTaskTimer(this, ticks, ticks);
    }

    private void startHudTask() {
        new BukkitRunnable() {
            @Override public void run() {
                if (!getConfig().getBoolean("hud.enabled", true)) {
                    clearAllHud();
                    return;
                }
                int range = getConfig().getInt("hud.range", 6);
                for (Player player : getServer().getOnlinePlayers()) {
                    Block target = player.getTargetBlockExact(range);
                    if (target == null || !isAnyFarmFeeder(target) || !(target.getState() instanceof Barrel barrel)) {
                        clearHud(player);
                        continue;
                    }
                    FeederKey key = FeederKey.of(target.getLocation());
                    String id = key.worldId() + ":" + key.x() + ":" + key.y() + ":" + key.z();
                    if (id.equals(hudTargets.get(player.getUniqueId()))) continue;
                    hudTargets.put(player.getUniqueId(), id);
                    player.sendActionBar(formatHud(getArea(target.getLocation()), barrel, isAquariumFeederBlock(target)));
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private void clearHud(Player player) {
        if (hudTargets.remove(player.getUniqueId()) != null) player.sendActionBar("");
    }

    private void clearAllHud() {
        for (Player player : getServer().getOnlinePlayers()) clearHud(player);
    }

    private void startBucketStackTask() {
        new BukkitRunnable() { @Override public void run() { for (Player player : getServer().getOnlinePlayers()) normalizeBuckets(player.getInventory()); } }.runTaskTimer(this, 40L, 40L);
    }

    private void normalizeBuckets(Inventory inventory) { normalizeBucketType(inventory, Material.WATER_BUCKET); normalizeBucketType(inventory, Material.MILK_BUCKET); }
    private void normalizeBucketType(Inventory inventory, Material type) {
        int total = 0; for (ItemStack item : inventory.getContents()) if (item != null && item.getType() == type) total += item.getAmount();
        if (total <= 1) return;
        for (int slot = 0; slot < inventory.getSize(); slot++) { ItemStack item = inventory.getItem(slot); if (item != null && item.getType() == type) inventory.setItem(slot, null); }
        for (int slot = 0; slot < inventory.getSize() && total > 0; slot++) if (inventory.getItem(slot) == null || inventory.getItem(slot).getType().isAir()) { int amount = Math.min(16, total); inventory.setItem(slot, stackableBucket(type, amount)); total -= amount; }
    }
    private ItemStack stackableBucket(Material type, int amount) { ItemStack item = new ItemStack(type, amount); ItemMeta meta = item.getItemMeta(); if (meta != null) { meta.setMaxStackSize(16); item.setItemMeta(meta); } return item; }

    private void processRegisteredFeeders() {
        for (FeederKey key : new ArrayList<>(feeders)) {
            World world = getServer().getWorld(key.worldId());
            if (world == null || !world.isChunkLoaded(key.x() >> 4, key.z() >> 4)) continue;
            Block block = world.getBlockAt(key.x(), key.y(), key.z());
            if (!(block.getState() instanceof Barrel barrel) || !isAnyFarmFeeder(block)) { feeders.remove(key); areaCache.remove(key); continue; }
            processFeeder(barrel, getArea(barrel.getLocation()), isAquariumFeederBlock(block));
        }
    }

    private AreaStatus getArea(Location feeder) {
        FeederKey key = FeederKey.of(feeder); CachedArea cached = areaCache.get(key);
        if (cached != null && cached.expiresAt >= serverTick) return cached.status;
        Block block = feeder.getBlock();
        AreaStatus status = isAquariumFeederBlock(block) ? analyzeAquarium(feeder) : analyzeLandPenFlexible(feeder);
        areaCache.put(key, new CachedArea(status, serverTick + AREA_CACHE_TICKS));
        return status;
    }

    private void processFeeder(Barrel barrel, AreaStatus area, boolean aquariumFeeder) {
        if (!area.valid()) return;
        if (aquariumFeeder) processAquarium(barrel, area); else processLandPen(barrel, area);
    }

    private void processLandPen(Barrel barrel, AreaStatus area) {
        long day = barrel.getWorld().getFullTime() / 24000L; List<Animals> animals = area.animals(); if (animals.isEmpty()) return;
        if (barrel.getPersistentDataContainer().getOrDefault(dailyFeedDayKey, PersistentDataType.LONG, -1L) < day && feedLandGroupsOnce(barrel.getInventory(), animals)) barrel.getPersistentDataContainer().set(dailyFeedDayKey, PersistentDataType.LONG, day);
        if (barrel.getPersistentDataContainer().getOrDefault(dailyWaterDayKey, PersistentDataType.LONG, -1L) < day && consumeOne(barrel.getInventory(), Material.WATER_BUCKET)) { addToInventory(barrel.getInventory(), new ItemStack(Material.BUCKET), 1); barrel.getPersistentDataContainer().set(dailyWaterDayKey, PersistentDataType.LONG, day); }
        collectDailyLandProduction(barrel, area, day); processBreeding(animals, barrel, day); normalizeBuckets(barrel.getInventory()); barrel.update(true, false);
    }

    private boolean feedLandGroupsOnce(Inventory inventory, List<Animals> animals) {
        Map<String, List<Animals>> groups = new HashMap<>();
        for (Animals animal : animals) { String key = getAnimalKey(animal); if (key != null) groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(animal); }
        boolean fed = false;
        for (String animal : groups.keySet()) { Material food = findLandFood(animal, inventory); if (food != null && consumeAmount(inventory, food, dailyFoodAmount(food, animal))) fed = true; }
        return fed;
    }

    private void collectDailyLandProduction(Barrel barrel, AreaStatus area, long day) {
        long last = barrel.getPersistentDataContainer().getOrDefault(productionDayKey, PersistentDataType.LONG, -1L); if (last >= day) return;
        int chickens = 0; for (Animals animal : area.animals()) if (animal.isAdult() && "chicken".equals(getAnimalKey(animal))) chickens++;
        if (chickens >= 2) for (Animals animal : area.animals()) if (animal.isAdult() && "chicken".equals(getAnimalKey(animal))) dropGroundItems(animal.getLocation(), new ItemStack(Material.EGG), random(5, 10));
        barrel.getPersistentDataContainer().set(productionDayKey, PersistentDataType.LONG, day);
    }

    private void processAquarium(Barrel barrel, AreaStatus area) {
        long day = barrel.getWorld().getFullTime() / 24000L; if (area.fish().isEmpty()) return;
        if (barrel.getPersistentDataContainer().getOrDefault(dailyFeedDayKey, PersistentDataType.LONG, -1L) < day) { Material food = findFishFood(barrel.getInventory()); if (food != null && consumeAmount(barrel.getInventory(), food, dailyFoodAmount(food, "fish"))) barrel.getPersistentDataContainer().set(dailyFeedDayKey, PersistentDataType.LONG, day); }
        processFishBreeding(area.fish(), barrel, day); barrel.update(true, false);
    }

    private void processBreeding(List<Animals> animals, Barrel barrel, long day) {
        Map<String, List<Animals>> groups = new HashMap<>();
        for (Animals animal : animals) if (animal.isAdult()) { String type = getAnimalKey(animal); if (type != null) groups.computeIfAbsent(type, ignored -> new ArrayList<>()).add(animal); }
        int limit = Math.max(1, getConfig().getInt("feeder.max-breeding-pairs-per-day", 10)), made = 0;
        for (List<Animals> group : groups.values()) for (int i = 0; i + 1 < group.size() && made < limit; i += 2) {
            Animals a = group.get(i), b = group.get(i + 1); if (!readyForBreed(a, day) || !readyForBreed(b, day)) continue;
            Location spawn = a.getLocation().add(b.getLocation()).multiply(0.5); spawn.setY(Math.max(a.getLocation().getY(), b.getLocation().getY()));
            Entity child = a.getWorld().spawnEntity(spawn, a.getType()); if (child instanceof Ageable ageable) ageable.setBaby(); scheduleNextBreed(a, day); scheduleNextBreed(b, day); made++;
        }
    }

    private void processFishBreeding(List<Fish> fish, Barrel barrel, long day) {
        Map<String, List<Fish>> groups = new HashMap<>(); for (Fish one : fish) groups.computeIfAbsent(one.getType().name(), ignored -> new ArrayList<>()).add(one);
        int limit = Math.max(1, getConfig().getInt("feeder.max-breeding-pairs-per-day", 10)), made = 0;
        for (List<Fish> group : groups.values()) for (int i = 0; i + 1 < group.size() && made < limit; i += 2) {
            Fish a = group.get(i), b = group.get(i + 1); if (!readyForBreed(a, day) || !readyForBreed(b, day)) continue;
            Location spawn = a.getLocation().add(b.getLocation()).multiply(0.5); a.getWorld().spawnEntity(spawn, a.getType()); scheduleNextBreed(a, day); scheduleNextBreed(b, day); made++;
        }
    }

    private boolean readyForBreed(Entity entity, long day) { long next = entity.getPersistentDataContainer().getOrDefault(nextBreedDayKey, PersistentDataType.LONG, -1L); if (next < 0) { scheduleNextBreed(entity, day); return false; } return day >= next; }
    private void scheduleNextBreed(Entity entity, long day) { entity.getPersistentDataContainer().set(nextBreedDayKey, PersistentDataType.LONG, day + random(1, 3)); }

    private Material findLandFood(String animal, Inventory inventory) { for (ItemStack item : inventory.getContents()) if (item != null && !item.getType().isAir() && item.getAmount() > 0 && isLandFoodFor(animal, item.getType())) return item.getType(); return null; }
    private boolean isLandFoodFor(String animal, Material material) {
        if (animal.equals("rabbit")) return material == Material.CARROT || material == Material.GOLDEN_CARROT;
        if (animal.equals("cow")) return material == Material.HAY_BLOCK || material == Material.WHEAT || material == Material.APPLE || material == Material.MELON_SLICE || material == Material.PUMPKIN || material == Material.MELON || isNaturalPlantFood(material);
        if (animal.equals("horse") || animal.equals("sheep") || animal.equals("goat")) return material == Material.HAY_BLOCK || material == Material.WHEAT || isNaturalPlantFood(material);
        return animal.equals("chicken") && (isSeed(material) || isNaturalPlantFood(material));
    }
    private Material findFishFood(Inventory inventory) { for (ItemStack item : inventory.getContents()) if (item != null && item.getAmount() > 0 && (isSeed(item.getType()) || item.getType() == Material.SEAGRASS || item.getType() == Material.KELP || item.getType() == Material.SEA_PICKLE)) return item.getType(); return null; }
    private boolean isNaturalPlantFood(Material material) { String name = material.name(); if (name.endsWith("_LEAVES") || name.contains("FLOWER") || name.endsWith("_BUSH")) return true; return switch (material) { case SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN, DANDELION, POPPY, BLUE_ORCHID, ALLIUM, AZURE_BLUET, RED_TULIP, ORANGE_TULIP, WHITE_TULIP, PINK_TULIP, OXEYE_DAISY, CORNFLOWER, LILY_OF_THE_VALLEY, WITHER_ROSE, TORCHFLOWER, PINK_PETALS, AZALEA, FLOWERING_AZALEA, MOSS_CARPET, SWEET_BERRIES -> true; default -> false; }; }
    private boolean isSeed(Material material) { String name = material.name(); return name.endsWith("_SEEDS") || material == Material.BEETROOT_SEEDS || material == Material.PITCHER_POD || material == Material.TORCHFLOWER_SEEDS; }
    private int dailyFoodAmount(Material material, String animal) { if (isSeed(material)) return random(2, 5); if (material == Material.HAY_BLOCK || material == Material.WHEAT || isNaturalPlantFood(material)) return random(3, 5); return random(2, 4); }
    private boolean isMilkAnimal(Entity entity) { String key = getAnimalKey(entity); return "cow".equals(key) || "sheep".equals(key) || "goat".equals(key); }

    private void dropGroundItems(Location location, ItemStack prototype, int amount) { for (int i = 0; i < amount; i++) { Item item = location.getWorld().dropItemNaturally(location, prototype.clone()); item.setWillAge(false); } }
    private boolean consumeAmount(Inventory inventory, Material material, int amount) { if (count(inventory, material) < amount) return false; for (int i = 0; i < amount; i++) consumeOne(inventory, material); return true; }
    private boolean consumeOne(Inventory inventory, Material material) { for (int slot = 0; slot < inventory.getSize(); slot++) { ItemStack item = inventory.getItem(slot); if (item == null || item.getType() != material) continue; if (item.getAmount() <= 1) inventory.setItem(slot, null); else item.setAmount(item.getAmount() - 1); return true; } return false; }
    private void addToInventory(Inventory inventory, ItemStack prototype, int amount) { int remaining = amount; while (remaining > 0) { ItemStack one = prototype.clone(); one.setAmount(1); if (!inventory.addItem(one).isEmpty()) break; remaining--; } }
    private int count(Inventory inventory, Material material) { int total = 0; for (ItemStack item : inventory.getContents()) if (item != null && item.getType() == material) total += item.getAmount(); return total; }
    private void consumeOneFromHand(Player player, Material expected, Material replacement) { ItemStack hand = player.getInventory().getItemInMainHand(); if (hand.getType() != expected) return; if (hand.getAmount() <= 1) player.getInventory().setItemInMainHand(replacement == Material.BUCKET ? new ItemStack(Material.BUCKET) : new ItemStack(replacement)); else { hand.setAmount(hand.getAmount() - 1); player.getInventory().setItemInMainHand(hand); addToInventory(player.getInventory(), new ItemStack(replacement), 1); } }
    private void replaceOneMainHandItem(Player player, ItemStack replacement) { ItemStack old = player.getInventory().getItemInMainHand(); if (old.getAmount() <= 1) player.getInventory().setItemInMainHand(replacement); else { old.setAmount(old.getAmount() - 1); player.getInventory().setItemInMainHand(old); Map<Integer, ItemStack> leftovers = player.getInventory().addItem(replacement); leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item)); } }

    private String formatHud(AreaStatus status, Barrel barrel, boolean aquariumFeeder) {
        String base = aquariumFeeder ? "&bКормушка для аквариума &7| " : "&6Кормушка &7| ";
        if (!status.valid()) return color(base + "&c" + (aquariumFeeder ? "Аквариум не готов" : "Загон не готов") + " &7| " + reason(status));
        String type = aquariumFeeder ? "&bАквариум активен" : "&aЗагон активен";
        return color(base + type + " &7| &fЖивотных: &e" + status.totalEntities() + " &7| &bВода: &e" + (aquariumFeeder ? "в аквариуме" : count(barrel.getInventory(), Material.WATER_BUCKET)) + " &7| &eКорм: &f" + countFood(barrel.getInventory()));
    }
    private int countFood(Inventory inventory) { int total = 0; for (ItemStack item : inventory.getContents()) if (item != null && (isSeed(item.getType()) || isNaturalPlantFood(item.getType()) || item.getType() == Material.HAY_BLOCK || item.getType() == Material.WHEAT || item.getType() == Material.CARROT || item.getType() == Material.GOLDEN_CARROT || item.getType() == Material.APPLE || item.getType() == Material.MELON_SLICE || item.getType() == Material.PUMPKIN || item.getType() == Material.MELON || item.getType() == Material.SEAGRASS || item.getType() == Material.KELP || item.getType() == Material.SEA_PICKLE)) total += item.getAmount(); return total; }
    private String reason(AreaStatus status) { if (status.aquarium()) return status.waterPresent() ? "стеклянный аквариум не замкнут" : "нужно наполнить аквариум водой"; if (status.escaped()) return "граница не замкнута"; if (status.gates() == 0) return "нужна ровно одна калитка"; if (status.gates() > 1) return "должна быть только одна калитка"; if (status.openGate()) return "калитка должна быть закрыта"; return "загон не готов"; }

    private AreaStatus analyzeLandPenFlexible(Location feeder) {
        AreaStatus best = null;
        for (int offset = -1; offset <= 1; offset++) {
            Location candidate = feeder.clone().add(0, offset, 0);
            AreaStatus status = analyzeLandPen(candidate);
            if (status.valid()) return status;
            if (best == null || status.totalEntities() > best.totalEntities()) best = status;
        }
        return best == null ? analyzeLandPen(feeder) : best;
    }

    private AreaStatus analyzeLandPen(Location feeder) {
        int radius = Math.max(4, getConfig().getInt("pen.max-radius", 16)), sx = feeder.getBlockX(), sz = feeder.getBlockZ(), y = feeder.getBlockY();
        Set<Long> inside = new HashSet<>(); ArrayDeque<int[]> queue = new ArrayDeque<>(); queue.add(new int[]{sx, sz}); inside.add(posKey(sx, sz)); int gates = 0; boolean open = false, escaped = false;
        while (!queue.isEmpty()) { int[] point = queue.removeFirst(); for (int[] direction : DIRECTIONS) { int nx = point[0] + direction[0], nz = point[1] + direction[1]; if (Math.abs(nx - sx) > radius || Math.abs(nz - sz) > radius) { escaped = true; continue; } Block next = feeder.getWorld().getBlockAt(nx, y, nz); if (isGate(next)) { long k = posKey(nx, nz); if (inside.add(k)) { gates++; if (isOpen(next)) open = true; } continue; } if (isFence(next)) continue; if ((next.getType().isAir() || next.getType() == Material.WATER) && inside.add(posKey(nx, nz))) queue.add(new int[]{nx, nz}); } }
        List<Animals> animals = new ArrayList<>(); double range = radius + 1.0; for (Entity entity : feeder.getWorld().getNearbyEntities(feeder, range, Math.max(3, getConfig().getInt("pen.vertical-range", 5)), range)) if (entity instanceof Animals animal && inside.contains(posKey(animal.getLocation().getBlockX(), animal.getLocation().getBlockZ()))) animals.add(animal);
        return new AreaStatus(!escaped && gates == 1 && !open, false, true, escaped, gates, open, animals, List.of());
    }

    private AreaStatus analyzeAquarium(Location feeder) {
        int radius = Math.max(4, getConfig().getInt("aquarium.max-radius", 16));
        AreaStatus best = null;
        for (int offset = -1; offset <= 1; offset++) {
            AreaStatus status = analyzeAquariumAtY(feeder, feeder.getBlockY() + offset, radius);
            if (status.valid()) return status;
            if (best == null || (status.waterPresent() && !best.waterPresent())) best = status;
        }
        return best == null ? new AreaStatus(false, true, false, true, 0, false, List.of(), List.of()) : best;
    }

    private AreaStatus analyzeAquariumAtY(Location feeder, int y, int radius) {
        int sx = feeder.getBlockX(), sz = feeder.getBlockZ();
        int startY = y;
        if (isAnyFarmFeeder(feeder.getWorld().getBlockAt(sx, startY, sz))) {
            if (feeder.getWorld().getBlockAt(sx, startY - 1, sz).getType() == Material.WATER) startY--;
            else if (feeder.getWorld().getBlockAt(sx, startY + 1, sz).getType() == Material.WATER) startY++;
        }
        Set<Long> inside = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{sx, sz});
        inside.add(posKey(sx, sz));
        boolean escaped = false, waterPresent = false;
        while (!queue.isEmpty()) {
            int[] point = queue.removeFirst();
            for (int[] direction : DIRECTIONS) {
                int nx = point[0] + direction[0], nz = point[1] + direction[1];
                if (Math.abs(nx - sx) > radius || Math.abs(nz - sz) > radius) { escaped = true; continue; }
                Block next = feeder.getWorld().getBlockAt(nx, startY, nz);
                if (isGlass(next)) continue;
                if (next.getType() == Material.WATER) {
                    waterPresent = true;
                    if (inside.add(posKey(nx, nz))) queue.add(new int[]{nx, nz});
                    continue;
                }
                escaped = true;
            }
        }
        List<Fish> fish = new ArrayList<>();
        double range = radius + 1.0;
        for (Entity entity : feeder.getWorld().getNearbyEntities(feeder, range, Math.max(3, getConfig().getInt("aquarium.vertical-range", 5)), range)) {
            if (entity instanceof Fish one && inside.contains(posKey(one.getLocation().getBlockX(), one.getLocation().getBlockZ())) && one.getLocation().getBlock().getType() == Material.WATER) fish.add(one);
        }
        return new AreaStatus(!escaped && waterPresent, true, waterPresent, escaped, 0, false, List.of(), fish);
    }

    private boolean isFence(Block block) { String name = block.getType().name(); return name.endsWith("_FENCE") && !name.endsWith("_FENCE_GATE"); }
    private boolean isGate(Block block) { return block.getBlockData() instanceof Openable && block.getType().name().endsWith("_FENCE_GATE"); }
    private boolean isOpen(Block block) { return block.getBlockData() instanceof Openable openable && openable.isOpen(); }
    private boolean isGlass(Block block) { String name = block.getType().name(); return name.endsWith("_GLASS") || name.endsWith("_GLASS_PANE") || block.getType() == Material.GLASS || block.getType() == Material.GLASS_PANE; }
    private String getAnimalKey(Entity entity) { return switch (entity.getType()) { case COW -> "cow"; case SHEEP -> "sheep"; case GOAT -> "goat"; case CHICKEN -> "chicken"; case HORSE -> "horse"; case RABBIT -> "rabbit"; default -> null; }; }
    private long posKey(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    private int random(int min, int max) { int safeMin = Math.max(0, min), safeMax = Math.max(safeMin, max); return ThreadLocalRandom.current().nextInt(safeMin, safeMax + 1); }

    private record FeederKey(UUID worldId, int x, int y, int z) { static FeederKey of(Location location) { return new FeederKey(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ()); } }
    private record CachedArea(AreaStatus status, long expiresAt) {}
    private record AreaStatus(boolean valid, boolean aquarium, boolean waterPresent, boolean escaped, int gates, boolean openGate, List<Animals> animals, List<Fish> fish) { int totalEntities() { return animals.size() + fish.size(); } }
}
