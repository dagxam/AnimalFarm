package ru.dagxam.animalfarm;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Barrel;
import org.bukkit.entity.Animals;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Обрабатывает ежедневную продукцию животных отдельно от основной координации фермы. */
public final class FarmProductionProcessor {
    private final AnimalFarmPlugin plugin;
    private final FarmSettings settings;
    private final FarmInventoryService inventoryService;
    private final NamespacedKey productionDayKey;

    public FarmProductionProcessor(AnimalFarmPlugin plugin, FarmSettings settings, FarmInventoryService inventoryService, NamespacedKey productionDayKey) {
        this.plugin = plugin;
        this.settings = settings;
        this.inventoryService = inventoryService;
        this.productionDayKey = productionDayKey;
    }

    public void process(Barrel feeder, List<Animals> animals, long day, int cycles) {
        long previousDay = feeder.getPersistentDataContainer().getOrDefault(productionDayKey, PersistentDataType.LONG, -1L);
        if (previousDay >= day) return;

        Inventory inventory = feeder.getInventory();
        List<Animals> hens = new ArrayList<>();
        List<Animals> sheep = new ArrayList<>();

        for (Animals animal : animals) {
            if (!animal.isAdult()) continue;
            if (animal.getType() == EntityType.CHICKEN) {
                // Петухи не несут яйца.
                if (plugin.genderManager().getOrAssign(animal) == AnimalGender.FEMALE) {
                    hens.add(animal);
                }
            } else if (animal.getType() == EntityType.SHEEP) {
                sheep.add(animal);
            }
        }

        for (int cycle = 0; cycle < Math.max(1, cycles); cycle++) {
            processEggs(inventory, hens);
            processWool(inventory, sheep);
        }

        feeder.getPersistentDataContainer().set(productionDayKey, PersistentDataType.LONG, day);
    }

    private void processEggs(Inventory inventory, List<Animals> hens) {
        if (hens.isEmpty()) return;
        if (!inventoryService.consumeAnimalFood(EntityType.CHICKEN, inventory, settings.animalFoodMin())) return;

        int configured = random(
                plugin.getConfig().getInt("production.chicken.eggs-min", 5),
                plugin.getConfig().getInt("production.chicken.eggs-max", 10)
        );
        // Скин самки не влияет на тип яйца.
        addItems(inventory, Material.EGG, Math.min(hens.size(), configured));
    }

    private void processWool(Inventory inventory, List<Animals> sheep) {
        if (sheep.isEmpty()) return;
        if (!inventoryService.consumeAnimalFood(EntityType.SHEEP, inventory, settings.animalFoodMin())) return;

        int configured = random(
                plugin.getConfig().getInt("production.wool.min", 2),
                plugin.getConfig().getInt("production.wool.max", 3)
        );
        addItems(inventory, Material.WHITE_WOOL, Math.min(sheep.size(), configured));
    }

    private int random(int min, int max) {
        int lower = Math.min(min, max);
        int upper = Math.max(min, max);
        return ThreadLocalRandom.current().nextInt(lower, upper + 1);
    }

    private void addItems(Inventory inventory, Material material, int amount) {
        if (amount > 0) inventory.addItem(new ItemStack(material, amount));
    }
}
