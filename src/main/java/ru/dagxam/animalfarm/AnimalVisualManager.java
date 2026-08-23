package ru.dagxam.animalfarm;

import org.bukkit.DyeColor;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Sheep;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Визуальная часть системы пола без подмены модели.
 *
 * Для коров, свиней и куриц используются реальные ванильные варианты сущностей.
 * Самец получает WARM-вариант, самка — TEMPERATE-вариант. Это меняет внешний вид
 * непосредственно у настоящего моба и не создаёт ItemDisplay или дополнительные сущности.
 *
 * Reflection используется намеренно: AnimalFarm собирается против совместимого API и не
 * должен переставать запускаться на ядре, где API вариантов отсутствует.
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

        // Баран: встроенный и безопасный ванильный признак.
        if (animal instanceof Sheep sheep) {
            if (gender == AnimalGender.MALE) sheep.setColor(DyeColor.BLACK);
            return;
        }

        // В Minecraft 1.21.5+ корова, свинья и курица имеют реальные варианты.
        // WARM используется для самца, TEMPERATE — для самки.
        switch (animal.getType()) {
            case COW, PIG, CHICKEN -> applyFarmVariant(animal, gender);
            case GOAT -> {
                // У козы нет встроенного ванильного texture-variant API.
                // Пол продолжает показываться HUD/информацией без подмены модели.
            }
            default -> { }
        }
    }

    private void applyFarmVariant(Animals animal, AnimalGender gender) {
        try {
            Class<?> variantClass = Class.forName(animal.getClass().getInterfaces()[0].getName() + "$Variant");
            Object variant = getVariantConstant(variantClass,
                    gender == AnimalGender.MALE ? "WARM" : "TEMPERATE");
            if (variant == null) return;

            Method setVariant = findSetVariant(animal.getClass(), variantClass);
            if (setVariant == null) return;
            setVariant.invoke(animal, variant);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Ядро не поддерживает этот вариант API. Плагин продолжает работать без текстурной подмены.
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
                if (entity instanceof Animals animal) applyVisual(animal);
            }
        });
    }

    public void shutdown() {
        // Нечего удалять: внешний вид принадлежит настоящей сущности.
    }
}
