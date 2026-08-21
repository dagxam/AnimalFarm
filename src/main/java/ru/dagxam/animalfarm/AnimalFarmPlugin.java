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
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * AnimalFarm:
 * - обычная кормушка работает только с наземными животными;
 * - аквариумная полка является отдельным объектом;
 * - одна аквариумная полка обслуживает один замкнутый аквариум;
 * - крафт полки: 2x2 из любых деревянных заборов;
 * - еда рыб хранится непосредственно в аквариумной полке;
 * - ведра воды/молока складываются до 16 без фонового спама пакетами.
 */
public final class AnimalFarmPlugin extends JavaPlugin implements Listener {

    private NamespacedKey feederItemKey;
    private NamespacedKey feederBlockKey;

    private NamespacedKey aquariumShelfItemKey;
    private NamespacedKey aquariumShelfBlockKey;

    private NamespacedKey nextBreedDayKey;
    private NamespacedKey milkDayKey;
    private NamespacedKey milkFeedDayKey;
    private NamespacedKey milkFeedCountKey;
    private NamespacedKey milkFeedRequiredKey;
    private NamespacedKey dailyFeedDayKey;
    private NamespacedKey dailyWaterDayKey;
    private NamespacedKey productionDayKey;
    private NamespacedKey mobBucketKey;

    private final Set<FeederKey> farmObjects = ConcurrentHashMap.newKeySet();
    private final Map<FeederKey, CachedArea> areaCache = new ConcurrentHashMap<>();
    private final Set<UUID> hudVisible = ConcurrentHashMap.newKeySet();
    private final Map<UUID, FeederKey> hudTargets = new ConcurrentHashMap<>();
    private final Map<UUID, Long> hudLastSeenTick = new ConcurrentHashMap<>();
    private final Map<UUID, Long> hudLastRefreshTick = new ConcurrentHashMap<>();
    private final Set<UUID> pendingPlayerBucketNormalize = ConcurrentHashMap.newKeySet();

    private long serverTick;

    private static final int[][] DIRECTIONS = {
            {1, 0},
            {-1, 0},
            {0, 1},
            {0, -1}
    };

    private static final long AREA_CACHE_TICKS = 40L;
    private static final int BUCKET_STACK_SIZE = 16;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        feederItemKey = new NamespacedKey(this, "feeder_item");
        feederBlockKey = new NamespacedKey(this, "feeder_block");

        aquariumShelfItemKey = new NamespacedKey(this, "aquarium_shelf_item");
        aquariumShelfBlockKey = new NamespacedKey(this, "aquarium_shelf_block");

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
        registerAquariumShelfRecipe();

        // Убираем старый конфликтующий рецепт аквариумной кормушки.
        getServer().removeRecipe(new NamespacedKey(this, "aquarium_feeder"));

        registerLoadedFarmObjects();

        startTickTask();
        startFarmTask();
        startHudTask();

        getLogger().info("AnimalFarm включён. Кормушка и аквариумная полка работают отдельно.");
        getLogger().info("Аквариумная полка: крафт 2x2 из любых обычных полок.");
        getLogger().info("Стопки воды и молока: до " + BUCKET_STACK_SIZE + ".");
    }

    @Override
    public void onDisable() {
        clearAllHud();
        farmObjects.clear();
        areaCache.clear();
        hudVisible.clear();
        hudTargets.clear();
        hudLastSeenTick.clear();
        hudLastRefreshTick.clear();
        pendingPlayerBucketNormalize.clear();

        getLogger().info("AnimalFarm выключен.");
    }

    public void reloadPluginConfig() {
        reloadConfig();
        areaCache.clear();
    }

    public String message(String path) {
        return color(getConfig().getString("messages." + path, ""));
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    /* ============================================================
       КРАФТЫ
       ============================================================ */

    private void registerFeederRecipe() {
        NamespacedKey key = new NamespacedKey(this, "feeder");
        getServer().removeRecipe(key);

        ShapedRecipe recipe = new ShapedRecipe(key, createFeederItem());
        recipe.shape(
                "BB",
                "BB"
        );
        recipe.setIngredient('B', Material.BARREL);

        getServer().addRecipe(recipe);
    }

    private void registerAquariumShelfRecipe() {
        NamespacedKey key = new NamespacedKey(this, "aquarium_shelf");
        getServer().removeRecipe(key);

        List<Material> woodenShelves = Arrays.stream(Material.values())
                .filter(this::isWoodenShelfMaterial)
                .toList();

        if (woodenShelves.isEmpty()) {
            getLogger().warning("Не найдены обычные полки для крафта аквариумной полки.");
            return;
        }

        ShapedRecipe recipe = new ShapedRecipe(key, createAquariumShelfItem());

        /*
         * 4 обычные полки:
         *
         * S S
         * S S
         *
         * Каждый S может быть полкой из любой породы дерева,
         * включая смешанные породы дерева.
         */
        recipe.shape(
                "SS",
                "SS"
        );

        recipe.setIngredient(
                'S',
                new RecipeChoice.MaterialChoice(woodenShelves)
        );

        getServer().addRecipe(recipe);
    }

    private boolean isWoodenShelfMaterial(Material material) {
        // Используем все обычные полки из всех пород дерева.
        // Имена определяются по Material, поэтому код не привязан к одной породе.
        return material.name().endsWith("_SHELF");
    }

    /* ============================================================
       ПРЕДМЕТЫ
       ============================================================ */

    public ItemStack createFeederItem() {
        ItemStack item = new ItemStack(Material.BARREL);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(color("&6Кормушка"));

            meta.setLore(List.of(
                    color("&7Автоматическая кормушка для наземных животных."),
                    color("&7Работает только с обычным загоном."),
                    color("&7Для загона нужна ровно одна закрытая калитка."),
                    color("&7Для рыб используйте отдельную &bАквариумную полку&7.")
            ));

            meta.getPersistentDataContainer().set(
                    feederItemKey,
                    PersistentDataType.BYTE,
                    (byte) 1
            );

            item.setItemMeta(meta);
        }

        return item;
    }

    public ItemStack createAquariumShelfItem() {
        ItemStack item = new ItemStack(Material.BARREL);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(color("&bАквариумная полка"));

            meta.setLore(List.of(
                    color("&7Полка для крепления к стенке аквариума."),
                    color("&7Крафтится из 4 обычных полок любой породы дерева."),
                    color("&7Достаточно установить одну полку."),
                    color("&7Семена и другая еда для рыб хранятся внутри."),
                    color("&7Калитка для аквариума не нужна.")
            ));

            meta.getPersistentDataContainer().set(
                    aquariumShelfItemKey,
                    PersistentDataType.BYTE,
                    (byte) 1
            );

            item.setItemMeta(meta);
        }

        return item;
    }

    /**
     * Совместимость со старой командой/другими классами.
     * Теперь старое имя возвращает новую отдельную аквариумную полку.
     */
    public ItemStack createAquariumFeederItem() {
        return createAquariumShelfItem();
    }

    private boolean isFeederItem(ItemStack item) {
        return hasKey(item, feederItemKey);
    }

    private boolean isAquariumShelfItem(ItemStack item) {
        return hasKey(item, aquariumShelfItemKey);
    }

    private boolean hasKey(ItemStack item, NamespacedKey key) {
        if (item == null || item.getType() != Material.BARREL || !item.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();

        return meta != null
                && meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private boolean isFeederBlock(Block block) {
        return hasBlockKey(block, feederBlockKey);
    }

    private boolean isAquariumShelfBlock(Block block) {
        return hasBlockKey(block, aquariumShelfBlockKey);
    }

    private boolean hasBlockKey(Block block, NamespacedKey key) {
        if (block == null
                || block.getType() != Material.BARREL
                || !(block.getState() instanceof Barrel barrel)) {
            return false;
        }

        return barrel.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private boolean isAnyFarmObject(Block block) {
        return isFeederBlock(block) || isAquariumShelfBlock(block);
    }

    private boolean isMobBucket(ItemStack item) {
        if (item == null || item.getType() != Material.BUCKET || !item.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();

        return meta != null
                && meta.getPersistentDataContainer().has(mobBucketKey, PersistentDataType.BYTE);
    }

    /* ============================================================
       РЕГИСТРАЦИЯ И УСТАНОВКА ОБЪЕКТОВ
       ============================================================ */

    @EventHandler(ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        registerFarmObjectsInChunk(event.getChunk());
    }

    private void registerLoadedFarmObjects() {
        for (World world : getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                registerFarmObjectsInChunk(chunk);
            }
        }
    }

    private void registerFarmObjectsInChunk(Chunk chunk) {
        for (BlockState state : chunk.getTileEntities()) {
            if (state instanceof Barrel barrel && isAnyFarmObject(barrel.getBlock())) {
                farmObjects.add(FeederKey.of(barrel.getLocation()));
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!(event.getBlockPlaced().getState() instanceof Barrel barrel)) {
            return;
        }

        boolean landFeeder = isFeederItem(event.getItemInHand());
        boolean aquariumShelf = isAquariumShelfItem(event.getItemInHand());

        if (!landFeeder && !aquariumShelf) {
            return;
        }

        if (landFeeder) {
            barrel.getPersistentDataContainer().set(
                    feederBlockKey,
                    PersistentDataType.BYTE,
                    (byte) 1
            );

            barrel.setCustomName("Кормушка");
        } else {
            barrel.getPersistentDataContainer().set(
                    aquariumShelfBlockKey,
                    PersistentDataType.BYTE,
                    (byte) 1
            );

            barrel.setCustomName("Аквариумная полка");
        }

        barrel.getPersistentDataContainer().set(
                dailyFeedDayKey,
                PersistentDataType.LONG,
                -1L
        );

        barrel.getPersistentDataContainer().set(
                dailyWaterDayKey,
                PersistentDataType.LONG,
                -1L
        );

        barrel.getPersistentDataContainer().set(
                productionDayKey,
                PersistentDataType.LONG,
                -1L
        );

        barrel.update(true, false);

        FeederKey key = FeederKey.of(event.getBlockPlaced().getLocation());

        farmObjects.add(key);
        areaCache.remove(key);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();

        if (!isAnyFarmObject(block)) {
            return;
        }

        boolean aquariumShelf = isAquariumShelfBlock(block);

        FeederKey key = FeederKey.of(block.getLocation());

        farmObjects.remove(key);
        areaCache.remove(key);

        Barrel barrel = (Barrel) block.getState();
        Location location = block.getLocation();

        event.setDropItems(false);

        block.getWorld().dropItemNaturally(
                location,
                aquariumShelf
                        ? createAquariumShelfItem()
                        : createFeederItem()
        );

        for (ItemStack item : barrel.getInventory().getContents()) {
            if (item != null && !item.getType().isAir()) {
                block.getWorld().dropItemNaturally(
                        location,
                        item.clone()
                );
            }
        }
    }

    /* ============================================================
       ИНВЕНТАРИ И ВЕДРА
       ============================================================ */

    @EventHandler(ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getInventory().getHolder() instanceof Barrel barrel
                && isAnyFarmObject(barrel.getBlock())) {

            // Один раз при открытии приводим ведра в хранилище к стопкам до 16.
            // Это позволяет Shift-кликом сразу забрать стопку из 16.
            normalizeBuckets(event.getInventory());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof Barrel barrel
                && isAnyFarmObject(barrel.getBlock())) {

            ItemStack cursor = event.getCursor();
            ItemStack current = event.getCurrentItem();

            if (isMobBucket(cursor) || isMobBucket(current)) {
                event.setCancelled(true);
                return;
            }

            if (isLavaBucket(cursor) || isLavaBucket(current)) {
                event.setCancelled(true);
                return;
            }
        }

        /*
         * Нормализация игрока запускается только при Shift-клике по воде/молоку
         * и только один раз за тик. На обычные быстрые клики вообще ничего
         * не планируется, поэтому старый packet-rate flood устранён.
         */
        if (event.isShiftClick()
                && event.getWhoClicked() instanceof Player player
                && isStackableBucket(event.getCurrentItem())) {

            schedulePlayerBucketNormalize(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof Barrel barrel)
                || !isAnyFarmObject(barrel.getBlock())) {
            return;
        }

        ItemStack cursor = event.getOldCursor();

        if (isMobBucket(cursor) || isLavaBucket(cursor)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Barrel barrel
                && isAnyFarmObject(barrel.getBlock())) {

            normalizeBuckets(event.getInventory());
        }
    }

    private boolean isLavaBucket(ItemStack item) {
        return item != null && item.getType() == Material.LAVA_BUCKET;
    }

    private boolean isStackableBucket(ItemStack item) {
        if (item == null) {
            return false;
        }

        return item.getType() == Material.WATER_BUCKET
                || item.getType() == Material.MILK_BUCKET;
    }

    private void schedulePlayerBucketNormalize(Player player) {
        UUID uuid = player.getUniqueId();

        if (!pendingPlayerBucketNormalize.add(uuid)) {
            return;
        }

        getServer().getScheduler().runTask(this, () -> {
            try {
                if (player.isOnline()) {
                    normalizeBuckets(player.getInventory());
                }
            } finally {
                pendingPlayerBucketNormalize.remove(uuid);
            }
        });
    }

    private void normalizeBuckets(Inventory inventory) {
        normalizeBucketType(inventory, Material.WATER_BUCKET);
        normalizeBucketType(inventory, Material.MILK_BUCKET);
    }

    private void normalizeBucketType(Inventory inventory, Material type) {
        int total = 0;

        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() == type) {
                total += item.getAmount();
            }
        }

        if (total <= 1) {
            return;
        }

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);

            if (item != null && item.getType() == type) {
                inventory.setItem(slot, null);
            }
        }

        for (int slot = 0; slot < inventory.getSize() && total > 0; slot++) {
            ItemStack current = inventory.getItem(slot);

            if (current != null && !current.getType().isAir()) {
                continue;
            }

            int amount = Math.min(BUCKET_STACK_SIZE, total);

            inventory.setItem(
                    slot,
                    stackableBucket(type, amount)
            );

            total -= amount;
        }
    }

    private ItemStack stackableBucket(Material type, int amount) {
        ItemStack item = new ItemStack(
                type,
                Math.min(BUCKET_STACK_SIZE, Math.max(1, amount))
        );

        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setMaxStackSize(BUCKET_STACK_SIZE);
            item.setItemMeta(meta);
        }

        return item;
    }

    /* ============================================================
       ДОЕНИЕ И МОЛОКО
       ============================================================ */

    @EventHandler(ignoreCancelled = true)
    public void onMilk(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Entity target = event.getRightClicked();
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();

        if (isMobBucket(hand)) {
            return;
        }

        String animal = getAnimalKey(target);

        if (animal == null
                || animal.equals("chicken")
                || animal.equals("horse")
                || animal.equals("rabbit")) {
            return;
        }

        if (target instanceof Animals baby && !baby.isAdult()) {
            if (!isMilkAnimal(target)
                    || hand.getType() != Material.MILK_BUCKET) {
                return;
            }

            event.setCancelled(true);
            feedBabyWithMilk(player, baby);
            return;
        }

        if (!isMilkAnimal(target)
                || hand.getType() != Material.BUCKET) {
            return;
        }

        event.setCancelled(true);

        long day = target.getWorld().getFullTime() / 24000L;

        long last = target.getPersistentDataContainer().getOrDefault(
                milkDayKey,
                PersistentDataType.LONG,
                -1L
        );

        if (last >= day) {
            player.sendMessage(
                    message("prefix") + message("milk-cooldown")
            );
            return;
        }

        target.getPersistentDataContainer().set(
                milkDayKey,
                PersistentDataType.LONG,
                day
        );

        replaceOneMainHandItem(
                player,
                stackableBucket(Material.MILK_BUCKET, 1)
        );

        String messageKey = switch (animal) {
            case "cow" -> "milk-cow";
            case "sheep" -> "milk-sheep";
            default -> "milk-goat";
        };

        player.sendMessage(
                message("prefix") + message(messageKey)
        );
    }

    private void feedBabyWithMilk(Player player, Animals baby) {
        long day = baby.getWorld().getFullTime() / 24000L;

        long last = baby.getPersistentDataContainer().getOrDefault(
                milkFeedDayKey,
                PersistentDataType.LONG,
                -1L
        );

        if (last >= day) {
            player.sendMessage(
                    message("prefix") + message("milk-baby-once")
            );
            return;
        }

        int required = baby.getPersistentDataContainer().getOrDefault(
                milkFeedRequiredKey,
                PersistentDataType.INTEGER,
                0
        );

        if (required <= 0) {
            required = random(1, 3);

            baby.getPersistentDataContainer().set(
                    milkFeedRequiredKey,
                    PersistentDataType.INTEGER,
                    required
            );
        }

        consumeOneFromHand(
                player,
                Material.MILK_BUCKET,
                Material.BUCKET
        );

        int count = baby.getPersistentDataContainer().getOrDefault(
                milkFeedCountKey,
                PersistentDataType.INTEGER,
                0
        ) + 1;

        baby.getPersistentDataContainer().set(
                milkFeedDayKey,
                PersistentDataType.LONG,
                day
        );

        baby.getPersistentDataContainer().set(
                milkFeedCountKey,
                PersistentDataType.INTEGER,
                count
        );

        if (count >= required) {
            baby.setAdult();

            baby.getPersistentDataContainer().remove(
                    milkFeedCountKey
            );

            baby.getPersistentDataContainer().remove(
                    milkFeedRequiredKey
            );

            player.sendMessage(
                    message("prefix") + message("milk-baby-grown")
            );
        } else {
            player.sendMessage(
                    message("prefix") + message("milk-baby")
            );
        }
    }

    /* ============================================================
       ДРОПЫ
       ============================================================ */

    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        String animal = getAnimalKey(event.getEntity());

        if (animal == null) {
            return;
        }

        ConfigurationSection section = getConfig()
                .getConfigurationSection("drops." + animal);

        if (section == null) {
            return;
        }

        event.getDrops().clear();

        addConfiguredDrop(
                event,
                section,
                "meat",
                meat(animal)
        );

        if (animal.equals("rabbit")) {
            addConfiguredDrop(
                    event,
                    section,
                    "rabbit-hide",
                    new ItemStack(Material.RABBIT_HIDE)
            );
        } else if (!animal.equals("chicken")) {
            addConfiguredDrop(
                    event,
                    section,
                    "leather",
                    new ItemStack(Material.LEATHER)
            );
        }

        addConfiguredDrop(
                event,
                section,
                "bone",
                new ItemStack(Material.BONE)
        );

        if (animal.equals("sheep")) {
            addConfiguredDrop(
                    event,
                    section,
                    "wool",
                    new ItemStack(Material.WHITE_WOOL)
            );
        }

        if (animal.equals("chicken")) {
            addConfiguredDrop(
                    event,
                    section,
                    "feather",
                    new ItemStack(Material.FEATHER)
            );
        }
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
            meta.setDisplayName(color(name));
            item.setItemMeta(meta);
        }

        return item;
    }

    private void addConfiguredDrop(
            EntityDeathEvent event,
            ConfigurationSection section,
            String key,
            ItemStack prototype
    ) {
        int min = section.getInt(key + ".min", 0);
        int max = section.getInt(key + ".max", min);

        addDrop(event, prototype, min, max);
    }

    private void addDrop(
            EntityDeathEvent event,
            ItemStack prototype,
            int min,
            int max
    ) {
        int amount = random(
                Math.max(0, min),
                Math.max(Math.max(0, min), max)
        );

        if (amount <= 0) {
            return;
        }

        ItemStack drop = prototype.clone();
        drop.setAmount(amount);

        event.getDrops().add(drop);
    }

    /* ============================================================
       ФОНОВЫЕ ЗАДАЧИ
       ============================================================ */

    private void startTickTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                serverTick++;

                if (serverTick % 1200L == 0L) {
                    cleanupCaches();
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private void cleanupCaches() {
        areaCache.entrySet().removeIf(entry ->
                !farmObjects.contains(entry.getKey())
                        || entry.getValue().expiresAt < serverTick
        );
    }

    private void startFarmTask() {
        long ticks = Math.max(
                20L,
                getConfig().getLong(
                        "feeder.check-interval-seconds",
                        5L
                ) * 20L
        );

        new BukkitRunnable() {
            @Override
            public void run() {
                processRegisteredFarmObjects();
            }
        }.runTaskTimer(this, ticks, ticks);
    }

    private void startHudTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!getConfig().getBoolean("hud.enabled", true)) {
                    clearAllHud();
                    return;
                }

                int range = Math.max(1, getConfig().getInt("hud.range", 6));

                for (Player player : getServer().getOnlinePlayers()) {
                    UUID uuid = player.getUniqueId();
                    Block target = player.getTargetBlockExact(range);

                    if (target == null
                            || !isAnyFarmObject(target)
                            || !(target.getState() instanceof Barrel barrel)) {

                        clearHudIfCursorReallyLeft(player);
                        continue;
                    }

                    FeederKey targetKey = FeederKey.of(target.getLocation());

                    // Пока игрок продолжает смотреть на ту же кормушку/полку,
                    // HUD вообще НЕ отправляется повторно.
                    // Поэтому обычная кормушка не моргает и показывает
                    // последнюю информацию до следующего наведения.
                    if (targetKey.equals(hudTargets.get(uuid))) {
                        hudLastSeenTick.put(uuid, serverTick);
                        continue;
                    }

                    boolean aquariumShelf = isAquariumShelfBlock(target);
                    AreaStatus area = getArea(target.getLocation());

                    if (aquariumShelf) {
                        player.sendActionBar(
                                formatAquariumHud(area, barrel)
                        );
                    } else {
                        player.sendActionBar(
                                formatHud(area, barrel, false)
                        );
                    }

                    hudVisible.add(uuid);
                    hudTargets.put(uuid, targetKey);
                    hudLastSeenTick.put(uuid, serverTick);
                    hudLastRefreshTick.put(uuid, serverTick);
                }
            }
        }.runTaskTimer(this, 1L, 5L);
    }

    private boolean isFish(EntityType type) {
        return switch (type) {
            case COD, SALMON, PUFFERFISH, TROPICAL_FISH -> true;
            default -> false;
        };
    }

    private String formatAquariumHud(
            AreaStatus status,
            Barrel shelf
    ) {
        int fishCount = (int) status.animals().stream()
                .filter(entity -> isFish(entity.getType()))
                .count();

        int foodCount = 0;

        for (ItemStack item : shelf.getInventory().getContents()) {
            if (item != null && item.getAmount() > 0) {
                foodCount += item.getAmount();
            }
        }

        return color(
                "&bАквариумная полка"
                        + " &7| &fРыб: &e" + fishCount
                        + " &7| &fКорма: &a" + foodCount
        );
    }

    private void clearHudIfCursorReallyLeft(Player player) {
        UUID uuid = player.getUniqueId();

        if (!hudVisible.contains(uuid)) {
            return;
        }

        long lastSeen = hudLastSeenTick.getOrDefault(uuid, Long.MIN_VALUE);

        // Короткая задержка защищает от ложного исчезновения,
        // когда прицел на мгновение теряет блок.
        if (serverTick - lastSeen >= 10L) {
            clearHud(player);
        }
    }

    private void clearHud(Player player) {
        UUID uuid = player.getUniqueId();

        hudVisible.remove(uuid);
        hudTargets.remove(uuid);
        hudLastSeenTick.remove(uuid);
        hudLastRefreshTick.remove(uuid);

        player.sendActionBar("");
    }

    private void clearAllHud() {
        for (Player player : getServer().getOnlinePlayers()) {
            clearHud(player);
        }
    }

    /* ============================================================
       ОБРАБОТКА КОРМУШКИ И АКВАРИУМНОЙ ПОЛКИ
       ============================================================ */

    private void processRegisteredFarmObjects() {
        for (FeederKey key : new ArrayList<>(farmObjects)) {
            World world = getServer().getWorld(key.worldId());

            if (world == null
                    || !world.isChunkLoaded(
                    key.x() >> 4,
                    key.z() >> 4
            )) {
                continue;
            }

            Block block = world.getBlockAt(
                    key.x(),
                    key.y(),
                    key.z()
            );

            if (!(block.getState() instanceof Barrel barrel)
                    || !isAnyFarmObject(block)) {

                farmObjects.remove(key);
                areaCache.remove(key);
                continue;
            }

            boolean aquariumShelf = isAquariumShelfBlock(block);
            AreaStatus area = getArea(block.getLocation());

            if (!area.valid()) {
                continue;
            }

            if (aquariumShelf) {
                processAquariumShelf(barrel, area);
            } else {
                processLandFeeder(barrel, area);
            }
        }
    }

    private AreaStatus getArea(Location location) {
        FeederKey key = FeederKey.of(location);
        CachedArea cached = areaCache.get(key);

        if (cached != null
                && cached.expiresAt >= serverTick) {
            return cached.status;
        }

        Block block = location.getBlock();

        AreaStatus status = isAquariumShelfBlock(block)
                ? analyzeAquarium(location)
                : analyzeLandPenFlexible(location);

        areaCache.put(
                key,
                new CachedArea(
                        status,
                        serverTick + AREA_CACHE_TICKS
                )
        );

        return status;
    }

    private void processLandFeeder(
            Barrel barrel,
            AreaStatus area
    ) {
        long day = barrel.getWorld().getFullTime() / 24000L;

        List<Animals> animals = area.animals();

        if (animals.isEmpty()) {
            return;
        }

        long lastFeedDay =
                barrel.getPersistentDataContainer().getOrDefault(
                        dailyFeedDayKey,
                        PersistentDataType.LONG,
                        -1L
                );

        if (lastFeedDay < day
                && feedLandGroupsOnce(
                barrel.getInventory(),
                animals
        )) {
            barrel.getPersistentDataContainer().set(
                    dailyFeedDayKey,
                    PersistentDataType.LONG,
                    day
            );
        }

        long lastWaterDay =
                barrel.getPersistentDataContainer().getOrDefault(
                        dailyWaterDayKey,
                        PersistentDataType.LONG,
                        -1L
                );

        if (lastWaterDay < day
                && consumeOne(
                barrel.getInventory(),
                Material.WATER_BUCKET
        )) {
            addToInventory(
                    barrel.getInventory(),
                    new ItemStack(Material.BUCKET),
                    1
            );

            barrel.getPersistentDataContainer().set(
                    dailyWaterDayKey,
                    PersistentDataType.LONG,
                    day
            );
        }

        collectDailyLandProduction(
                barrel,
                area,
                day
        );

        processBreeding(
                animals,
                barrel,
                day
        );

        normalizeBuckets(barrel.getInventory());

        barrel.update(true, false);
    }

    private void processAquariumShelf(
            Barrel shelf,
            AreaStatus area
    ) {
        if (area.fish().isEmpty()) {
            return;
        }

        long day = shelf.getWorld().getFullTime() / 24000L;

        long lastFeedDay =
                shelf.getPersistentDataContainer().getOrDefault(
                        dailyFeedDayKey,
                        PersistentDataType.LONG,
                        -1L
                );

        /*
         * Рыбы берут еду непосредственно из аквариумной полки.
         * Подходят семена, морская трава, ламинария и морские огурцы.
         */
        if (lastFeedDay < day) {
            Material food = findFishFood(shelf.getInventory());

            if (food != null) {
                int consumed = consumeUpTo(
                        shelf.getInventory(),
                        food,
                        dailyFoodAmount(food, "fish")
                );

                if (consumed > 0) {
                    shelf.getPersistentDataContainer().set(
                            dailyFeedDayKey,
                            PersistentDataType.LONG,
                            day
                    );
                }
            }
        }

        processFishBreeding(
                area.fish(),
                shelf,
                day
        );

        shelf.update(true, false);
    }

    private boolean feedLandGroupsOnce(
            Inventory inventory,
            List<Animals> animals
    ) {
        Map<String, List<Animals>> groups = new HashMap<>();

        for (Animals animal : animals) {
            String key = getAnimalKey(animal);

            if (key != null) {
                groups.computeIfAbsent(
                        key,
                        ignored -> new ArrayList<>()
                ).add(animal);
            }
        }

        boolean fed = false;

        for (String animal : groups.keySet()) {
            Material food = findLandFood(
                    animal,
                    inventory
            );

            if (food != null) {
                int consumed = consumeUpTo(
                        inventory,
                        food,
                        dailyFoodAmount(food, animal)
                );

                if (consumed > 0) {
                    fed = true;
                }
            }
        }

        return fed;
    }

    private void collectDailyLandProduction(
            Barrel barrel,
            AreaStatus area,
            long day
    ) {
        long last = barrel.getPersistentDataContainer().getOrDefault(
                productionDayKey,
                PersistentDataType.LONG,
                -1L
        );

        if (last >= day) {
            return;
        }

        int chickens = 0;

        for (Animals animal : area.animals()) {
            if (animal.isAdult()
                    && "chicken".equals(getAnimalKey(animal))) {
                chickens++;
            }
        }

        if (chickens >= 2) {
            for (Animals animal : area.animals()) {
                if (animal.isAdult()
                        && "chicken".equals(getAnimalKey(animal))) {

                    dropGroundItems(
                            animal.getLocation(),
                            new ItemStack(Material.EGG),
                            random(5, 10)
                    );
                }
            }
        }

        barrel.getPersistentDataContainer().set(
                productionDayKey,
                PersistentDataType.LONG,
                day
        );
    }

    /* ============================================================
       РАЗМНОЖЕНИЕ
       ============================================================ */

    private void processBreeding(
            List<Animals> animals,
            Barrel barrel,
            long day
    ) {
        Map<String, List<Animals>> groups = new HashMap<>();

        for (Animals animal : animals) {
            if (!animal.isAdult()) {
                continue;
            }

            String type = getAnimalKey(animal);

            if (type != null) {
                groups.computeIfAbsent(
                        type,
                        ignored -> new ArrayList<>()
                ).add(animal);
            }
        }

        int limit = Math.max(
                1,
                getConfig().getInt(
                        "feeder.max-breeding-pairs-per-day",
                        10
                )
        );

        int made = 0;

        for (List<Animals> group : groups.values()) {
            for (int i = 0;
                 i + 1 < group.size() && made < limit;
                 i += 2) {

                Animals first = group.get(i);
                Animals second = group.get(i + 1);

                if (!readyForBreed(first, day)
                        || !readyForBreed(second, day)) {
                    continue;
                }

                Location spawn = first.getLocation()
                        .add(second.getLocation())
                        .multiply(0.5);

                spawn.setY(
                        Math.max(
                                first.getLocation().getY(),
                                second.getLocation().getY()
                        )
                );

                Entity child = first.getWorld().spawnEntity(
                        spawn,
                        first.getType()
                );

                if (child instanceof Ageable ageable) {
                    ageable.setBaby();
                }

                scheduleNextBreed(first, day);
                scheduleNextBreed(second, day);

                made++;
            }
        }
    }

    private void processFishBreeding(
            List<Fish> fish,
            Barrel shelf,
            long day
    ) {
        Map<String, List<Fish>> groups = new HashMap<>();

        for (Fish one : fish) {
            groups.computeIfAbsent(
                    one.getType().name(),
                    ignored -> new ArrayList<>()
            ).add(one);
        }

        int limit = Math.max(
                1,
                getConfig().getInt(
                        "feeder.max-breeding-pairs-per-day",
                        10
                )
        );

        int made = 0;

        for (List<Fish> group : groups.values()) {
            for (int i = 0;
                 i + 1 < group.size() && made < limit;
                 i += 2) {

                Fish first = group.get(i);
                Fish second = group.get(i + 1);

                if (!readyForBreed(first, day)
                        || !readyForBreed(second, day)) {
                    continue;
                }

                Location spawn = first.getLocation()
                        .add(second.getLocation())
                        .multiply(0.5);

                first.getWorld().spawnEntity(
                        spawn,
                        first.getType()
                );

                scheduleNextBreed(first, day);
                scheduleNextBreed(second, day);

                made++;
            }
        }
    }

    private boolean readyForBreed(
            Entity entity,
            long day
    ) {
        long next = entity.getPersistentDataContainer().getOrDefault(
                nextBreedDayKey,
                PersistentDataType.LONG,
                -1L
        );

        if (next < 0) {
            scheduleNextBreed(entity, day);
            return false;
        }

        return day >= next;
    }

    private void scheduleNextBreed(
            Entity entity,
            long day
    ) {
        entity.getPersistentDataContainer().set(
                nextBreedDayKey,
                PersistentDataType.LONG,
                day + random(1, 3)
        );
    }

    /* ============================================================
       ЕДА
       ============================================================ */

    private Material findLandFood(
            String animal,
            Inventory inventory
    ) {
        for (ItemStack item : inventory.getContents()) {
            if (item == null
                    || item.getType().isAir()
                    || item.getAmount() <= 0) {
                continue;
            }

            if (isLandFoodFor(
                    animal,
                    item.getType()
            )) {
                return item.getType();
            }
        }

        return null;
    }

    private boolean isLandFoodFor(
            String animal,
            Material material
    ) {
        if (animal.equals("rabbit")) {
            return material == Material.CARROT
                    || material == Material.GOLDEN_CARROT;
        }

        if (animal.equals("cow")) {
            return material == Material.HAY_BLOCK
                    || material == Material.WHEAT
                    || material == Material.APPLE
                    || material == Material.MELON_SLICE
                    || material == Material.PUMPKIN
                    || material == Material.MELON
                    || isNaturalPlantFood(material);
        }

        if (animal.equals("horse")
                || animal.equals("sheep")
                || animal.equals("goat")) {

            return material == Material.HAY_BLOCK
                    || material == Material.WHEAT
                    || isNaturalPlantFood(material);
        }

        return animal.equals("chicken")
                && (
                isSeed(material)
                        || isNaturalPlantFood(material)
        );
    }

    private Material findFishFood(Inventory inventory) {
        for (ItemStack item : inventory.getContents()) {
            if (item == null
                    || item.getAmount() <= 0) {
                continue;
            }

            Material material = item.getType();

            if (isSeed(material)
                    || material == Material.SEAGRASS
                    || material == Material.KELP
                    || material == Material.SEA_PICKLE) {

                return material;
            }
        }

        return null;
    }

    private boolean isNaturalPlantFood(Material material) {
        String name = material.name();

        if (name.endsWith("_LEAVES")
                || name.contains("FLOWER")
                || name.endsWith("_BUSH")) {
            return true;
        }

        return switch (material) {
            case SHORT_GRASS,
                 TALL_GRASS,
                 FERN,
                 LARGE_FERN,
                 DANDELION,
                 POPPY,
                 BLUE_ORCHID,
                 ALLIUM,
                 AZURE_BLUET,
                 RED_TULIP,
                 ORANGE_TULIP,
                 WHITE_TULIP,
                 PINK_TULIP,
                 OXEYE_DAISY,
                 CORNFLOWER,
                 LILY_OF_THE_VALLEY,
                 WITHER_ROSE,
                 TORCHFLOWER,
                 PINK_PETALS,
                 AZALEA,
                 FLOWERING_AZALEA,
                 MOSS_CARPET,
                 SWEET_BERRIES -> true;

            default -> false;
        };
    }

    private boolean isSeed(Material material) {
        String name = material.name();

        return name.endsWith("_SEEDS")
                || material == Material.BEETROOT_SEEDS
                || material == Material.PITCHER_POD
                || material == Material.TORCHFLOWER_SEEDS;
    }

    private int dailyFoodAmount(
            Material material,
            String animal
    ) {
        if (isSeed(material)) {
            return random(2, 5);
        }

        if (material == Material.HAY_BLOCK
                || material == Material.WHEAT
                || isNaturalPlantFood(material)) {
            return random(3, 5);
        }

        return random(2, 4);
    }

    private boolean isMilkAnimal(Entity entity) {
        String key = getAnimalKey(entity);

        return "cow".equals(key)
                || "sheep".equals(key)
                || "goat".equals(key);
    }

    /* ============================================================
       ИНВЕНТАРНЫЕ ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
       ============================================================ */

    private void dropGroundItems(
            Location location,
            ItemStack prototype,
            int amount
    ) {
        for (int i = 0; i < amount; i++) {
            Item item = location.getWorld().dropItemNaturally(
                    location,
                    prototype.clone()
            );

            item.setWillAge(false);
        }
    }

    private int consumeUpTo(
            Inventory inventory,
            Material material,
            int requestedAmount
    ) {
        if (inventory == null || material == null || requestedAmount <= 0) {
            return 0;
        }

        int remaining = requestedAmount;
        int consumed = 0;

        for (int slot = 0;
             slot < inventory.getSize() && remaining > 0;
             slot++) {

            ItemStack item = inventory.getItem(slot);

            if (item == null
                    || item.getType() != material
                    || item.getAmount() <= 0) {
                continue;
            }

            int take = Math.min(item.getAmount(), remaining);

            if (take >= item.getAmount()) {
                inventory.setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - take);
                inventory.setItem(slot, item);
            }

            remaining -= take;
            consumed += take;
        }

        return consumed;
    }

    private boolean consumeAmount(
            Inventory inventory,
            Material material,
            int amount
    ) {
        if (count(inventory, material) < amount) {
            return false;
        }

        for (int i = 0; i < amount; i++) {
            consumeOne(inventory, material);
        }

        return true;
    }

    private boolean consumeOne(
            Inventory inventory,
            Material material
    ) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);

            if (item == null
                    || item.getType() != material) {
                continue;
            }

            if (item.getAmount() <= 1) {
                inventory.setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - 1);
            }

            return true;
        }

        return false;
    }

    private void addToInventory(
            Inventory inventory,
            ItemStack prototype,
            int amount
    ) {
        int remaining = amount;

        while (remaining > 0) {
            ItemStack one = prototype.clone();
            one.setAmount(1);

            if (!inventory.addItem(one).isEmpty()) {
                break;
            }

            remaining--;
        }
    }

    private int count(
            Inventory inventory,
            Material material
    ) {
        int total = 0;

        for (ItemStack item : inventory.getContents()) {
            if (item != null
                    && item.getType() == material) {
                total += item.getAmount();
            }
        }

        return total;
    }

    private void consumeOneFromHand(
            Player player,
            Material expected,
            Material replacement
    ) {
        ItemStack hand = player.getInventory().getItemInMainHand();

        if (hand.getType() != expected) {
            return;
        }

        if (hand.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(
                    new ItemStack(replacement)
            );
            return;
        }

        hand.setAmount(hand.getAmount() - 1);
        player.getInventory().setItemInMainHand(hand);

        addToInventory(
                player.getInventory(),
                new ItemStack(replacement),
                1
        );
    }

    private void replaceOneMainHandItem(
            Player player,
            ItemStack replacement
    ) {
        ItemStack old = player.getInventory().getItemInMainHand();

        if (old.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(replacement);
            return;
        }

        old.setAmount(old.getAmount() - 1);
        player.getInventory().setItemInMainHand(old);

        Map<Integer, ItemStack> leftovers =
                player.getInventory().addItem(replacement);

        leftovers.values().forEach(item ->
                player.getWorld().dropItemNaturally(
                        player.getLocation(),
                        item
                )
        );
    }

    /* ============================================================
       HUD
       ============================================================ */

    private String formatHud(
            AreaStatus status,
            Barrel barrel,
            boolean aquariumShelf
    ) {
        if (aquariumShelf) {
            return color("&bАквариумная полка");
        }

        String base = "&6Кормушка &7| ";

        if (!status.valid()) {
            return color(
                    base
                            + "&c"
                            + (
                            aquariumShelf
                                    ? "Аквариум не готов"
                                    : "Загон не готов"
                    )
                            + " &7| "
                            + reason(status)
            );
        }

        String type = aquariumShelf
                ? "&bАквариум активен"
                : "&aЗагон активен";

        String water = aquariumShelf
                ? "в аквариуме"
                : String.valueOf(
                count(
                        barrel.getInventory(),
                        Material.WATER_BUCKET
                )
        );

        return color(
                base
                        + type
                        + " &7| &fЖивотных: &e"
                        + status.totalEntities()
                        + " &7| &bВода: &e"
                        + water
                        + " &7| &eКорм: &f"
                        + countFood(barrel.getInventory())
        );
    }

    private int countFood(Inventory inventory) {
        int total = 0;

        for (ItemStack item : inventory.getContents()) {
            if (item == null) {
                continue;
            }

            Material material = item.getType();

            if (isSeed(material)
                    || isNaturalPlantFood(material)
                    || material == Material.HAY_BLOCK
                    || material == Material.WHEAT
                    || material == Material.CARROT
                    || material == Material.GOLDEN_CARROT
                    || material == Material.APPLE
                    || material == Material.MELON_SLICE
                    || material == Material.PUMPKIN
                    || material == Material.MELON
                    || material == Material.SEAGRASS
                    || material == Material.KELP
                    || material == Material.SEA_PICKLE) {

                total += item.getAmount();
            }
        }

        return total;
    }

    private String reason(AreaStatus status) {
        if (status.aquarium()) {
            return status.waterPresent()
                    ? "стеклянный аквариум не замкнут"
                    : "нужно наполнить аквариум водой";
        }

        if (status.escaped()) {
            return "граница не замкнута";
        }

        if (status.gates() == 0) {
            return "нужна ровно одна калитка";
        }

        if (status.gates() > 1) {
            return "должна быть только одна калитка";
        }

        if (status.openGate()) {
            return "калитка должна быть закрыта";
        }

        return "загон не готов";
    }

    /* ============================================================
       АНАЛИЗ ЗАГОНА: -1 / 0 / +1
       ============================================================ */

    private AreaStatus analyzeLandPenFlexible(Location feeder) {
        AreaStatus best = null;

        for (int offset = -1; offset <= 1; offset++) {
            int y = feeder.getBlockY() + offset;

            AreaStatus status = analyzeLandPenAtY(
                    feeder,
                    y
            );

            if (status.valid()) {
                return status;
            }

            if (best == null
                    || status.totalEntities() > best.totalEntities()
                    || (
                    status.gates() > best.gates()
                            && !status.escaped()
                    )) {
                best = status;
            }
        }

        return best == null
                ? analyzeLandPenAtY(
                feeder,
                feeder.getBlockY()
        )
                : best;
    }

    private AreaStatus analyzeLandPenAtY(
            Location feeder,
            int y
    ) {
        int radius = Math.max(
                4,
                getConfig().getInt(
                        "pen.max-radius",
                        16
                )
        );

        int sx = feeder.getBlockX();
        int sz = feeder.getBlockZ();

        Set<Long> inside = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();

        queue.add(new int[]{sx, sz});
        inside.add(posKey(sx, sz));

        int gates = 0;
        boolean open = false;
        boolean escaped = false;

        while (!queue.isEmpty()) {
            int[] point = queue.removeFirst();

            for (int[] direction : DIRECTIONS) {
                int nx = point[0] + direction[0];
                int nz = point[1] + direction[1];

                if (Math.abs(nx - sx) > radius
                        || Math.abs(nz - sz) > radius) {

                    escaped = true;
                    continue;
                }

                BoundaryInfo boundary = getLandBoundaryInfo(
                        feeder.getWorld(),
                        nx,
                        y,
                        nz
                );

                if (boundary.fence()) {
                    continue;
                }

                if (boundary.gate()) {
                    long gateKey = posKey(nx, nz);

                    if (inside.add(gateKey)) {
                        gates++;

                        if (boundary.open()) {
                            open = true;
                        }
                    }

                    continue;
                }

                Block next = feeder.getWorld().getBlockAt(
                        nx,
                        y,
                        nz
                );

                if (!isPenPassable(next)) {
                    escaped = true;
                    continue;
                }

                if (inside.add(posKey(nx, nz))) {
                    queue.add(new int[]{nx, nz});
                }
            }
        }

        List<Animals> animals = new ArrayList<>();

        double range = radius + 1.0;

        int verticalRange = Math.max(
                3,
                getConfig().getInt(
                        "pen.vertical-range",
                        5
                )
        );

        Location center = new Location(
                feeder.getWorld(),
                sx + 0.5,
                y + 0.5,
                sz + 0.5
        );

        for (Entity entity : feeder.getWorld().getNearbyEntities(
                center,
                range,
                verticalRange,
                range
        )) {
            if (!(entity instanceof Animals animal)) {
                continue;
            }

            int animalX = animal.getLocation().getBlockX();
            int animalZ = animal.getLocation().getBlockZ();

            if (inside.contains(posKey(animalX, animalZ))) {
                animals.add(animal);
            }
        }

        return new AreaStatus(
                !escaped && gates == 1 && !open,
                false,
                true,
                escaped,
                gates,
                open,
                animals,
                List.of()
        );
    }

    private BoundaryInfo getLandBoundaryInfo(
            World world,
            int x,
            int baseY,
            int z
    ) {
        boolean fence = false;
        boolean gate = false;
        boolean open = false;

        /*
         * Проверяем границу сразу на уровнях -1 / 0 / +1.
         * Поэтому кормушка может стоять на один блок выше или ниже
         * уровня самого загона.
         */
        for (int offset = -1; offset <= 1; offset++) {
            Block block = world.getBlockAt(
                    x,
                    baseY + offset,
                    z
            );

            if (isGate(block)) {
                gate = true;

                if (isOpen(block)) {
                    open = true;
                }

                continue;
            }

            if (isFence(block)) {
                fence = true;
            }
        }

        return new BoundaryInfo(
                fence,
                gate,
                open
        );
    }

    private boolean isPenPassable(Block block) {
        Material type = block.getType();

        return type.isAir()
                || type == Material.WATER
                || type == Material.TALL_GRASS
                || type == Material.SHORT_GRASS
                || type == Material.FERN
                || type == Material.LARGE_FERN;
    }

    /* ============================================================
       АНАЛИЗ АКВАРИУМА: ОТДЕЛЬНО ОТ КОРМУШКИ
       ============================================================ */

    private AreaStatus analyzeAquarium(Location shelf) {
        int radius = Math.max(
                4,
                getConfig().getInt(
                        "aquarium.max-radius",
                        16
                )
        );

        AreaStatus best = null;

        for (int offset = -1; offset <= 1; offset++) {
            AreaStatus status = analyzeAquariumAtY(
                    shelf,
                    shelf.getBlockY() + offset,
                    radius
            );

            if (status.valid()) {
                return status;
            }

            if (best == null
                    || (
                    status.waterPresent()
                            && !best.waterPresent()
                    )
                    || status.totalEntities()
                    > best.totalEntities()) {
                best = status;
            }
        }

        return best == null
                ? new AreaStatus(
                false,
                true,
                false,
                true,
                0,
                false,
                List.of(),
                List.of()
        )
                : best;
    }

    private AreaStatus analyzeAquariumAtY(
            Location shelf,
            int waterY,
            int radius
    ) {
        World world = shelf.getWorld();

        int sx = shelf.getBlockX();
        int sz = shelf.getBlockZ();

        Set<Long> inside = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();

        /*
         * Стартуем от одной установленной аквариумной полки.
         * Сама полка является допустимой стартовой точкой,
         * далее область распространяется только через воду.
         */
        queue.add(new int[]{sx, sz});
        inside.add(posKey(sx, sz));

        boolean escaped = false;
        boolean waterPresent = false;

        while (!queue.isEmpty()) {
            int[] point = queue.removeFirst();

            for (int[] direction : DIRECTIONS) {
                int nx = point[0] + direction[0];
                int nz = point[1] + direction[1];

                if (Math.abs(nx - sx) > radius
                        || Math.abs(nz - sz) > radius) {

                    escaped = true;
                    continue;
                }

                Block next = world.getBlockAt(
                        nx,
                        waterY,
                        nz
                );

                if (isGlassBoundary(
                        world,
                        nx,
                        waterY,
                        nz
                )) {
                    continue;
                }

                if (next.getType() == Material.WATER) {
                    waterPresent = true;

                    if (inside.add(posKey(nx, nz))) {
                        queue.add(new int[]{nx, nz});
                    }

                    continue;
                }

                escaped = true;
            }
        }

        List<Fish> fish = new ArrayList<>();

        double range = radius + 1.0;

        int verticalRange = Math.max(
                3,
                getConfig().getInt(
                        "aquarium.vertical-range",
                        5
                )
        );

        Location center = new Location(
                world,
                sx + 0.5,
                waterY + 0.5,
                sz + 0.5
        );

        for (Entity entity : world.getNearbyEntities(
                center,
                range,
                verticalRange,
                range
        )) {
            if (!(entity instanceof Fish one)) {
                continue;
            }

            int fishX = one.getLocation().getBlockX();
            int fishZ = one.getLocation().getBlockZ();

            if (inside.contains(posKey(fishX, fishZ))
                    && one.getLocation().getBlock().getType()
                    == Material.WATER) {

                fish.add(one);
            }
        }

        return new AreaStatus(
                !escaped && waterPresent,
                true,
                waterPresent,
                escaped,
                0,
                false,
                List.of(),
                fish
        );
    }

    private boolean isGlassBoundary(
            World world,
            int x,
            int baseY,
            int z
    ) {
        /*
         * Стекло также допускается на -1 / 0 / +1,
         * чтобы полка могла стоять не строго на одном уровне с водой.
         */
        for (int offset = -1; offset <= 1; offset++) {
            if (isGlass(
                    world.getBlockAt(
                            x,
                            baseY + offset,
                            z
                    )
            )) {
                return true;
            }
        }

        return false;
    }

    /* ============================================================
       ТИПЫ БЛОКОВ И УТИЛИТЫ
       ============================================================ */

    private boolean isFence(Block block) {
        String name = block.getType().name();

        return name.endsWith("_FENCE")
                && !name.endsWith("_FENCE_GATE");
    }

    private boolean isGate(Block block) {
        return block.getBlockData() instanceof Openable
                && block.getType().name()
                .endsWith("_FENCE_GATE");
    }

    private boolean isOpen(Block block) {
        return block.getBlockData() instanceof Openable openable
                && openable.isOpen();
    }

    private boolean isGlass(Block block) {
        String name = block.getType().name();

        return name.endsWith("_GLASS")
                || name.endsWith("_GLASS_PANE")
                || block.getType() == Material.GLASS
                || block.getType() == Material.GLASS_PANE;
    }

    private String getAnimalKey(Entity entity) {
        return switch (entity.getType()) {
            case COW -> "cow";
            case SHEEP -> "sheep";
            case GOAT -> "goat";
            case CHICKEN -> "chicken";
            case HORSE -> "horse";
            case RABBIT -> "rabbit";
            default -> null;
        };
    }

    private long posKey(int x, int z) {
        return ((long) x << 32)
                ^ (z & 0xffffffffL);
    }

    private int random(int min, int max) {
        int safeMin = Math.max(0, min);
        int safeMax = Math.max(safeMin, max);

        return ThreadLocalRandom.current().nextInt(
                safeMin,
                safeMax + 1
        );
    }

    private record FeederKey(
            UUID worldId,
            int x,
            int y,
            int z
    ) {
        static FeederKey of(Location location) {
            return new FeederKey(
                    location.getWorld().getUID(),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            );
        }
    }

    private record CachedArea(
            AreaStatus status,
            long expiresAt
    ) {
    }

    private record BoundaryInfo(
            boolean fence,
            boolean gate,
            boolean open
    ) {
    }

    private record AreaStatus(
            boolean valid,
            boolean aquarium,
            boolean waterPresent,
            boolean escaped,
            int gates,
            boolean openGate,
            List<Animals> animals,
            List<Fish> fish
    ) {
        int totalEntities() {
            return animals.size() + fish.size();
        }
    }
}
