package ru.dagxam.animalfarm;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Arrays;
import java.util.List;

public final class AnimalFarmPlugin extends JavaPlugin {

    private long serverTick;
    private BukkitTask tickTask;

    private FarmSettings settings;
    private FarmAreaAnalyzer areaAnalyzer;
    private FarmObjectManager farmObjectManager;
    private FarmProcessor farmProcessor;
    private FarmTaskScheduler taskScheduler;
    private MilkManager milkManager;
    private FishingManager fishingManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadManagers();
        registerListeners();
        registerCommand();
        registerRecipes();
        startTickTask();
        startFarmTask();
    }

    @Override
    public void onDisable() {
        if (tickTask != null) tickTask.cancel();
        if (taskScheduler != null) taskScheduler.stop();
        if (farmObjectManager != null) farmObjectManager.clear();
        if (areaAnalyzer != null) areaAnalyzer.clear();
    }

    private void loadManagers() {
        settings = new FarmSettings(this);
        areaAnalyzer = new FarmAreaAnalyzer(this, settings);
        farmObjectManager = new FarmObjectManager(this, areaAnalyzer);
        farmObjectManager.registerLoaded();
        farmProcessor = new FarmProcessor(this, settings);
        taskScheduler = new FarmTaskScheduler(this);
        milkManager = new MilkManager(this, settings);
        fishingManager = new FishingManager(this);
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(farmObjectManager, this);
        getServer().getPluginManager().registerEvents(new DropManager(this), this);
        getServer().getPluginManager().registerEvents(milkManager, this);
        getServer().getPluginManager().registerEvents(fishingManager, this);
    }

    private void registerCommand() {
        PluginCommand command = getCommand("animalfarm");
        if (command == null) {
            getLogger().warning("Команда /animalfarm не найдена в plugin.yml");
            return;
        }
        AnimalFarmCommand executor = new AnimalFarmCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    public void reloadPluginConfig() {
        reloadConfig();
        settings = new FarmSettings(this);
        areaAnalyzer = new FarmAreaAnalyzer(this, settings);
        farmObjectManager.setAreaAnalyzer(areaAnalyzer);
        farmObjectManager.clear();
        farmObjectManager.registerLoaded();
        farmProcessor = new FarmProcessor(this, settings);
        milkManager = new MilkManager(this, settings);
        fishingManager = new FishingManager(this);
        restartFarmTask();
    }

    public ItemStack createFeederItem() {
        ItemStack item = new ItemStack(Material.BARREL);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§6Кормушка");
            meta.getPersistentDataContainer().set(
                    new NamespacedKey(this, "feeder_item"),
                    org.bukkit.persistence.PersistentDataType.BYTE,
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
            meta.setDisplayName("§bАквариумная полка");
            meta.getPersistentDataContainer().set(
                    new NamespacedKey(this, "aquarium_shelf_item"),
                    org.bukkit.persistence.PersistentDataType.BYTE,
                    (byte) 1
            );
            item.setItemMeta(meta);
        }
        return item;
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
        taskScheduler.stop();
        startFarmTask();
    }

    private void processFarmObjects() {
        for (FarmObjectKey key : farmObjectManager.objects()) {
            var location = key.location(getServer());
            if (location.getWorld() == null) continue;
            FarmObjectType type = farmObjectManager.typeOf(location.getBlock());
            if (type != null) {
                farmProcessor.process(key, type, serverTick);
            }
        }
    }
}
