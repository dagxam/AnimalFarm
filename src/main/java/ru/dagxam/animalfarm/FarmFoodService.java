package ru.dagxam.animalfarm;

import org.bukkit.Material;
import java.util.EnumSet;
import java.util.Set;

/** Единая проверка корма для фермы и HUD. */
public final class FarmFoodService {
    private static final Set<Material> SEEDS = EnumSet.of(Material.WHEAT_SEEDS, Material.BEETROOT_SEEDS, Material.MELON_SEEDS, Material.PUMPKIN_SEEDS, Material.TORCHFLOWER_SEEDS, Material.PITCHER_POD);
    public boolean isAnimalFood(Material material) {
        return material == Material.WHEAT || material == Material.HAY_BLOCK || material == Material.GRASS_BLOCK || material == Material.GRASS || material == Material.TALL_GRASS || material.name().endsWith("_LEAVES") || material == Material.CARROT || material == Material.POTATO || material == Material.BEETROOT || material == Material.GOLDEN_CARROT || SEEDS.contains(material);
    }
    public boolean isFishFood(Material material) {
        return SEEDS.contains(material) || material == Material.SEAGRASS || material == Material.KELP || material == Material.KELP_PLANT || material == Material.SEA_PICKLE;
    }
}