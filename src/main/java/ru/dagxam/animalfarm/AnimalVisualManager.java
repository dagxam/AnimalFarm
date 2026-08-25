package ru.dagxam.animalfarm;

import org.bukkit.Chunk;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Применяет встроенные варианты Minecraft через отдельный compatibility layer. */
public final class AnimalVisualManager {
    private final AnimalFarmPlugin plugin;
    private final AnimalGenderManager genders;

    public AnimalVisualManager(AnimalFarmPlugin plugin, AnimalGenderManager genders) {
        this.plugin = plugin;
        this.genders = genders;
    }

    public boolean enabled() { return plugin.getConfig().getBoolean("animal-genders.visuals.enabled", true); }

    public void applyVisualAfterSpawn(Animals animal) {
        plugin.getServer().getScheduler().runTask(plugin, () -> applyIfStillValid(animal));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> applyIfStillValid(animal), 2L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> applyIfStillValid(animal), 5L);
    }

    private void applyIfStillValid(Animals animal) { if (animal.isValid() && !animal.isDead()) applyVisual(animal); }

    public void applyVisual(Animals animal) {
        if (!enabled() || !genders.supported(animal)) return;
        AnimalGender gender = genders.getOrAssign(animal);
        switch (animal.getType()) {
            case COW -> applyCowVariant(animal, gender);
            case MOOSHROOM -> applyVariantByName(animal, "org.bukkit.entity.MushroomCow$Variant", "BROWN");
            case SHEEP -> { /* Цвет определяет пол, намеренно не меняем. */ }
            case PIG -> applyPigVariant(animal, gender);
            case CHICKEN -> applyChickenVariant(animal, gender);
            default -> { }
        }
    }

    private void applyCowVariant(Animals animal, AnimalGender gender) {
        applyVariantByName(animal, "org.bukkit.entity.Cow$Variant", gender == AnimalGender.MALE ? choose(animal, "WARM", "COLD") : "TEMPERATE");
    }

    private void applyPigVariant(Animals animal, AnimalGender gender) {
        applyVariantByName(animal, "org.bukkit.entity.Pig$Variant", gender == AnimalGender.MALE ? choose(animal, "WARM", "COLD") : "COLD");
    }

    private void applyChickenVariant(Animals animal, AnimalGender gender) {
        applyVariantByName(animal, "org.bukkit.entity.Chicken$Variant", gender == AnimalGender.MALE ? "COLD" : choose(animal, "WARM", "TEMPERATE"));
    }

    private String choose(Animals animal, String first, String second) { return Math.floorMod(animal.getUniqueId().hashCode(), 2) == 0 ? first : second; }

    private void applyVariantByName(Animals animal, String variantClassName, String variantName) {
        try {
            Class<?> variantClass = Class.forName(variantClassName);
            Object variant = getVariantConstant(variantClass, variantName);
            if (variant == null) return;
            Method setVariant = findSetVariant(animal.getClass(), variantClass);
            if (setVariant == null) return;
            setVariant.invoke(animal, variant);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Версия сервера может не поддерживать конкретный встроенный вариант.
        }
    }

    private Object getVariantConstant(Class<?> variantClass, String name) {
        try { Field field = variantClass.getField(name); return field.get(null); }
        catch (ReflectiveOperationException exception) { return null; }
    }

    private Method findSetVariant(Class<?> entityClass, Class<?> variantClass) {
        for (Method method : entityClass.getMethods()) {
            if (!method.getName().equals("setVariant") || method.getParameterCount() != 1) continue;
            if (method.getParameterTypes()[0].isAssignableFrom(variantClass) || variantClass.isAssignableFrom(method.getParameterTypes()[0])) return method;
        }
        return null;
    }

    public void removeVisual(Animals animal) { }

    public void refreshChunkAnimals(Chunk chunk) {
        if (!enabled()) return;
        for (Entity entity : chunk.getEntities()) if (entity instanceof Animals animal) applyVisualAfterSpawn(animal);
    }

    public void refreshLoadedAnimals() {
        if (!enabled()) return;
        for (var world : plugin.getServer().getWorlds()) for (Chunk chunk : world.getLoadedChunks()) refreshChunkAnimals(chunk);
    }

    public void shutdown() { }
}
