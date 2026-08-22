package ru.dagxam.animalfarm;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class AnimalFarmPlugin extends JavaPlugin {
    private long serverTick;
    private BukkitTask tickTask;
    private FarmSettings settings;
    private FarmAreaAnalyzer areaAnalyzer;
    private FarmObjectManager farmObjectManager;
    private FarmOwnershipManager ownershipManager;
    private FarmProcessor farmProcessor;
    private FarmTaskScheduler taskScheduler;
    private MilkManager milkManager;
    private FishingManager fishingManager;
    private FarmHudManager hudManager;

    @Override public void onEnable() {
        saveDefaultConfig();
        loadManagers();
        registerListeners();
        registerCommand();
        registerRecipes();
        startTickTask();
        startFarmTask();
    }

    @Override public void onDisable() {
        if (tickTask != null) tickTask.cancel();
        if (taskScheduler != null) taskScheduler.stop();
        if (farmObjectManager != null) farmObjectManager.clear();
        if (areaAnalyzer != null) areaAnalyzer.clear();
    }

    private void loadManagers() {
        settings = new FarmSettings(getConfig());
        areaAnalyzer = new FarmAreaAnalyzer(settings);
        farmObjectManager = new FarmObjectManager(this, areaAnalyzer);
        ownershipManager = new FarmOwnershipManager(this);
        farmObjectManager.registerLoaded();
        farmProcessor = new FarmProcessor(this, settings);
        taskScheduler = new FarmTaskScheduler(this);
        milkManager = new MilkManager(this, settings);
        fishingManager = new FishingManager(this);
        hudManager = new FarmHudManager(this);
    }

    private void registerListeners() {
        var pm = getServer().getPluginManager();
        pm.registerEvents(farmObjectManager, this);
        pm.registerEvents(new DropManager(this), this);
        pm.registerEvents(milkManager, this);
        pm.registerEvents(fishingManager, this);
        pm.registerEvents(hudManager, this);
        pm.registerEvents(new FreshFishReleaseManager(this), this);
        pm.registerEvents(new AquariumFishHarvestManager(this), this);
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
        settings = new FarmSettings(getConfig());
        areaAnalyzer = new FarmAreaAnalyzer(settings);
        farmObjectManager.setAreaAnalyzer(areaAnalyzer);
        farmObjectManager.clear();
        farmObjectManager.registerLoaded();
        farmProcessor = new FarmProcessor(this, settings);
        milkManager.setSettings(settings);
        restartFarmTask();
    }

    public FarmSettings settings() { return settings; }
    public FarmObjectManager farmObjectManager() { return farmObjectManager; }
    public FarmOwnershipManager ownershipManager() { return ownershipManager; }
    public String message(String key) { return org.bukkit.ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages." + key, "")); }
    public ItemStack createFeederItem() { return createTaggedItem("§6Кормушка", "feeder_item"); }
    public ItemStack createAquariumShelfItem() { return createTaggedItem("§bАквариумная кормушка", "aquarium_shelf_item"); }

    private ItemStack createTaggedItem(String name, String keyName) {
        ItemStack item = new ItemStack(Material.BARREL);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.getPersistentDataContainer().set(
                    new NamespacedKey(this, keyName),
                    org.bukkit.persistence.PersistentDataType.BYTE,
                    (byte) 1
            );
            item.setItemMeta(meta);
        }
        return item;
    }

    private void registerRecipes() {
        registerFeederRecipe();
        registerAquariumRecipe("aquarium_shelf_cod", Material.COD);
        registerAquariumRecipe("aquarium_shelf_salmon", Material.SALMON);
        registerAquariumRecipe("aquarium_shelf_tropical_fish", Material.TROPICAL_FISH);
        registerAquariumRecipe("aquarium_shelf_pufferfish", Material.PUFFERFISH);
    }

    private void registerFeederRecipe() {
        NamespacedKey key = new NamespacedKey(this, "feeder");
        getServer().removeRecipe(key);

        ShapedRecipe recipe = new ShapedRecipe(key, createFeederItem());
        recipe.shape("BB", "BB");
        recipe.setIngredient('B', Material.BARREL);
        getServer().addRecipe(recipe);
    }

    /**
     * Регистрирует отдельный рецепт для каждого вида рыбы.
     * Так Minecraft принимает только 4 одинаковые обычные свежие рыбы,
     * а не яйца призыва и не смесь разных видов.
     */
    private void registerAquariumRecipe(String keyName, Material fishMaterial) {
        NamespacedKey key = new NamespacedKey(this, keyName);
        getServer().removeRecipe(key);

        ShapedRecipe recipe = new ShapedRecipe(key, createAquariumShelfItem());
        recipe.shape("FF", "FF");
        recipe.setIngredient('F', fishMaterial);
        getServer().addRecipe(recipe);
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
            if (type != null) farmProcessor.process(key, type, serverTick);
        }
    }
}
