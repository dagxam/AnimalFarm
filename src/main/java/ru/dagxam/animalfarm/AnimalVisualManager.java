package ru.dagxam.animalfarm;

import org.bukkit.DyeColor;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Sheep;

/**
 * Визуальная часть системы пола без подмены модели.
 *
 * Важно: ванильный клиент Minecraft не позволяет серверному плагину назначить
 * отдельную PNG-текстуру конкретной корове, козе, свинье или курице. Поэтому
 * здесь намеренно нет ItemDisplay, PAPER, CustomModelData, невидимых животных
 * и других подмен модели.
 *
 * Ресурс-пак по-прежнему может загружаться ResourcePackManager, но сам выбор
 * текстуры для конкретной сущности требует клиентской системы вариантов
 * текстур. Без такой системы плагин не пытается создавать ложный визуальный
 * эффект. Для барана используется реальный ванильный признак — чёрный цвет.
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
     * Применяет только безопасные встроенные ванильные признаки внешности.
     * Никакие дополнительные сущности и модели не создаются.
     */
    public void applyVisual(Animals animal) {
        if (!enabled() || !genders.supported(animal)) {
            return;
        }

        if (animal instanceof Sheep sheep
                && genders.getOrAssign(sheep) == AnimalGender.MALE) {
            sheep.setColor(DyeColor.BLACK);
        }
    }

    /**
     * Метод сохранён для совместимости с жизненным циклом плагина.
     * В текстурном режиме нечего удалять: настоящий моб никогда не скрывается.
     */
    public void removeVisual(Animals animal) {
        // Ничего не делать.
    }

    /** Применяет безопасные визуальные признаки ко всем загруженным животным. */
    public void refreshLoadedAnimals() {
        if (!enabled()) return;

        plugin.getServer().getWorlds().forEach(world -> {
            world.getEntities().forEach(entity -> {
                if (entity instanceof Animals animal) {
                    applyVisual(animal);
                }
            });
        });
    }

    /**
     * В этом режиме нет дочерних display-сущностей, поэтому очищать нечего.
     */
    public void shutdown() {
        // Ничего не делать.
    }
}
