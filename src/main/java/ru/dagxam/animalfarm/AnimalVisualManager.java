package ru.dagxam.animalfarm;

import org.bukkit.DyeColor;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Sheep;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Визуальная часть системы пола без Resource Pack, моделей и подмены сущностей.
 *
 * Используются только встроенные ванильные варианты:
 * - самец-корова (бык) получает WARM-вариант коровы;
 * - самец-свинья (хряк) получает WARM-вариант свиньи;
 * - самец-овца (баран) получает чёрную шерсть.
 *
 * Самки используют обычные варианты TEMPERATE, поэтому корова и свинья остаются
 * визуально стандартными. Вариант применяется и взрослым, и детёнышам сразу
 * после назначения пола.
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

    public void applyVisual(Animals animal) {
        if (!enabled() || !genders.supported(animal)) return;

        AnimalGender gender = genders.getOrAssign(animal);

        if (animal instanceof Sheep sheep) {
            // Только бараны становятся чёрными. Овцам не принудительно меняем цвет,
            // чтобы не ломать обычные цвета и окрашивание шерсти игроками.
            if (gender == AnimalGender.MALE) {
                sheep.setColor(DyeColor.BLACK);
            }
            return;
        }

        if (animal.getType().name().equals("COW")) {
            applyVariant(animal, "org.bukkit.entity.Cow$Variant", gender);
            return;
        }

        if (animal.getType().name().equals("PIG")) {
            applyVariant(animal, "org.bukkit.entity.Pig$Variant", gender);
        }
    }

    private void applyVariant(Animals animal, String variantClassName, AnimalGender gender) {
        try {
            Class<?> variantClass = Class.forName(variantClassName);
            // WARM — коричневый ванильный вариант для самцов.
            // TEMPERATE — обычный розовый вариант для самок.
            Object variant = getVariantConstant(
                    variantClass,
                    gender == AnimalGender.MALE ? "WARM" : "TEMPERATE"
            );
            if (variant == null) return;

            Method setVariant = findSetVariant(animal.getClass(), variantClass);
            if (setVariant != null) {
                setVariant.invoke(animal, variant);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Если используемое Bukkit-совместимое API не поддерживает варианты,
            // плагин продолжает работать, а визуальное изменение просто пропускается.
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
        // Никаких дочерних display-сущностей нет.
    }

    public void refreshLoadedAnimals() {
        if (!enabled()) return;
        plugin.getServer().getWorlds().forEach(world -> {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Animals animal) {
                    applyVisual(animal);
                }
            }
        });
    }

    public void shutdown() {
        // Нечего удалять: внешний вид принадлежит настоящей сущности.
    }
}
