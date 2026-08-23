package ru.dagxam.animalfarm;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * Визуальная надстройка пола животных.
 *
 * Самец остаётся настоящей серверной сущностью для всей игровой логики,
 * а поверх него создаётся ItemDisplay с моделью из ресурс-пака.
 * Самки остаются ванильными. Баран использует настоящую чёрную овцу.
 */
public final class AnimalVisualManager implements Listener {
    private final AnimalFarmPlugin plugin;
    private final AnimalGenderManager genders;
    private final NamespacedKey ownerKey;
    private final NamespacedKey displayKey;

    public AnimalVisualManager(AnimalFarmPlugin plugin, AnimalGenderManager genders) {
        this.plugin = plugin;
        this.genders = genders;
        this.ownerKey = new NamespacedKey(plugin, "gender_visual_owner");
        this.displayKey = new NamespacedKey(plugin, "gender_visual_display");
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("animal-genders.visuals.enabled", true);
    }

    public void applyVisual(Animals animal) {
        if (!enabled() || !genders.supported(animal)) {
            removeVisual(animal);
            return;
        }

        AnimalGender gender = genders.getOrAssign(animal);

        // Баран остаётся настоящей овцой: его чёрный цвет задаётся отдельно.
        if (animal.getType() == org.bukkit.entity.EntityType.SHEEP || gender != AnimalGender.MALE) {
            removeVisual(animal);
            return;
        }

        int modelData = modelData(animal);
        if (modelData == -1) {
            removeVisual(animal);
            return;
        }

        ItemDisplay display = findDisplay(animal);
        if (display == null || !display.isValid()) {
            display = animal.getWorld().spawn(animal.getLocation(), ItemDisplay.class, created -> {
                created.setGravity(false);
                created.setInvulnerable(true);
                created.setPersistent(false);
                created.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            });
            display.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, animal.getUniqueId().toString());
            animal.addPassenger(display);
            animal.getPersistentDataContainer().set(displayKey, PersistentDataType.STRING, display.getUniqueId().toString());
        }

        display.setItemStack(createVisualItem(modelData));
        display.setTransformation(createTransformation(animal));
        animal.setInvisible(true);
    }

    public void removeVisual(Animals animal) {
        ItemDisplay display = findDisplay(animal);
        if (display != null && display.isValid()) {
            display.remove();
        }
        animal.getPersistentDataContainer().remove(displayKey);
        if (animal.isValid()) {
            animal.setInvisible(false);
        }
    }

    private ItemDisplay findDisplay(Animals animal) {
        PersistentDataContainer pdc = animal.getPersistentDataContainer();
        String rawUuid = pdc.get(displayKey, PersistentDataType.STRING);
        if (rawUuid != null) {
            try {
                Entity entity = findEntity(animal.getWorld(), UUID.fromString(rawUuid));
                if (entity instanceof ItemDisplay display && belongsTo(display, animal)) {
                    return display;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        for (Entity passenger : animal.getPassengers()) {
            if (passenger instanceof ItemDisplay display && belongsTo(display, animal)) {
                return display;
            }
        }
        return null;
    }

    private Entity findEntity(World world, UUID uuid) {
        for (Entity entity : world.getEntities()) {
            if (entity.getUniqueId().equals(uuid)) {
                return entity;
            }
        }
        return null;
    }

    private boolean belongsTo(ItemDisplay display, Animals animal) {
        String owner = display.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        return animal.getUniqueId().toString().equals(owner);
    }

    private ItemStack createVisualItem(int modelData) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setCustomModelData(modelData);
            item.setItemMeta(meta);
        }
        return item;
    }

    private Transformation createTransformation(Animals animal) {
        float scale = animal instanceof Ageable ageable && !ageable.isAdult() ? 0.55f : 1.0f;
        return new Transformation(
                new Vector3f(0.0f, 0.0f, 0.0f),
                new Quaternionf(),
                new Vector3f(scale, scale, scale),
                new Quaternionf()
        );
    }

    private int modelData(Animals animal) {
        return switch (animal.getType()) {
            case COW -> 1001;
            case GOAT -> 1002;
            case PIG -> 1003;
            case CHICKEN -> 1004;
            default -> -1;
        };
    }

    public void refreshLoadedAnimals() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Animals animal) {
                    applyVisual(animal);
                }
            }
        }
    }

    public void shutdown() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Animals animal) {
                    removeVisual(animal);
                }
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (Entity entity : event.getChunk().getEntities()) {
                if (entity instanceof Animals animal) {
                    applyVisual(animal);
                }
            }
        }, 1L);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Animals animal) {
            removeVisual(animal);
        }
    }
}
