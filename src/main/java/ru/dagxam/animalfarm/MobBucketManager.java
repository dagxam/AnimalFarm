package ru.dagxam.animalfarm;

import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.AnimalTamer;
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
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/** Специальное ведро для переноса любых LivingEntity, кроме игроков, с сохранением важных данных. */
public final class MobBucketManager implements Listener {
    private final AnimalFarmPlugin plugin;
    private final NamespacedKey bucketKey;
    private final NamespacedKey dataKey;

    public MobBucketManager(AnimalFarmPlugin plugin) {
        this.plugin = plugin;
        this.bucketKey = new NamespacedKey(plugin, "mob_bucket");
        this.dataKey = new NamespacedKey(plugin, "mob_bucket_data");
    }

    @EventHandler(ignoreCancelled = true)
    public void onCapture(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!(event.getRightClicked() instanceof LivingEntity entity) || entity instanceof Player) return;
        if (!isEmptyBucket(player.getInventory().getItemInMainHand())) return;
        if (!entity.isValid() || entity.isDead()) return;

        event.setCancelled(true);
        ItemStack filled = createFilledBucket(entity);
        if (filled == null || !isFilledBucket(filled)) {
            player.sendMessage("§cНе удалось сохранить данные моба.");
            return;
        }
        if (!replaceBucketWithFilled(player, filled)) {
            player.sendMessage("§cВ инвентаре недостаточно места для ведра с мобом.");
            return;
        }
        entity.remove();
    }

    @EventHandler(ignoreCancelled = true)
    public void onRelease(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (!isFilledBucket(item) || event.getClickedBlock() == null || event.getBlockFace() == null) return;

        event.setCancelled(true);
        MobState state = readState(item);
        if (state == null || !state.type.isAlive()) {
            event.getPlayer().sendMessage("§cДанные ведра повреждены.");
            return;
        }

        Location spawn = event.getClickedBlock().getRelative(event.getBlockFace()).getLocation().add(0.5, 0, 0.5);
        if (spawn.getWorld() == null || !spawn.getBlock().isPassable()) {
            event.getPlayer().sendMessage("§cЗдесь недостаточно места для выпуска моба.");
            return;
        }

        Entity entity;
        try {
            entity = spawn.getWorld().spawnEntity(spawn, state.type);
        } catch (RuntimeException exception) {
            event.getPlayer().sendMessage("§cНе удалось выпустить моба.");
            return;
        }
        if (!(entity instanceof LivingEntity living)) {
            entity.remove();
            event.getPlayer().sendMessage("§cЭтот тип моба нельзя восстановить.");
            return;
        }

        try {
            restoreState(living, state);
        } catch (RuntimeException exception) {
            living.remove();
            event.getPlayer().sendMessage("§cНе удалось восстановить состояние моба.");
            return;
        }
        consumeOneFilledBucket(event.getPlayer(), item);
    }

    public void registerRecipes() {
        NamespacedKey key = new NamespacedKey(plugin, "mob_bucket");
        plugin.getServer().removeRecipe(key);
        ShapedRecipe recipe = new ShapedRecipe(key, createEmptyBucket());
        recipe.shape(" I ", "IBI", " I ");
        recipe.setIngredient('I', Material.IRON_INGOT);
        recipe.setIngredient('B', Material.BUCKET);
        plugin.getServer().addRecipe(recipe);
    }

    private ItemStack createEmptyBucket() {
        ItemStack item = new ItemStack(Material.BUCKET);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§6Ведро для мобов");
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.getPersistentDataContainer().set(bucketKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createFilledBucket(LivingEntity entity) {
        String encoded = encodeState(captureState(entity));
        if (encoded == null || encoded.isBlank()) return null;
        ItemStack item = createEmptyBucket();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        meta.setDisplayName("§6Ведро: §f" + displayName(entity.getType()));
        meta.getPersistentDataContainer().set(dataKey, PersistentDataType.STRING, encoded);
        item.setItemMeta(meta);
        return item;
    }

    private String displayName(EntityType type) { return type.name().toLowerCase(Locale.ROOT).replace('_', ' '); }

    private boolean isEmptyBucket(ItemStack item) {
        return item != null && item.getType() == Material.BUCKET && item.getItemMeta() != null
                && item.getItemMeta().getPersistentDataContainer().has(bucketKey, PersistentDataType.BYTE)
                && !item.getItemMeta().getPersistentDataContainer().has(dataKey, PersistentDataType.STRING);
    }

    private boolean isFilledBucket(ItemStack item) {
        return item != null && item.getItemMeta() != null
                && item.getItemMeta().getPersistentDataContainer().has(bucketKey, PersistentDataType.BYTE)
                && item.getItemMeta().getPersistentDataContainer().has(dataKey, PersistentDataType.STRING);
    }

    private boolean replaceBucketWithFilled(Player player, ItemStack filled) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(filled);
            return true;
        }
        if (player.getInventory().firstEmpty() == -1) return false;
        hand.setAmount(hand.getAmount() - 1);
        player.getInventory().addItem(filled);
        return true;
    }

    private void consumeOneFilledBucket(Player player, ItemStack item) {
        if (item.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(createEmptyBucket());
            return;
        }
        item.setAmount(item.getAmount() - 1);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(createEmptyBucket());
        leftovers.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    private MobState captureState(LivingEntity entity) {
        Properties p = new Properties();
        p.setProperty("type", entity.getType().name());
        p.setProperty("health", Double.toString(entity.getHealth()));
        AttributeInstance maxHealth = entity.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) p.setProperty("maxHealthBase", Double.toString(maxHealth.getBaseValue()));
        p.setProperty("absorption", Double.toString(entity.getAbsorptionAmount()));
        p.setProperty("remainingAir", Integer.toString(entity.getRemainingAir()));
        p.setProperty("customName", entity.getCustomName() == null ? "" : entity.getCustomName());
        p.setProperty("customNameVisible", Boolean.toString(entity.isCustomNameVisible()));
        p.setProperty("silent", Boolean.toString(entity.isSilent()));
        p.setProperty("invulnerable", Boolean.toString(entity.isInvulnerable()));
        p.setProperty("glowing", Boolean.toString(entity.isGlowing()));
        p.setProperty("fireTicks", Integer.toString(entity.getFireTicks()));
        p.setProperty("scoreboardTags", String.join("\u001F", entity.getScoreboardTags()));

        if (entity instanceof Ageable ageable) p.setProperty("adult", Boolean.toString(ageable.isAdult()));
        if (entity instanceof Tameable tameable) {
            p.setProperty("tamed", Boolean.toString(tameable.isTamed()));
            AnimalTamer owner = tameable.getOwner();
            if (owner != null) p.setProperty("owner", owner.getUniqueId().toString());
        }
        if (entity instanceof Sheep sheep) {
            p.setProperty("sheep.color", sheep.getColor().name());
            p.setProperty("sheep.sheared", Boolean.toString(sheep.isSheared()));
        }
        if (entity instanceof Horse horse) {
            p.setProperty("horse.color", horse.getColor().name());
            p.setProperty("horse.style", horse.getStyle().name());
        }
        if (entity instanceof Rabbit rabbit) p.setProperty("rabbit.type", rabbit.getRabbitType().name());
        if (entity instanceof AbstractHorse horse) {
            p.setProperty("horse.domestication", Integer.toString(horse.getDomestication()));
            p.setProperty("horse.maxDomestication", Integer.toString(horse.getMaxDomestication()));
            p.setProperty("horse.jumpStrength", Double.toString(horse.getJumpStrength()));
        }
        saveEquipment(entity.getEquipment(), p);
        saveEffects(entity, p);
        saveAnimalFarmPdc(entity.getPersistentDataContainer(), p);
        return new MobState(entity.getType(), p);
    }

    private void restoreState(LivingEntity entity, MobState state) {
        Properties p = state.properties;
        AttributeInstance maxHealth = entity.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null && p.containsKey("maxHealthBase")) maxHealth.setBaseValue(Math.max(0.1D, parseDouble(p.getProperty("maxHealthBase"), maxHealth.getBaseValue())));
        entity.setCustomName(emptyToNull(p.getProperty("customName")));
        entity.setCustomNameVisible(Boolean.parseBoolean(p.getProperty("customNameVisible", "false")));
        entity.setSilent(Boolean.parseBoolean(p.getProperty("silent", "false")));
        entity.setInvulnerable(Boolean.parseBoolean(p.getProperty("invulnerable", "false")));
        entity.setGlowing(Boolean.parseBoolean(p.getProperty("glowing", "false")));
        entity.setFireTicks(parseInt(p.getProperty("fireTicks"), 0));
        entity.setRemainingAir(parseInt(p.getProperty("remainingAir"), entity.getMaximumAir()));
        entity.setAbsorptionAmount(Math.max(0D, parseDouble(p.getProperty("absorption"), 0D)));
        restoreScoreboardTags(entity, p.getProperty("scoreboardTags", ""));

        if (entity instanceof Ageable ageable) { if (Boolean.parseBoolean(p.getProperty("adult", "true"))) ageable.setAdult(); else ageable.setBaby(); }
        if (entity instanceof Tameable tameable) {
            tameable.setTamed(Boolean.parseBoolean(p.getProperty("tamed", "false")));
            try { String ownerId = p.getProperty("owner"); if (ownerId != null) tameable.setOwner(plugin.getServer().getOfflinePlayer(UUID.fromString(ownerId))); } catch (IllegalArgumentException ignored) { }
        }
        if (entity instanceof Sheep sheep) {
            try { sheep.setColor(DyeColor.valueOf(p.getProperty("sheep.color", sheep.getColor().name()))); } catch (IllegalArgumentException ignored) { }
            sheep.setSheared(Boolean.parseBoolean(p.getProperty("sheep.sheared", "false")));
        }
        if (entity instanceof Horse horse) {
            try { horse.setColor(Horse.Color.valueOf(p.getProperty("horse.color", horse.getColor().name()))); horse.setStyle(Horse.Style.valueOf(p.getProperty("horse.style", horse.getStyle().name()))); } catch (IllegalArgumentException ignored) { }
        }
        if (entity instanceof Rabbit rabbit) { try { rabbit.setRabbitType(Rabbit.Type.valueOf(p.getProperty("rabbit.type", rabbit.getRabbitType().name()))); } catch (IllegalArgumentException ignored) { } }
        if (entity instanceof AbstractHorse horse) {
            horse.setMaxDomestication(Math.max(0, parseInt(p.getProperty("horse.maxDomestication"), horse.getMaxDomestication())));
            horse.setDomestication(Math.max(0, Math.min(parseInt(p.getProperty("horse.domestication"), horse.getDomestication()), horse.getMaxDomestication())));
            horse.setJumpStrength(Math.max(0D, parseDouble(p.getProperty("horse.jumpStrength"), horse.getJumpStrength())));
        }
        restoreEquipment(entity.getEquipment(), p);
        restoreEffects(entity, p);
        restoreAnimalFarmPdc(entity.getPersistentDataContainer(), p);

        double health = parseDouble(p.getProperty("health"), entity.getHealth());
        if (maxHealth != null) health = Math.max(0.1D, Math.min(health, maxHealth.getValue()));
        entity.setHealth(health);
    }

    private void saveEquipment(EntityEquipment equipment, Properties p) {
        if (equipment == null) return;
        saveItem(p, "equipment.mainHand", equipment.getItemInMainHand());
        saveItem(p, "equipment.offHand", equipment.getItemInOffHand());
        saveItem(p, "equipment.helmet", equipment.getHelmet());
        saveItem(p, "equipment.chestplate", equipment.getChestplate());
        saveItem(p, "equipment.leggings", equipment.getLeggings());
        saveItem(p, "equipment.boots", equipment.getBoots());
    }

    private void restoreEquipment(EntityEquipment equipment, Properties p) {
        if (equipment == null) return;
        equipment.setItemInMainHand(readItem(p, "equipment.mainHand"));
        equipment.setItemInOffHand(readItem(p, "equipment.offHand"));
        equipment.setHelmet(readItem(p, "equipment.helmet"));
        equipment.setChestplate(readItem(p, "equipment.chestplate"));
        equipment.setLeggings(readItem(p, "equipment.leggings"));
        equipment.setBoots(readItem(p, "equipment.boots"));
    }

    private void saveItem(Properties p, String key, ItemStack item) {
        if (item == null || item.getType().isAir()) return;
        p.setProperty(key, item.getType().name() + ";" + item.getAmount());
    }

    private ItemStack readItem(Properties p, String key) {
        String raw = p.getProperty(key);
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split(";", 2);
        try { return new ItemStack(Material.valueOf(parts[0]), Math.max(1, parseInt(parts.length > 1 ? parts[1] : "1", 1))); } catch (IllegalArgumentException ignored) { return null; }
    }

    private void saveEffects(LivingEntity entity, Properties p) {
        int index = 0;
        for (PotionEffect effect : entity.getActivePotionEffects()) {
            String type = effect.getType().getKey().toString();
            p.setProperty("effect." + index++, type + ";" + effect.getDuration() + ";" + effect.getAmplifier());
        }
    }

    private void restoreEffects(LivingEntity entity, Properties p) {
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith("effect.")) continue;
            String[] parts = p.getProperty(key, "").split(";", 3);
            if (parts.length != 3) continue;
            PotionEffectType type = PotionEffectType.getByKey(NamespacedKey.fromString(parts[0]));
            if (type != null) entity.addPotionEffect(new PotionEffect(type, Math.max(1, parseInt(parts[1], 1)), Math.max(0, parseInt(parts[2], 0))));
        }
    }

    private void restoreScoreboardTags(Entity entity, String raw) {
        if (raw == null || raw.isBlank()) return;
        for (String tag : raw.split("\u001F")) if (!tag.isBlank()) entity.addScoreboardTag(tag);
    }

    private void saveAnimalFarmPdc(PersistentDataContainer pdc, Properties p) {
        for (NamespacedKey key : pdc.getKeys()) {
            if (!key.getNamespace().equals(plugin.getName().toLowerCase(Locale.ROOT))) continue;
            String base = "pdc." + key.getKey();
            String value = pdc.get(key, PersistentDataType.STRING);
            if (value != null) { p.setProperty(base + ".type", "STRING"); p.setProperty(base + ".value", value); continue; }
            Byte b = pdc.get(key, PersistentDataType.BYTE);
            if (b != null) { p.setProperty(base + ".type", "BYTE"); p.setProperty(base + ".value", Byte.toString(b)); continue; }
            Integer i = pdc.get(key, PersistentDataType.INTEGER);
            if (i != null) { p.setProperty(base + ".type", "INTEGER"); p.setProperty(base + ".value", i.toString()); continue; }
            Long l = pdc.get(key, PersistentDataType.LONG);
            if (l != null) { p.setProperty(base + ".type", "LONG"); p.setProperty(base + ".value", l.toString()); }
        }
    }

    private void restoreAnimalFarmPdc(PersistentDataContainer pdc, Properties p) {
        for (String property : p.stringPropertyNames()) {
            if (!property.startsWith("pdc.") || !property.endsWith(".type")) continue;
            String keyName = property.substring(4, property.length() - 5);
            String value = p.getProperty("pdc." + keyName + ".value");
            if (value == null) continue;
            NamespacedKey key = new NamespacedKey(plugin, keyName);
            switch (p.getProperty(property)) {
                case "STRING" -> pdc.set(key, PersistentDataType.STRING, value);
                case "BYTE" -> pdc.set(key, PersistentDataType.BYTE, (byte) parseInt(value, 0));
                case "INTEGER" -> pdc.set(key, PersistentDataType.INTEGER, parseInt(value, 0));
                case "LONG" -> pdc.set(key, PersistentDataType.LONG, parseLong(value, 0L));
                default -> { }
            }
        }
    }

    private String encodeState(MobState state) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            state.properties.store(out, null);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) { return null; }
    }

    private MobState readState(ItemStack item) {
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return null;
            String data = meta.getPersistentDataContainer().get(dataKey, PersistentDataType.STRING);
            if (data == null || data.isBlank()) return null;
            Properties p = new Properties();
            p.load(new ByteArrayInputStream(Base64.getDecoder().decode(data)));
            EntityType type = EntityType.valueOf(p.getProperty("type"));
            return type == EntityType.PLAYER ? null : new MobState(type, p);
        } catch (Exception e) { return null; }
    }

    private String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }
    private int parseInt(String value, int fallback) { try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; } }
    private long parseLong(String value, long fallback) { try { return Long.parseLong(value); } catch (Exception ignored) { return fallback; } }
    private double parseDouble(String value, double fallback) { try { return Double.parseDouble(value); } catch (Exception ignored) { return fallback; } }

    private static final class MobState {
        private final EntityType type;
        private final Properties properties;
        private MobState(EntityType type, Properties properties) { this.type = type; this.properties = properties; }
    }
}
