package ru.dagxam.animalfarm;

import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Визуальная часть системы пола без моделей, подмены сущностей и привязки к ядру.
 * Используются только встроенные варианты Minecraft.
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
     * Применяет внешний вид после завершения обычного спавна.
     * Повторная установка нужна, чтобы итоговый биомный вариант ванили не
     * перезаписал вариант, выбранный системой пола.
     */
    public void applyVisualAfterSpawn(Animals animal) {
        plugin.getServer().getScheduler().runTask(plugin, () -> applyIfStillValid(animal));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> applyIfStillValid(animal), 2L);
    }

    private void applyIfStillValid(Animals animal) {
        if (animal.isValid() && !animal.isDead()) {
            applyVisual(animal);
        }
    }

    public void applyVisual(Animals animal) {
        if (!enabled() || !genders.supported(animal)) return;

        AnimalGender gender = genders.getOrAssign(animal);

        if (animal instanceof org.bukkit.entity.Sheep) {
            // Цвет овцы не меняем: он и есть визуальный признак пола.
            // Чёрная и серая овца = баран, остальные цвета = овца.
            return;
        }

        if (animal.getType().name().equals("COW")) {
            // Бык — коричневый встроенный вариант, корова — обычный вариант.
            applyVariant(animal, "org.bukkit.entity.Cow$Variant", gender, "WARM", "TEMPERATE");
            return;
        }

        if (animal.getType().name().equals("PIG")) {
            // Хряк — коричневый встроенный вариант, свинья — обычный розовый вариант.
            applyVariant(animal, "org.bukkit.entity.Pig$Variant", gender, "WARM", "TEMPERATE");
            return;
        }

        if (animal.getType().name().equals("CHICKEN")) {
            applyChickenVariant(animal, gender);
        }
    }

    private void applyChickenVariant(Animals animal, AnimalGender gender) {
        String variantName;
        if (gender == AnimalGender.MALE) {
            variantName = "COLD";
        } else {
            variantName = Math.floorMod(animal.getUniqueId().hashCode(), 2) == 0
                    ? "WARM"
                    : "TEMPERATE";
        }
        applyVariantByName(animal, "org.bukkit.entity.Chicken$Variant", variantName);
    }

    private void applyVariant(
            Animals animal,
            String variantClassName,
            AnimalGender gender,
            String maleVariantName,
            String femaleVariantName
    ) {
        String variantName = gender == AnimalGender.MALE ? maleVariantName : femaleVariantName;
        applyVariantByName(animal, variantClassName, variantName);
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
