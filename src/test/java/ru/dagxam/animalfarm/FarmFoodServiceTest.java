package ru.dagxam.animalfarm;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmFoodServiceTest {
    private final FarmFoodService service = new FarmFoodService();

    @Test
    void cowSheepGoatAndHorseEatGrazerFood() {
        EntityType[] grazers = {
                EntityType.COW,
                EntityType.SHEEP,
                EntityType.GOAT,
                EntityType.HORSE
        };

        for (EntityType type : grazers) {
            assertTrue(service.isFoodFor(type, Material.WHEAT));
            assertTrue(service.isFoodFor(type, Material.HAY_BLOCK));
            assertTrue(service.isFoodFor(type, Material.GRASS_BLOCK));
            assertTrue(service.isFoodFor(type, Material.SHORT_GRASS));
            assertTrue(service.isFoodFor(type, Material.TALL_GRASS));
            assertTrue(service.isFoodFor(type, Material.OAK_LEAVES));
            assertTrue(service.isFoodFor(type, Material.BIRCH_LEAVES));
            assertTrue(service.isFoodFor(type, Material.CHERRY_LEAVES));
            assertFalse(service.isFoodFor(type, Material.WHEAT_SEEDS));
            assertFalse(service.isFoodFor(type, Material.CARROT));
        }
    }

    @Test
    void anyLeavesGroupAcceptsAllLeafMaterials() {
        for (Material material : Material.values()) {
            if (material.name().endsWith("_LEAVES")) {
                assertTrue(service.isAnimalFood(material), material + " must be accepted by ANY_LEAVES");
                assertTrue(service.isFoodFor(EntityType.COW, material), material + " must feed grazers");
                assertFalse(service.isFoodFor(EntityType.CHICKEN, material), material + " must not feed chickens");
            }
        }
    }

    @Test
    void chickenEatsOnlySeedsFromSupportedFarmFood() {
        assertTrue(service.isFoodFor(EntityType.CHICKEN, Material.WHEAT_SEEDS));
        assertTrue(service.isFoodFor(EntityType.CHICKEN, Material.BEETROOT_SEEDS));
        assertTrue(service.isFoodFor(EntityType.CHICKEN, Material.MELON_SEEDS));
        assertTrue(service.isFoodFor(EntityType.CHICKEN, Material.PUMPKIN_SEEDS));
        assertTrue(service.isFoodFor(EntityType.CHICKEN, Material.TORCHFLOWER_SEEDS));
        assertTrue(service.isFoodFor(EntityType.CHICKEN, Material.PITCHER_POD));
        assertFalse(service.isFoodFor(EntityType.CHICKEN, Material.WHEAT));
        assertFalse(service.isFoodFor(EntityType.CHICKEN, Material.OAK_LEAVES));
        assertFalse(service.isFoodFor(EntityType.CHICKEN, Material.CARROT));
    }

    @Test
    void anySeedsGroupRemainsExplicitAndDoesNotAcceptUnrelatedItems() {
        assertTrue(service.isFoodFor(EntityType.CHICKEN, Material.WHEAT_SEEDS));
        assertTrue(service.isFoodFor(EntityType.CHICKEN, Material.BEETROOT_SEEDS));
        assertTrue(service.isFoodFor(EntityType.CHICKEN, Material.MELON_SEEDS));
        assertTrue(service.isFoodFor(EntityType.CHICKEN, Material.PUMPKIN_SEEDS));
        assertTrue(service.isFoodFor(EntityType.CHICKEN, Material.TORCHFLOWER_SEEDS));
        assertTrue(service.isFoodFor(EntityType.CHICKEN, Material.PITCHER_POD));
        assertFalse(service.isFoodFor(EntityType.CHICKEN, Material.BREAD));
        assertFalse(service.isFoodFor(EntityType.CHICKEN, Material.WHEAT));
    }

    @Test
    void rabbitEatsRabbitFood() {
        assertTrue(service.isFoodFor(EntityType.RABBIT, Material.CARROT));
        assertTrue(service.isFoodFor(EntityType.RABBIT, Material.GOLDEN_CARROT));
        assertFalse(service.isFoodFor(EntityType.RABBIT, Material.WHEAT));
        assertFalse(service.isFoodFor(EntityType.RABBIT, Material.WHEAT_SEEDS));
        assertFalse(service.isFoodFor(EntityType.RABBIT, Material.OAK_LEAVES));
    }

    @Test
    void generalAnimalFoodAcceptsAnySupportedFarmFood() {
        assertTrue(service.isAnimalFood(Material.WHEAT));
        assertTrue(service.isAnimalFood(Material.OAK_LEAVES));
        assertTrue(service.isAnimalFood(Material.WHEAT_SEEDS));
        assertTrue(service.isAnimalFood(Material.CARROT));
        assertFalse(service.isAnimalFood(Material.DIAMOND));
        assertFalse(service.isAnimalFood(Material.STONE));
        assertFalse(service.isAnimalFood(null));
    }

    @Test
    void fishFoodRulesRemainSeparate() {
        assertTrue(service.isFishFood(Material.SEAGRASS));
        assertTrue(service.isFishFood(Material.KELP));
        assertTrue(service.isFishFood(Material.SEA_PICKLE));
        assertTrue(service.isFishFood(Material.WHEAT_SEEDS));
        assertFalse(service.isFishFood(Material.DIAMOND));
        assertFalse(service.isFishFood(Material.BREAD));
        assertFalse(service.isFishFood(null));
    }
}
