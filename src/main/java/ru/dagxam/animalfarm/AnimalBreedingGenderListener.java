package ru.dagxam.animalfarm;

import org.bukkit.entity.Animals;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;

/** Проверяет половую совместимость родителей до появления детёныша. */
public final class AnimalBreedingGenderListener implements Listener {
    private final AnimalGenderManager genders;

    public AnimalBreedingGenderListener(AnimalGenderManager genders) {
        this.genders = genders;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (!(event.getMother() instanceof Animals mother) || !(event.getFather() instanceof Animals father)) {
            return;
        }
        if (!genders.canBreed(mother, father)) {
            event.setCancelled(true);
        }
    }
}
