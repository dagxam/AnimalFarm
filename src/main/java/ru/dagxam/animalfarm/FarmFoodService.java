package ru.dagxam.animalfarm;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.EnumSet;
import java.util.Set;

/** Единые правила корма для фермы, обработки и HUD. */
public final class FarmFoodService {
    private static final Set<Material> SEEDS = EnumSet.of(
            Material.WHEAT_SEEDS,
            Material.BEETROOT_SEEDS,
            Material.MELON_SEEDS,
            Material.PUMPKIN_SEEDS,
            Material.TORCHFLOWER_SEEDS,
            Material.PITCHER_POD
    );

    private static final Set<EntityType> GRAZERS = EnumSet.of(
            EntityType.COW,
            EntityType.SHEEP,
            EntityType.GOAT,
            EntityType.HORSE
    );

    /** Общая проверка: является ли материал кормом хотя бы для одного животного фермы. */
    public boolean isAnimalFood(Material material) {
        if (material == null) return false;
        return isGrazerFood(material)
                || SEEDS.contains(material)
                || isRabbitFood(material);
    }

    /** Проверка корма для конкретного животного. */
    public boolean isFoodFor(EntityType type, Material material) {
        if (type == null || material == null) return false;

        if (GRAZERS.contains(type)) {
            return isGrazerFood(material);
        }

        if (type == EntityType.CHICKEN) {
            return SEEDS.contains(material);
        }

        if (type == EntityType.RABBIT) {
            return isRabbitFood(material);
        }

        return false;
    }

    private boolean isGrazerFood(Material material) {
        return material == Material.WHEAT
                || material == Material.HAY_BLOCK
                || material == Material.GRASS_BLOCK
                || material == Material.SHORT_GRASS
                || material == Material.TALL_GRASS
                || material.name().endsWith("_LEAVES");
    }

    private boolean isRabbitFood(Material material) {
        return material == Material.CARROT
                || material == Material.GOLDEN_CARROT;
    }

    public boolean isFishFood(Material material) {
        if (material == null) return false;
        return SEEDS.contains(material)
                || material == Material.SEAGRASS
                || material == Material.KELP
                || material == Material.KELP_PLANT
                || material == Material.SEA_PICKLE;
    }
}
