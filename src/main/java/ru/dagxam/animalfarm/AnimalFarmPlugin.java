package ru.dagxam.animalfarm;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Основное ядро AnimalFarm.
 * Игровые механики распределены по специализированным менеджерам.
 */
public final class AnimalFarmPlugin extends JavaPlugin {

    private static final int BUCKET_STACK_SIZE = 16;

    private NamespacedKey feederItemKey;
    private NamespacedKey aquariumShelfItemKey;
    private NamespacedKey mobBucketKey;

    private FarmSettings settings;
    private FarmAreaAnalyzer areaAnalyzer;
    private FarmObjectManager farmObjectManager;
    private FarmTaskScheduler taskScheduler;

    private BukkitTask tickTask;
    private BukkitTask hudTask;
    private long serverTick;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        initializeKeys();

        settings = new FarmSettings(getConfig());
        areaAnalyzer = new FarmAreaAnalyzer(settings);
        farmObjectManager = new FarmObjectManager(this, areaAnalyzer);
        taskScheduler = new FarmTaskScheduler(this);

        getServer().getPluginManager().registerEvents(farmObjectManager, this);
        getServer().getPluginManager().registerEvents(new DropManager(this), this);

        AnimalFarmCommand command = new AnimalFarmCommand(this);
        Objects.requireNonNull(getCommand("animalfarm")).setExecutor(command);
        Objects.requireNonNull(getCommand("animalfarm")).setTabCompleter(command);

        registerRecipes();
        getServer().removeRecipe(new NamespacedKey(this, "aquarium_feeder"));

        farmObjectManager.registerLoaded();
        startTickTask();
        startFarmTask();
        startHudTask();

        getLogger().info("AnimalFarm включён. Дублирующие фермерские менеджеры не запускаются.");
    }

    @Override
    public void onDisable() {
        if (tickTask != null) tickTask.cancel();
        if (hudTask != null) hudTask.cancel();
        if (taskScheduler != null) taskScheduler.stop();
        if (farmObjectManager != null) farmObjectManager.clear();
        if (areaAnalyzer != null) areaAnalyzer.clear();
        getLogger().info("AnimalFarm выключен.");
    }

    public void reloadPluginConfig() {
        reloadConfig();
        settings = new FarmSettings(getConfig());
        areaAnalyzer = new FarmAreaAnalyzer(settings);
        if (farmObjectManager != null) {
            farmObjectManager.setAreaAnalyzer(areaAnalyzer);
        }
        restartFarmTask();
    }

    public String message(String path) {
        return color(getConfig().getString("messages." + path, ""));
    }

    public String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    public FarmSettings settings() {
        return settings;
    }

    public FarmAreaAnalyzer areaAnalyzer() {
        return areaAnalyzer;
    }

    public FarmObjectManager farmObjectManager() {
        return farmObjectManager;
    }

    public long serverTick() {
        return serverTick;
    }

    public ItemStack createFeederItem() {
        ItemStack item = new ItemStack(Material.BARREL);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(color("&6Кормушка"));
        meta.setLore(List.of(
                color("&7Автоматическая кормушка для наземных животных."),
                color("&7Работает только с обычным загоном."),
                color("&7Для загона нужна ровно одна закрытая калитка."),
                color("&7Для рыб используйте отдельную &bАквариумную полку&7.")
        ));
        meta.getPersistentDataContainer().set(feederItemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createAquariumShelfItem() {
        ItemStack item = new ItemStack(Material.BARREL);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(color("&bАквариумная полка"));
        meta.setLore(List.of(
                color("&7Полка для крепления к стенке аквариума."),
                color("&7Крафтится из 4 обычных полок любой породы дерева."),
                color("&7Достаточно установить одну полку."),
                color("&7Семена и другая еда для рыб хранятся внутри."),
                color("&7Калитка для аквариума не нужна.")
        ));
        meta.getPersistentDataContainer().set(aquariumShelfItemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createAquariumFeederItem() {
        return createAquariumShelfItem();
    }

    public boolean isMobBucket(ItemStack item) {
        if (item == null || item.getType() != Material.BUCKET || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(mobBucketKey, PersistentDataType.BYTE);
    }

    public void invalidateArea(org.bukkit.Location location) {
        if (areaAnalyzer != null && farmObjectManager != null) {
            FarmObjectKey key = FarmObjectKey.of(location);
            areaAnalyzer.invalidate(key);
        }
    }

    private void initializeKeys() {
        feederItemKey = new NamespacedKey(this, "feeder_item");
        aquariumShelfItemKey = new NamespacedKey(this, "aquarium_shelf_item");
        mobBucketKey = new NamespacedKey(this, "mob_bucket");
    }

    private void registerRecipes() {
        NamespacedKey feederKey = new NamespacedKey(this, "feeder");
        getServer().removeRecipe(feederKey);
        ShapedRecipe feederRecipe = new ShapedRecipe(feederKey, createFeederItem());
        feederRecipe.shape("BB", "BB");
        feederRecipe.setIngredient('B', Material.BARREL);
        getServer().addRecipe(feederRecipe);

        NamespacedKey shelfKey = new NamespacedKey(this, "aquarium_shelf");
        getServer().removeRecipe(shelfKey);
        List<Material> shelves = Arrays.stream(Material.values())
                .filter(material -> material.name().endsWith("_SHELF"))
                .toList();
        if (!shelves.isEmpty()) {
            ShapedRecipe shelfRecipe = new ShapedRecipe(shelfKey, createAquariumShelfItem());
            shelfRecipe.shape("SS", "SS");
            shelfRecipe.setIngredient('S', new RecipeChoice.MaterialChoice(shelves));
            getServer().addRecipe(shelfRecipe);
        }
    }

    private void startTickTask() {
        tickTask = getServer().getScheduler().runTaskTimer(this, () -> {
            serverTick++;
        }, 1L, 1L);
    }

    private void startFarmTask() {
        taskScheduler.startFarmTask(this::processFarmObjects, settings.feederCheckIntervalTicks());
    }

    private void restartFarmTask() {
        if (taskScheduler != null) {
            taskScheduler.restartFarmTask(this::processFarmObjects, settings.feederCheckIntervalTicks());
        }
    }

    private void processFarmObjects() {
        if (!getConfig().getBoolean("feeder.enabled", true)) return;

        for (FarmObjectKey key : farmObjectManager.objects()) {
            FarmObjectType type;
            try {
                org.bukkit.Location location = key.location(getServer());
                type = farmObjectManager.typeOf(location.getBlock());
                if (type == null) {
                    farmObjectManager.remove(key);
                    continue;
                }

                FarmAreaCache area = areaAnalyzer.analyze(key, type, serverTick, getServer());
                if (!area.valid()) continue;

                processObject(location, type);
            } catch (IllegalStateException ignored) {
                // Мир выгружен; объект останется в списке и будет проверен после загрузки.
            }
        }
    }

    private void processObject(org.bukkit.Location location, FarmObjectType type) {
        if (!(location.getBlock().getState() instanceof Barrel barrel)) return;
        if (type == FarmObjectType.LAND_FEEDER) {
            processLandFeeder(barrel, location);
        } else {
            processAquariumShelf(barrel, location);
        }
    }

    private void processLandFeeder(Barrel barrel, org.bukkit.Location location) {
        long day = location.getWorld().getFullTime() / 24000L;
        processDailyLandFeedAndWater(barrel, day);
        processDailyLandProduction(barrel, day);
        barrel.update(true, false);
    }

    private void processAquariumShelf(Barrel barrel, org.bukkit.Location location) {
        long day = location.getWorld().getFullTime() / 24000L;
        processDailyFishFeed(barrel, day);
        barrel.update(true, false);
    }

    private void processDailyLandFeedAndWater(Barrel barrel, long day) {
        // Основная обработка кормления/размножения постепенно переносится в отдельные процессоры.
        // Здесь пока оставлен только единый тикер без дополнительных world-wide scans.
        barrel.getPersistentDataContainer().set(
                new NamespacedKey(this, "last_farm_process_day"),
                PersistentDataType.LONG,
                day
        );
    }

    private void processDailyLandProduction(Barrel barrel, long day) {
        // Не создаём вторую систему производства: старые production-manager'ы удалены.
        // Производственные механики будут жить в едином FarmProcessor.
    }

    private void processDailyFishFeed(Barrel barrel, long day) {
        // Единый тикер для аквариума; отдельного world-wide FishFarmManager больше нет.
    }

    private void startHudTask() {
        hudTask = getServer().getScheduler().runTaskTimer(this, () -> {
            if (!getConfig().getBoolean("hud.enabled", true)) return;
            // HUD будет обслуживаться специализированным менеджером при дальнейшем разбиении.
        }, 5L, 5L);
    }
}
