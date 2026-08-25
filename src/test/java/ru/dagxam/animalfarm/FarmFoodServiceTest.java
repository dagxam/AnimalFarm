package ru.dagxam.animalfarm;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmFoodServiceTest {
    private final FarmFoodService service = new FarmFoodService();

    @Test
    void acceptsFarmAnimalFoods() {
        assertTrue(service.isAnimalFood(Material.WHEAT));
        assertTrue(service.isAnimalFood(Material.HAY_BLOCK));
        assertTrue(service.isAnimalFood(Material.GRASS_BLOCK));
        assertTrue(service.isAnimalFood(Material.SHORT_GRASS));
        assertTrue(service.isAnimalFood(Material.TALL_GRASS));
        assertTrue(service.isAnimalFood(Material.OAK_LEAVES));
    }

    @Test
    void acceptsAllSupportedSeeds() {
        assertTrue(service.isAnimalFood(Material.WHEAT_SEEDS));
        assertTrue(service.isAnimalFood(Material.BEETROOT_SEEDS));
        assertTrue(service.isAnimalFood(Material.MELON_SEEDS));
        assertTrue(service.isAnimalFood(Material.PUMPKIN_SEEDS));
        assertTrue(service.isAnimalFood(Material.TORCHFLOWER_SEEDS));
        assertTrue(service.isAnimalFood(Material.PITCHER_POD));
    }

    @Test
    void rejectsNonFoodMaterials() {
        assertFalse(service.isAnimalFood(Material.DIAMOND));
        assertFalse(service.isAnimalFood(Material.IRON_INGOT));
        assertFalse(service.isAnimalFood(Material.STONE));
        assertFalse(service.isAnimalFood(null));
    }

    @Test
    void acceptsConfiguredFishFood() {
        assertTrue(service.isFishFood(Material.SEAGRASS));
        assertTrue(service.isFishFood(Material.KELP));
        assertTrue(service.isFishFood(Material.SEA_PICKLE));
        assertTrue(service.isFishFood(Material.WHEAT_SEEDS));
    }

    @Test
    void rejectsNonFishFood() {
        assertFalse(service.isFishFood(Material.DIAMOND));
        assertFalse(service.isFishFood(Material.BREAD));
        assertFalse(service.isFishFood(null));
    }
}
