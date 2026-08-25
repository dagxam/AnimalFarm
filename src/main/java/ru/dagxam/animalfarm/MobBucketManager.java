package ru.dagxam.animalfarm;

import org.bukkit.ChatColor;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Horse;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/** Переносит любых живых мобов, кроме игроков и ArmorStand, с сохранением состояния AnimalFarm. */
public final class MobBucketManager implements Listener {
    private static final String VERSION = "2";

    private final JavaPlugin plugin;
    private final NamespacedKey bucketKey;
    private final NamespacedKey mobDataKey;

    public MobBucketManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.bucketKey = new NamespacedKey(plugin, "mob_bucket");
        this.mobDataKey = new NamespacedKey(plugin, "mob_data");
    }

    public void registerRecipes() {
        NamespacedKey key = new NamespacedKey(plugin, "mob_bucket");
        plugin.getServer().removeRecipe(key);
        ShapedRecipe recipe = new ShapedRecipe(key, createEmptyBucket());
        recipe.shape("BBB");
        recipe.setIngredient('B', Material.BUCKET);
        plugin.getServer().addRecipe(recipe);
        registerAnimalFarmCrafts();
    }

    private void registerAnimalFarmCrafts() {
        NamespacedKey nameTagKey = new NamespacedKey(plugin, "craft_name_tag");
        plugin.getServer().removeRecipe(nameTagKey);
        ShapedRecipe nameTag = new ShapedRecipe(nameTagKey, new ItemStack(Material.NAME_TAG));
        nameTag.shape("LS", "  ");
        nameTag.setIngredient('L', Material.LEATHER);
        nameTag.setIngredient('S', Material.STRING);
        plugin.getServer().addRecipe(nameTag);

        NamespacedKey leadKey = new NamespacedKey(plugin, "craft_lead");
        plugin.getServer().removeRecipe(leadKey);
        ShapedRecipe lead = new ShapedRecipe(leadKey, new ItemStack(Material.LEAD));
        lead.shape("LL", "S ");
        lead.setIngredient('L', Material.LEATHER);
        lead.setIngredient('S', Material.STRING);
        plugin.getServer().addRecipe(lead);

        NamespacedKey stringKey = new NamespacedKey(plugin, "craft_wool_to_string");
        plugin.getServer().removeRecipe(stringKey);
        ShapedRecipe string = new ShapedRecipe(stringKey, new ItemStack(Material.STRING, 4));
        string.shape("W");
        string.setIngredient('W', new RecipeChoice.MaterialChoice(Arrays.stream(Material.values())
                .filter(material -> material.name().endsWith("_WOOL"))
                .toList()));
        plugin.getServer().addRecipe(string);

        NamespacedKey bundleKey = new NamespacedKey(plugin, "craft_bundle");
        plugin.getServer().removeRecipe(bundleKey);
        ShapedRecipe bundle = new ShapedRecipe(bundleKey, new ItemStack(Material.BUNDLE));
        bundle.shape("L L", " S ", " L ");
        bundle.setIngredient('L', Material.LEATHER);
        bundle.setIngredient('S', Material.STRING);
        plugin.getServer().addRecipe(bundle);
    }

    private ItemStack createEmptyBucket() {
        ItemStack item = new ItemStack(Material.BUCKET);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(color("&6Ведро для мобов"));
        meta.setLore(java.util.List.of(
                color("&7ПКМ по мобу — поместить его в ведро."),
                color("&7Shift + ПКМ по корове, козе или овце — доить."),
                color("&7ПКМ по свободному месту — выпустить моба.")
        ));
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.getPersistentDataContainer().set(bucketKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isBucket(ItemStack item) {
        if (item == null || item.getType() != Material.BUCKET || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(bucketKey, PersistentDataType.BYTE);
    }

    private boolean hasMob(ItemStack item) {
        if (!isBucket(item)) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(mobDataKey, PersistentDataType.STRING);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMobPickup(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        ItemStack bucket = player.getInventory().getItemInMainHand();
        if (!isBucket(bucket) || hasMob(bucket)) return;

        Entity target = event.getRightClicked();
        if (player.isSneaking() && isMilkAnimal(target)) return;
        if (!(target instanceof LivingEntity living)
                || target instanceof Player
                || target instanceof org.bukkit.entity.ArmorStand
                || target.isDead()) return;

        ItemStack filled = createFilledBucket(living);
        if (!replaceOneEmptyBucket(player, bucket, filled)) return;

        event.setCancelled(true);
        target.remove();
        player.sendMessage(color("&8[&6AnimalFarm&8] &aМоб помещён в ведро: &f" + displayType(living)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onRelease(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack bucket = player.getInventory().getItemInMainHand();
        if (!hasMob(bucket)) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null || event.getBlockFace() == null) return;

        Location spawn = clicked.getRelative(event.getBlockFace()).getLocation().add(0.5, 0.0, 0.5);
        if (!isFree(spawn)) {
            player.sendMessage(color("&8[&6AnimalFarm&8] &cНедостаточно места, чтобы выпустить моба."));
            return;
        }

        MobState state = readState(bucket);
        if (state == null) {
            player.sendMessage(color("&8[&6AnimalFarm&8] &cДанные моба в ведре повреждены."));
            return;
        }

        try {
            EntityType type = EntityType.valueOf(state.type());
            if (!type.isAlive() || type == EntityType.PLAYER) return;

            event.setCancelled(true);
            Entity entity = spawn.getWorld().spawnEntity(spawn, type);
            if (!(entity instanceof LivingEntity living)) {
                entity.remove();
                return;
            }

            restoreState(living, state);
            player.getInventory().setItemInMainHand(createEmptyBucket());
            player.sendMessage(color("&8[&6AnimalFarm&8] &aМоб выпущен: &f" + displayType(living)));
        } catch (IllegalArgumentException ex) {
            player.sendMessage(color("&8[&6AnimalFarm&8] &cНе удалось восстановить моба из ведра."));
        }
    }

    private boolean replaceOneEmptyBucket(Player player, ItemStack source, ItemStack filled) {
        if (source.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(filled);
            return true;
        }

        source.setAmount(source.getAmount() - 1);
        var leftovers = player.getInventory().addItem(filled);
        if (!leftovers.isEmpty()) {
            source.setAmount(source.getAmount() + 1);
            return false;
        }
        return true;
    }

    private boolean isMilkAnimal(Entity entity) {
        return switch (entity.getType()) {
            case COW, GOAT, SHEEP -> true;
            default -> false;
        };
    }

    private ItemStack createFilledBucket(LivingEntity entity) {
        ItemStack item = createEmptyBucket();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.getPersistentDataContainer().set(mobDataKey, PersistentDataType.STRING, encodeState(captureState(entity)));
        meta.setDisplayName(color("&6Ведро с мобом: &f" + displayType(entity)));
        meta.setLore(java.util.List.of(color("&7ПКМ по свободному месту — выпустить моба.")));
        item.setItemMeta(meta);
        return item;
    }

    private MobState captureState(LivingEntity entity) {
        Properties properties = new Properties();
        properties.setProperty("version", VERSION);
        properties.setProperty("type", entity.getType().name());
        properties.setProperty("name", nullToEmpty(entity.getCustomName()));
        properties.setProperty("nameVisible", Boolean.toString(entity.isCustomNameVisible()));
        properties.setProperty("health", Double.toString(entity.getHealth()));
        properties.setProperty("ai", Boolean.toString(entity.hasAI()));
        properties.setProperty("silent", Boolean.toString(entity.isSilent()));
        properties.setProperty("invulnerable", Boolean.toString(entity.isInvulnerable()));
        properties.setProperty("glowing", Boolean.toString(entity.isGlowing()));
        properties.setProperty("fireTicks", Integer.toString(entity.getFireTicks()));
        properties.setProperty("removeWhenFarAway", Boolean.toString(entity.getRemoveWhenFarAway()));

        if (entity instanceof Ageable ageable) {
            properties.setProperty("age", Integer.toString(ageable.getAge()));
        }

        if (entity instanceof Tameable tameable) {
            properties.setProperty("tamed", Boolean.toString(tameable.isTamed()));
            if (tameable.getOwner() != null) {
                properties.setProperty("owner", tameable.getOwner().getUniqueId().toString());
            }
        }

        if (entity instanceof Sheep sheep) {
            properties.setProperty("sheep.color", sheep.getColor().name());
            properties.setProperty("sheep.sheared", Boolean.toString(sheep.isSheared()));
        }

        if (entity instanceof Horse horse) {
            properties.setProperty("horse.color", horse.getColor().name());
            properties.setProperty("horse.style", horse.getStyle().name());
        }

        if (entity instanceof Rabbit rabbit) {
            properties.setProperty("rabbit.type", rabbit.getRabbitType().name());
        }

        captureAnimalFarmPdc(entity.getPersistentDataContainer(), properties);
        return new MobState(properties);
    }

    private void captureAnimalFarmPdc(PersistentDataContainer pdc, Properties properties) {
        for (NamespacedKey key : pdc.getKeys()) {
            if (!key.getNamespace().equalsIgnoreCase(plugin.getName().toLowerCase(Locale.ROOT))) continue;
            String prefix = "pdc." + key.getKey();
            String string = pdc.get(key, PersistentDataType.STRING);
            if (string != null) {
                properties.setProperty(prefix + ".type", "STRING");
                properties.setProperty(prefix + ".value", string);
                continue;
            }
            Byte byteValue = pdc.get(key, PersistentDataType.BYTE);
            if (byteValue != null) {
                properties.setProperty(prefix + ".type", "BYTE");
                properties.setProperty(prefix + ".value", Byte.toString(byteValue));
                continue;
            }
            Integer intValue = pdc.get(key, PersistentDataType.INTEGER);
            if (intValue != null) {
                properties.setProperty(prefix + ".type", "INTEGER");
                properties.setProperty(prefix + ".value", Integer.toString(intValue));
                continue;
            }
            Long longValue = pdc.get(key, PersistentDataType.LONG);
            if (longValue != null) {
                properties.setProperty(prefix + ".type", "LONG");
                properties.setProperty(prefix + ".value", Long.toString(longValue));
            }
        }
    }

    private void restoreState(LivingEntity entity, MobState state) {
        Properties p = state.properties();
        String name = p.getProperty("name", "");
        entity.setCustomName(name.isEmpty() ? null : name);
        entity.setCustomNameVisible(Boolean.parseBoolean(p.getProperty("nameVisible", "false")));
        entity.setAI(Boolean.parseBoolean(p.getProperty("ai", "true")));
        entity.setSilent(Boolean.parseBoolean(p.getProperty("silent", "false")));
        entity.setInvulnerable(Boolean.parseBoolean(p.getProperty("invulnerable", "false")));
        entity.setGlowing(Boolean.parseBoolean(p.getProperty("glowing", "false")));
        entity.setFireTicks(parseInt(p.getProperty("fireTicks"), 0));
        entity.setRemoveWhenFarAway(Boolean.parseBoolean(p.getProperty("removeWhenFarAway", "true")));

        if (entity instanceof Ageable ageable && p.containsKey("age")) {
            ageable.setAge(parseInt(p.getProperty("age"), 0));
        }

        if (entity instanceof Tameable tameable) {
            tameable.setTamed(Boolean.parseBoolean(p.getProperty("tamed", "false")));
            String owner = p.getProperty("owner", "");
            if (!owner.isEmpty()) {
                try {
                    tameable.setOwner(plugin.getServer().getOfflinePlayer(UUID.fromString(owner)));
                    tameable.setTamed(true);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        if (entity instanceof Sheep sheep) {
            try {
                sheep.setColor(DyeColor.valueOf(p.getProperty("sheep.color", sheep.getColor().name())));
            } catch (IllegalArgumentException ignored) {
            }
            sheep.setSheared(Boolean.parseBoolean(p.getProperty("sheep.sheared", "false")));
        }

        if (entity instanceof Horse horse) {
            try {
                horse.setColor(Horse.Color.valueOf(p.getProperty("horse.color", horse.getColor().name())));
                horse.setStyle(Horse.Style.valueOf(p.getProperty("horse.style", horse.getStyle().name())));
            } catch (IllegalArgumentException ignored) {
            }
        }

        if (entity instanceof Rabbit rabbit) {
            try {
                rabbit.setRabbitType(Rabbit.Type.valueOf(p.getProperty("rabbit.type", rabbit.getRabbitType().name())));
            } catch (IllegalArgumentException ignored) {
            }
        }

        restoreAnimalFarmPdc(entity.getPersistentDataContainer(), p);

        double health = parseDouble(p.getProperty("health"), entity.getHealth());
        if (entity.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            health = Math.max(0.1D, Math.min(health, entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue()));
        }
        entity.setHealth(health);
    }

    private void restoreAnimalFarmPdc(PersistentDataContainer pdc, Properties properties) {
        for (String property : properties.stringPropertyNames()) {
            if (!property.startsWith("pdc.") || !property.endsWith(".type")) continue;
            String keyName = property.substring(4, property.length() - 5);
            String value = properties.getProperty("pdc." + keyName + ".value");
            if (value == null) continue;
            NamespacedKey key = new NamespacedKey(plugin, keyName);
            switch (properties.getProperty(property)) {
                case "STRING" -> pdc.set(key, PersistentDataType.STRING, value);
                case "BYTE" -> pdc.set(key, PersistentDataType.BYTE, (byte) parseInt(value, 0));
                case "INTEGER" -> pdc.set(key, PersistentDataType.INTEGER, parseInt(value, 0));
                case "LONG" -> pdc.set(key, PersistentDataType.LONG, parseLong(value, 0L));
                default -> {
                }
            }
        }
    }

    private String encodeState(MobState state) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            state.properties().store(out, null);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException ex) {
            throw new IllegalStateException("Не удалось сохранить состояние моба", ex);
        }
    }

    private MobState readState(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String encoded = meta.getPersistentDataContainer().get(mobDataKey, PersistentDataType.STRING);
        if (encoded == null) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            Properties properties = new Properties();
            properties.load(new ByteArrayInputStream(bytes));
            if (!VERSION.equals(properties.getProperty("version"))) return null;
            String type = properties.getProperty("type");
            return type == null || type.isBlank() ? null : new MobState(properties);
        } catch (IllegalArgumentException | IOException ex) {
            return null;
        }
    }

    private boolean isFree(Location location) {
        Block feet = location.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        return feet.isPassable() && head.isPassable();
    }

    private String displayType(Entity entity) {
        String name = entity.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private record MobState(Properties properties) {
        String type() {
            return properties.getProperty("type");
        }
    }
}
