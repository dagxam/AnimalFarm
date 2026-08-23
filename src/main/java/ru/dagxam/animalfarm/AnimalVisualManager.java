package ru.dagxam.animalfarm;

import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Применяет только разрешённые пользователем встроенные варианты Minecraft.
 * Правило одинаково для взрослых, детёнышей, естественного спавна и генерации чанков.
 */
public final class AnimalVisualManager {
    private final AnimalFarmPlugin plugin;
    private final AnimalGenderManager genders;

    public AnimalVisualManager(AnimalFarmPlugin plugin, AnimalGenderManager genders) {
        this.plugin = plugin;
        this.genders = genders;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("animal-genders.visuals.enabled", true);
    }

    /**
     * Повторяем установку после завершения ванильной генерации сущности,
     * чтобы биом или генератор чанка не вернул собственный вариант обратно.
     */
    public void applyVisualAfterSpawn(Animals animal) {
        plugin.getServer().getScheduler().runTask(plugin, () -> applyIfStillValid(animal));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> applyIfStillValid(animal), 2L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> applyIfStillValid(animal), 5L);
    }

    private void applyIfStillValid(Animals animal) {
        if (animal.isValid() && !animal.isDead()) {
            applyVisual(animal);
        }
    }

    public void applyVisual(Animals animal) {
        if (!enabled() || !genders.supported(animal)) return;

        AnimalGender gender = genders.getOrAssign(animal);

        switch (animal.getType()) {
            case COW -> applyCowVariant(animal, gender);
            case MOOSHROOM -> applyVariantByName(animal, "org.bukkit.entity.MushroomCow$Variant", "BROWN");
            case SHEEP -> {
                // Чёрная/серая = баран, остальные цвета = овца.
                // Цвет специально не меняем, чтобы не ломать правило пола.
            }
            case PIG -> applyPigVariant(animal, gender);
            case CHICKEN -> applyChickenVariant(animal, gender);
            default -> {
            }
        }
    }

    private void applyCowVariant(Animals animal, AnimalGender gender) {
        String variantName;
        if (gender == AnimalGender.MALE) {
            // Бык: cow_warm или cow_cold.
            variantName = choose(animal, "WARM", "COLD");
        } else {
            // Корова: cow_temperate.
            variantName = "TEMPERATE";
        }
        applyVariantByName(animal, "org.bukkit.entity.Cow$Variant", variantName);
    }

    private void applyPigVariant(Animals animal, AnimalGender gender) {
        String variantName;
        if (gender == AnimalGender.MALE) {
            // Хряк: pig_warm или pig_cold.
            variantName = choose(animal, "WARM", "COLD");
        } else {
            // Свинья: pig_cold согласно заданному правилу.
            variantName = "COLD";
        }
        applyVariantByName(animal, "org.bukkit.entity.Pig$Variant", variantName);
    }

    private void applyChickenVariant(Animals animal, AnimalGender gender) {
        String variantName;
        if (gender == AnimalGender.MALE) {
            // Петух: chicken_cold.
            variantName = "COLD";
        } else {
            // Курица: chicken_warm или chicken_temperate.
            variantName = choose(animal, "WARM", "TEMPERATE");
        }
        applyVariantByName(animal, "org.bukkit.entity.Chicken$Variant", variantName);
    }

    private String choose(Animals animal, String first, String second) {
        return Math.floorMod(animal.getUniqueId().hashCode(), 2) == 0 ? first : second;
    }

    private void applyVariantByName(Animals animal, String variantClassName, String variantName) {
        try {
            Class<?> variantClass = Class.forName(variantClassName);
            Object variant = getVariantConstant(variantClass, variantName);
            if (variant == null) {
                plugin.getLogger().warning("Не найден вариант " + variantName + " для " + animal.getType());
                return;
            }

            Method setVariant = findSetVariant(animal.getClass(), variantClass);
            if (setVariant == null) {
                plugin.getLogger().warning("Метод setVariant недоступен для " + animal.getType());
                return;
            }

            setVariant.invoke(animal, variant);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().warning("Не удалось применить вариант " + variantName + " для "
                    + animal.getType() + ": " + exception.getClass().getSimpleName());
        }
    }

    private Object getVariantConstant(Class<?> variantClass, String name) {
        try {
            Field field = variantClass.getField(name);
            return field.get(null);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private Method findSetVariant(Class<?> entityClass, Class<?> variantClass) {
        for (Method method : entityClass.getMethods()) {
            if (!method.getName().equals("setVariant") || method.getParameterCount() != 1) continue;
            if (method.getParameterTypes()[0].isAssignableFrom(variantClass)
                    || variantClass.isAssignableFrom(method.getParameterTypes()[0])) {
                return method;
            }
        }
        return null;
    }

    public void removeVisual(Animals animal) {
        // Внешний вид принадлежит самой сущности, удалять нечего.
    }

    public void refreshLoadedAnimals() {
        if (!enabled()) return;
        plugin.getServer().getWorlds().forEach(world -> {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Animals animal) {
                    applyVisualAfterSpawn(animal);
                }
            }
        });
    }

    public void shutdown() {
        // Внешний вид принадлежит настоящей сущности.
    }
}
