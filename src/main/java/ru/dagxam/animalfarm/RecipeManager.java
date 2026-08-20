package ru.dagxam.animalfarm;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/** Дополнительные рецепты AnimalFarm. */
public final class RecipeManager {
    private final JavaPlugin plugin;

    public RecipeManager(JavaPlugin plugin) {
        this.plugin = plugin;
        registerRecipes();
    }

    private void registerRecipes() {
        registerNameTag();
        registerLead();
        registerBundle();
        registerStringFromWool();
        registerBrush();
    }

    private void registerNameTag() {
        ShapedRecipe recipe = new ShapedRecipe(key("name_tag"), new ItemStack(Material.NAME_TAG));
        recipe.shape("LS", "  ", "  ");
        recipe.setIngredient('L', Material.LEATHER);
        recipe.setIngredient('S', Material.STRING);
        add(recipe);
    }

    private void registerLead() {
        ShapedRecipe recipe = new ShapedRecipe(key("lead"), new ItemStack(Material.LEAD));
        recipe.shape("LLS", "   ", "   ");
        recipe.setIngredient('L', Material.LEATHER);
        recipe.setIngredient('S', Material.STRING);
        add(recipe);
    }

    private void registerBundle() {
        ShapedRecipe recipe = new ShapedRecipe(key("bundle"), new ItemStack(Material.BUNDLE));
        recipe.shape("L L", " S ", " L ");
        recipe.setIngredient('L', Material.LEATHER);
        recipe.setIngredient('S', Material.STRING);
        add(recipe);
    }

    private void registerStringFromWool() {
        ItemStack result = new ItemStack(Material.STRING, 4);
        ShapedRecipe recipe = new ShapedRecipe(key("string_from_wool"), result);
        recipe.shape("W", " ", " ");
        recipe.setIngredient('W', new RecipeChoice.MaterialChoice(allWool()));
        add(recipe);
    }

    private void registerBrush() {
        ShapedRecipe recipe = new ShapedRecipe(key("brush"), new ItemStack(Material.BRUSH));
        recipe.shape("W", "S", " ");
        recipe.setIngredient('W', new RecipeChoice.MaterialChoice(allWool()));
        recipe.setIngredient('S', Material.STICK);
        add(recipe);
    }

    private List<Material> allWool() {
        List<Material> wool = new ArrayList<>();
        for (Material material : Material.values()) {
            if (material.name().endsWith("_WOOL")) wool.add(material);
        }
        return wool;
    }

    private NamespacedKey key(String name) {
        return new NamespacedKey(plugin, "recipe_" + name);
    }

    private void add(ShapedRecipe recipe) {
        plugin.getServer().removeRecipe(recipe.getKey());
        plugin.getServer().addRecipe(recipe);
    }
}
