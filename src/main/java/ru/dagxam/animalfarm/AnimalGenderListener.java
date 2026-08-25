package ru.dagxam.animalfarm;

import org.bukkit.Chunk;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Sheep;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.SheepDyeWoolEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Назначает и восстанавливает пол животных при спавне и загрузке чанков. */
public final class AnimalGenderListener implements Listener {
    private final AnimalFarmPlugin plugin;
    private final AnimalGenderManager genders;

    public AnimalGenderListener(AnimalFarmPlugin plugin, AnimalGenderManager genders) {
        this.plugin = plugin;
        this.genders = genders;
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        handleAnimal(event.getEntity());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            handleAnimal(entity);
        }
    }

    private void handleAnimal(Entity entity) {
        if (!(entity instanceof Animals animal) || !genders.supported(animal)) {
            return;
        }

        // Если пол уже сохранён в PDC, getOrAssign его не изменит.
        genders.assignRandomIfSupported(animal);
        plugin.visualManager().applyVisualAfterSpawn(animal);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (!(event.getMother() instanceof Animals mother) || !(event.getFather() instanceof Animals father)) {
            return;
        }

        if (!genders.canBreed(mother, father)) {
            event.setCancelled(true);
            return;
        }

        handleAnimal(event.getEntity());
    }

    @EventHandler(ignoreCancelled = true)
    public void onSheepDye(SheepDyeWoolEvent event) {
        Sheep sheep = event.getEntity();
        if (genders.supported(sheep) && genders.getOrAssign(sheep) == AnimalGender.MALE) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInfo(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!plugin.getConfig().getBoolean("animal-genders.interaction.show-info-on-right-click", true)) {
            return;
        }
        if (event.getPlayer().isSneaking()) {
            return;
        }
        if (event.getPlayer().getInventory().getItemInMainHand().getType().name().endsWith("BUCKET")) {
            return;
        }

        Entity entity = event.getRightClicked();
        if (!(entity instanceof Animals animal) || !genders.supported(animal)) {
            return;
        }

        AnimalGender gender = genders.getOrAssign(animal);
        String sex = gender == AnimalGender.MALE ? "Мужской" : "Женский";
        String age = animal instanceof Ageable ageable && !ageable.isAdult()
                ? "Детёныш"
                : gender == AnimalGender.MALE ? "Взрослый" : "Взрослая";

        event.getPlayer().sendMessage(plugin.message("prefix") + "&fЖивотное: &e" + displayName(animal, gender));
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
            case HORSE -> gender == AnimalGender.MALE ? "Жеребец" : "Лошадь";
            case RABBIT -> gender == AnimalGender.MALE ? "Кролик" : "Крольчиха";
            default -> animal.getType().name();
        };
    }
}
