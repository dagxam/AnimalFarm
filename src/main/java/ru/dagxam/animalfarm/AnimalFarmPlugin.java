package ru.dagxam.animalfarm;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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

/** Основное ядро AnimalFarm. Игровые механики распределены по специализированным менеджерам. */
public final class AnimalFarmPlugin extends JavaPlugin {

    private NamespacedKey feederItemKey;
    private NamespacedKey aquariumShelfItemKey;
    private NamespacedKey mobBucketKey;

    private FarmSettings settings;
    private FarmAreaAnalyzer areaAnalyzer;
    private FarmObjectManager farmObjectManager;
    private FarmTaskScheduler taskScheduler;
    private FarmProcessor farmProcessor;
    private MilkManager milkManager;

    private BukkitTask tickTask;
    private long serverTick;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        initializeKeys();
        createManagers();

        getServer().getPluginManager().registerEvents(farmObjectManager, this);
        getServer().getPluginManager().registerEvents(new DropManager(this), this);
        getServer().getPluginManager().registerEvents(milkManager, this);

        AnimalFarmCommand command = new AnimalFarmCommand(this);
        Objects.requireNonNull(getCommand("animalfarm")).setExecutor(command);
        Objects.requireNonNull(getCommand("animalfarm")).setTabCompleter(command);

        registerRecipes();
        getServer().removeRecipe(new NamespacedKey(this, "aquarium_feeder"));

        farmObjectManager.registerLoaded();
        startTickTask();
        startFarmTask();

        getLogger().info("AnimalFarm включён. Единый процессор фермы активен.");
    }

    @Override
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (taskScheduler != null) taskScheduler.stop();
        if (farmObjectManager != null) farmObjectManager.clear();
        if (areaAnalyzer != null) areaAnalyzer.clear();
        getLogger().info("AnimalFarm выключен.");
    }

    /** Перезагружает только конфигурацию и зависимые объекты без повторной регистрации слушателей. */
    public void reloadPluginConfig() {
        reloadConfig();
        settings = new FarmSettings(getConfig());
        areaAnalyzer = new FarmAreaAnalyzer(settings);
        farmObjectManager.setAreaAnalyzer(areaAnalyzer);
        farmProcessor = new FarmProcessor(this, settings);
        milkManager.setSettings(settings);
        restartFarmTask();
        farmObjectManager.registerLoaded();
    }

    private void createManagers() {
        settings = new FarmSettings(getConfig());
        areaAnalyzer = new FarmAreaAnalyzer(settings);
        farmObjectManager = new FarmObjectManager(this, areaAnalyzer);
        taskScheduler = new FarmTaskScheduler(this);
        farmProcessor = new FarmProcessor(this, settings);
        milkManager = new MilkManager(this, settings);
    }

    public String message(String path) {
        return color(getConfig().getString("messages." + path, ""));
    }

    public String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    public FarmSettings settings() { return settings; }
    public FarmAreaAnalyzer areaAnalyzer() { return areaAnalyzer; }
    public FarmObjectManager farmObjectManager() { return farmObjectManager; }
    public long serverTick() { return serverTick; }

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
                color("&7Полка для автоматического разведения рыб."),
                color("&7Семена и другая еда для рыб хранятся внутри.")
        ));
        meta.getPersistentDataContainer().set(aquariumShelfItemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createAquariumFeederItem() { return createAquariumShelfItem(); }

    public boolean isMobBucket(ItemStack item) {
        if (item == null || item.getType() != Material.BUCKET || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(mobBucketKey, PersistentDataType.BYTE);
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
        tickTask = getServer().getScheduler().runTaskTimer(this, () -> serverTick++, 1L, 1L);
    }

    private void startFarmTask() {
        taskScheduler.startFarmTask(this::processFarmObjects, settings.feederCheckIntervalTicks());
    }

    private void restartFarmTask() {
        taskScheduler.restartFarmTask(this::processFarmObjects, settings.feederCheckIntervalTicks());
    }

    private void processFarmObjects() {
        if (!getConfig().getBoolean("feeder.enabled", true)) return;

        for (FarmObjectKey key : farmObjectManager.objects()) {
            try {
                org.bukkit.Location location = key.location(getServer());
                FarmObjectType type = farmObjectManager.typeOf(location.getBlock());
                if (type == null) {
                    farmObjectManager.remove(key);
                    continue;
                }
                FarmAreaCache area = areaAnalyzer.analyze(key, type, serverTick, getServer());
                if (area.valid()) farmProcessor.process(key, type, serverTick);
            } catch (IllegalStateException ignored) {
                // Мир временно не загружен.
            }
        }
    }
}
