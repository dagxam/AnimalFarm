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
        // Как на скриншоте: нить сверху, кожа под ней.
        ShapedRecipe recipe = new ShapedRecipe(key("name_tag"), new ItemStack(Material.NAME_TAG));
        recipe.shape("S", "L", " ");
        recipe.setIngredient('L', Material.LEATHER);
        recipe.setIngredient('S', Material.STRING);
        add(recipe);
    }

    private void registerLead() {
        // Как на скриншоте: две кожи и нить в одной горизонтальной линии.
        ShapedRecipe recipe = new ShapedRecipe(key("lead"), new ItemStack(Material.LEAD));
        recipe.shape("   ", "LLS", "   ");
        recipe.setIngredient('L', Material.LEATHER);
        recipe.setIngredient('S', Material.STRING);
        add(recipe);
    }

    private void registerBundle() {
        // Форма ведра: кожа слева/справа, третья кожа снизу по центру, нить в центре.
        ShapedRecipe recipe = new ShapedRecipe(key("bundle"), new ItemStack(Material.BUNDLE));
        recipe.shape("   ", "LSL", " L ");
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
        // Как на скриншоте: шерсть сверху, палка прямо под ней.
        ShapedRecipe recipe = new ShapedRecipe(key("brush"), new ItemStack(Material.BRUSH));
        recipe.shape(" ", "W", "S");
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
