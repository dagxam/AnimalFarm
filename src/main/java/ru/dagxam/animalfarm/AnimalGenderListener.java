package ru.dagxam.animalfarm;

import org.bukkit.entity.Ageable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Sheep;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.SheepDyeWoolEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Назначает пол, контролирует пары и применяет визуальные признаки пола. */
public final class AnimalGenderListener implements Listener {
    private final AnimalFarmPlugin plugin;
    private final AnimalGenderManager genders;

    public AnimalGenderListener(AnimalFarmPlugin plugin, AnimalGenderManager genders) {
        this.plugin = plugin;
        this.genders = genders;
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        handleSpawn(event.getEntity());
    }

    /** Дополнительная обработка сущностей, создаваемых при генерации мира и чанков. */
    @EventHandler(ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        handleSpawn(event.getEntity());
    }

    private void handleSpawn(Entity entity) {
        if (!(entity instanceof Animals animal) || !genders.supported(animal)) return;

        // Для овец getOrAssign определяет пол по уже выбранному ванильному цвету:
        // серая/чёрная = самец, остальные = самка.
        genders.assignRandomIfSupported(animal);
        plugin.visualManager().applyVisualAfterSpawn(animal);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (!(event.getMother() instanceof Animals mother) || !(event.getFather() instanceof Animals father)) return;
        if (!genders.canBreed(mother, father)) {
            event.setCancelled(true);
            return;
        }

        if (event.getEntity() instanceof Animals baby && genders.supported(baby)) {
            // У детёныша овцы пол также определяется его фактическим цветом шерсти.
            genders.assignRandomIfSupported(baby);
            plugin.visualManager().applyVisualAfterSpawn(baby);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSheepDye(SheepDyeWoolEvent event) {
        Sheep sheep = event.getEntity();
        // Самца нельзя перекрасить, чтобы чёрный/серый признак пола не потерялся.
        if (genders.supported(sheep) && genders.getOrAssign(sheep) == AnimalGender.MALE) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInfo(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!plugin.getConfig().getBoolean("animal-genders.interaction.show-info-on-right-click", true)) return;
        Entity entity = event.getRightClicked();
        if (!(entity instanceof Animals animal) || !genders.supported(animal)) return;
        if (event.getPlayer().isSneaking()) return;
        if (event.getPlayer().getInventory().getItemInMainHand().getType().name().endsWith("BUCKET")) return;

        AnimalGender gender = genders.getOrAssign(animal);
        String species = displayName(animal, gender);
        String sex = gender == AnimalGender.MALE ? "Мужской" : "Женский";
        String age = animal instanceof Ageable ageable && !ageable.isAdult() ? "Детёныш" : adultAge(gender);
        event.getPlayer().sendMessage(plugin.message("prefix") + "&fЖивотное: &e" + species);
        event.getPlayer().sendMessage("&fПол: &e" + sex);
        event.getPlayer().sendMessage("&fВозраст: &e" + age);
    }

    private String displayName(Animals animal, AnimalGender gender) {
        return switch (animal.getType()) {
            case COW -> gender == AnimalGender.MALE ? "Бык" : "Корова";
            case SHEEP -> gender == AnimalGender.MALE ? "Баран" : "Овца";
            case GOAT -> gender == AnimalGender.MALE ? "Козёл" : "Коза";
            case PIG -> gender == AnimalGender.MALE ? "Хряк" : "Свинья";
            case CHICKEN -> gender == AnimalGender.MALE ? "Петух" : "Курица";
            default -> animal.getType().name();
        };
    }

    private String adultAge(AnimalGender gender) {
        return gender == AnimalGender.MALE ? "Взрослый" : "Взрослая";
    }
}
