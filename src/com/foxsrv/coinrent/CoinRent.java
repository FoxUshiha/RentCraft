package com.foxsrv.coinrent;
import com.foxsrv.coincard.CoinCardPlugin.CoinCardAPI;
import com.foxsrv.coincard.CoinCardPlugin.TransferCallback;
import com.foxsrv.coincard.CoinCardPlugin.BalanceCallback;
import com.google.gson.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class CoinRent extends JavaPlugin implements Listener {
    // ====================================================
    // CONSTANTS & FORMATTING
    // ====================================================
    private static final DecimalFormat COIN_FORMAT;
    private static final long RENT_CHECK_INTERVAL = 20L * 60 * 60; // 1 hora em ticks
    private static final long MILLIS_PER_HOUR = 60 * 60 * 1000; // 1 hora em milissegundos
    private static final long CHARGE_COOLDOWN_MS = 5000; // 5 seconds cooldown between charges
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setDecimalSeparator('.');
        COIN_FORMAT = new DecimalFormat("0.########", symbols);
    }
    // ====================================================
    // NBT KEYS
    // ====================================================
    private NamespacedKey rentItemKey;
    private NamespacedKey rentIdKey;
    private NamespacedKey rentOwnerKey;
    private NamespacedKey rentPriceKey;
    private NamespacedKey rentOriginalItemKey;
    private NamespacedKey rentRenterKey;
    private NamespacedKey rentStartTimeKey;
    private NamespacedKey rentLastChargeKey;
    private NamespacedKey rentNextChargeKey;
    private NamespacedKey rentChargeCountKey;
    // ====================================================
    // CONFIGURATION
    // ====================================================
    private FileConfiguration config;
    private File usersFolder;
    private File rentalsFile;
    private File offlineInventoryFolder;
    private RentalsData rentalsData;
    private String serverCardId;
    private double taxRate;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;
    private long cooldownMs;
    private long clickCooldownMs;
    // ====================================================
    // COINCARD API
    // ====================================================
    private CoinCardAPI coinCardAPI;
    // ====================================================
    // PAYMENT QUEUE
    // ====================================================
    private final Queue<RentPayment> paymentQueue = new ConcurrentLinkedQueue<>();
    private final Map<String, PendingPayment> pendingPayments = new ConcurrentHashMap<>();
    private BukkitTask paymentProcessorTask;
    private final AtomicLong lastProcessTime = new AtomicLong(0);
    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(4);
    // ====================================================
    // ACTIVE RENTALS
    // ====================================================
    private final Map<String, ActiveRental> activeRentals = new ConcurrentHashMap<>();
    private final Map<String, RentalChargeHistory> chargeHistory = new ConcurrentHashMap<>();
    private BukkitTask rentCheckerTask;
    private final Map<UUID, List<String>> playerRentals = new ConcurrentHashMap<>();
    // ====================================================
    // CHARGE PROCESSING LOCK
    // ====================================================
    private final Set<String> processingCharges = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastChargeAttempt = new ConcurrentHashMap<>();
    // ====================================================
    // PLAYER SESSIONS
    // ====================================================
    private final Map<UUID, PlayerSession> playerSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> playerClickCooldown = new ConcurrentHashMap<>();
    // ====================================================
    // CACHE
    // ====================================================
    private final Map<UUID, String> cardCache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cardCacheTimestamp = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION = 5 * 60 * 1000;
    // ====================================================
    // MATERIAL BLACKLIST (consumíveis)
    // ====================================================
    private final Set<Material> consumableMaterials = new HashSet<>();
    // ====================================================
    // ON ENABLE / DISABLE
    // ====================================================
    @Override
    public void onEnable() {
        getLogger().info("=== Iniciando CoinRent v" + getDescription().getVersion() + " ===");
        try {
            // Initialize NBT keys
            rentItemKey = new NamespacedKey(this, "rent_item");
            rentIdKey = new NamespacedKey(this, "rent_id");
            rentOwnerKey = new NamespacedKey(this, "rent_owner");
            rentPriceKey = new NamespacedKey(this, "rent_price");
            rentOriginalItemKey = new NamespacedKey(this, "rent_original_item");
            rentRenterKey = new NamespacedKey(this, "rent_renter");
            rentStartTimeKey = new NamespacedKey(this, "rent_start_time");
            rentLastChargeKey = new NamespacedKey(this, "rent_last_charge");
            rentNextChargeKey = new NamespacedKey(this, "rent_next_charge");
            rentChargeCountKey = new NamespacedKey(this, "rent_charge_count");
            getLogger().info("NBT keys initialized");
            // Check CoinCard dependency
            if (!setupCoinCardAPI()) {
                getLogger().severe("CoinCard plugin not found! Disabling CoinRent...");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            getLogger().info("CoinCard API connected successfully");
            // Create data folder
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }
            // Create config
            saveDefaultConfig();
            loadConfig();
            // Setup folders and files
            setupFolders();
            loadRentalsData();
            loadActiveRentals();
            // Setup consumable materials
            setupConsumableMaterials();
            // Register events
            getServer().getPluginManager().registerEvents(this, this);
            // Register commands
            Objects.requireNonNull(getCommand("crent")).setExecutor(new RentCommand());
            Objects.requireNonNull(getCommand("crent")).setTabCompleter(new RentCommand());
            // Start processors
            startPaymentProcessor();
            startRentChecker();
            COIN_FORMAT.setRoundingMode(RoundingMode.DOWN);
            COIN_FORMAT.setMinimumFractionDigits(0);
            COIN_FORMAT.setMaximumFractionDigits(8);
            getLogger().info("=== CoinRent v" + getDescription().getVersion() + " enabled successfully! ===");
            getLogger().info("Loaded " + rentalsData.rentals.size() + " rentals from database.");
            getLogger().info("Active rentals: " + activeRentals.size());
        } catch (Exception e) {
            getLogger().severe("FATAL ERROR ENABLING PLUGIN: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    @Override
    public void onDisable() {
        getLogger().info("Disabling CoinRent...");
        if (paymentProcessorTask != null) paymentProcessorTask.cancel();
        if (rentCheckerTask != null) rentCheckerTask.cancel();
        // Salvamentos SÍNCRONOS no disable para garantir que tudo seja escrito antes de shutdown
        saveRentalsDataSync();
        saveActiveRentalsSync();
        saveChargeHistorySync();
        // Return all active rentals to shop
        for (ActiveRental rental : activeRentals.values()) {
            returnItemToShop(rental, true);
        }
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncExecutor.shutdownNow();
        }
        cardCache.clear();
        cardCacheTimestamp.clear();
        playerClickCooldown.clear();
        playerSessions.clear();
        getLogger().info("CoinRent disabled.");
    }
    // ====================================================
    // SETUP METHODS
    // ====================================================
    private void setupConsumableMaterials() {
        // Comidas
        for (Material material : Material.values()) {
            if (material.isEdible()) {
                consumableMaterials.add(material);
            }
        }
        // Adicionar outros consumíveis
        consumableMaterials.add(Material.SNOWBALL);
        consumableMaterials.add(Material.EGG);
        consumableMaterials.add(Material.ENDER_PEARL);
        consumableMaterials.add(Material.ENDER_EYE);
        consumableMaterials.add(Material.FIREWORK_ROCKET);
        consumableMaterials.add(Material.FIREWORK_STAR);
        consumableMaterials.add(Material.EXPERIENCE_BOTTLE);
        consumableMaterials.add(Material.POTION);
        consumableMaterials.add(Material.SPLASH_POTION);
        consumableMaterials.add(Material.LINGERING_POTION);
        consumableMaterials.add(Material.TIPPED_ARROW);
        consumableMaterials.add(Material.SPECTRAL_ARROW);
    }
    private boolean isConsumable(ItemStack item) {
        if (item == null) return true;
        return consumableMaterials.contains(item.getType());
    }
    private void setupFolders() {
        usersFolder = new File(getDataFolder(), "users");
        if (!usersFolder.exists()) usersFolder.mkdirs();
        offlineInventoryFolder = new File(getDataFolder(), "offline_inventory");
        if (!offlineInventoryFolder.exists()) offlineInventoryFolder.mkdirs();
        rentalsFile = new File(getDataFolder(), "rentals.dat");
    }
    private void loadConfig() {
        reloadConfig();
        config = getConfig();
        config.addDefault("ServerCard", "");
        config.addDefault("Tax", 0.1);
        config.addDefault("Min", 0.00000001);
        config.addDefault("Max", 1000.0);
        config.addDefault("Cooldown", 1000);
        config.options().copyDefaults(true);
        saveConfig();
        serverCardId = config.getString("ServerCard", "");
        taxRate = config.getDouble("Tax", 0.1);
        minPrice = BigDecimal.valueOf(config.getDouble("Min", 0.00000001));
        maxPrice = BigDecimal.valueOf(config.getDouble("Max", 1000.0));
        cooldownMs = config.getLong("Cooldown", 1000);
        clickCooldownMs = cooldownMs;
    }
    // ====================================================
    // COINCARD API SETUP
    // ====================================================
    private boolean setupCoinCardAPI() {
        try {
            RegisteredServiceProvider<CoinCardAPI> provider =
                getServer().getServicesManager().getRegistration(CoinCardAPI.class);
            if (provider == null) return false;
            coinCardAPI = provider.getProvider();
            return coinCardAPI != null;
        } catch (Exception e) {
            getLogger().severe("Failed to setup CoinCard API: " + e.getMessage());
            return false;
        }
    }
    // ====================================================
    // COINCARD HELPERS
    // ====================================================
    private boolean hasPlayerCard(UUID uuid) {
        if (uuid == null) return false;
        Long cachedTime = cardCacheTimestamp.get(uuid);
        if (cachedTime != null && (System.currentTimeMillis() - cachedTime) < CACHE_DURATION) {
            return cardCache.containsKey(uuid);
        }
        boolean hasCard = coinCardAPI.hasCard(uuid);
        if (hasCard) {
            String cardId = coinCardAPI.getPlayerCard(uuid);
            if (cardId != null && !cardId.isEmpty()) {
                cardCache.put(uuid, cardId);
                cardCacheTimestamp.put(uuid, System.currentTimeMillis());
            }
        }
        return hasCard;
    }
    private String getPlayerCardId(UUID uuid) {
        if (uuid == null) return null;
        Long cachedTime = cardCacheTimestamp.get(uuid);
        if (cachedTime != null && (System.currentTimeMillis() - cachedTime) < CACHE_DURATION) {
            return cardCache.get(uuid);
        }
        String cardId = coinCardAPI.getPlayerCard(uuid);
        if (cardId != null && !cardId.isEmpty()) {
            cardCache.put(uuid, cardId);
            cardCacheTimestamp.put(uuid, System.currentTimeMillis());
        }
        return cardId;
    }
    private void checkPlayerBalance(String cardId, BalanceCheckCallback callback) {
        if (cardId == null || cardId.isEmpty()) {
            callback.onFailure("Invalid card ID");
            return;
        }
        coinCardAPI.getBalance(cardId, new BalanceCallback() {
            @Override
            public void onResult(double balance, String error) {
                if (error != null && !error.isEmpty()) {
                    callback.onFailure(error);
                } else {
                    callback.onSuccess(BigDecimal.valueOf(balance));
                }
            }
        });
    }
    // ====================================================
    // OFFLINE INVENTORY MANAGEMENT (já era assíncrono)
    // ====================================================
    private void saveOfflineInventory(UUID uuid, PlayerInventory inventory) {
        asyncExecutor.submit(() -> {
            File inventoryFile = new File(offlineInventoryFolder, uuid.toString() + ".dat");
            try {
                JsonObject jsonObject = new JsonObject();
                JsonArray itemsArray = new JsonArray();
                // Save main inventory (36 slots)
                for (int i = 0; i < 36; i++) {
                    ItemStack item = inventory.getItem(i);
                    if (item != null && item.getType() != Material.AIR) {
                        JsonObject itemObj = new JsonObject();
                        itemObj.addProperty("slot", i);
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        try (BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
                            boos.writeObject(item);
                        }
                        itemObj.addProperty("item", Base64.getEncoder().encodeToString(baos.toByteArray()));
                        itemsArray.add(itemObj);
                    }
                }
                // Save armor and offhand
                JsonArray armorArray = new JsonArray();
                for (int i = 36; i < 40; i++) {
                    ItemStack item = inventory.getItem(i);
                    if (item != null && item.getType() != Material.AIR) {
                        JsonObject itemObj = new JsonObject();
                        itemObj.addProperty("slot", i);
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        try (BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
                            boos.writeObject(item);
                        }
                        itemObj.addProperty("item", Base64.getEncoder().encodeToString(baos.toByteArray()));
                        armorArray.add(itemObj);
                    }
                }
                // Offhand
                ItemStack offhand = inventory.getItem(40);
                if (offhand != null && offhand.getType() != Material.AIR) {
                    JsonObject itemObj = new JsonObject();
                    itemObj.addProperty("slot", 40);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try (BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
                        boos.writeObject(offhand);
                    }
                    itemObj.addProperty("item", Base64.getEncoder().encodeToString(baos.toByteArray()));
                    armorArray.add(itemObj);
                }
                jsonObject.add("items", itemsArray);
                jsonObject.add("armor", armorArray);
                jsonObject.addProperty("lastSave", System.currentTimeMillis());
                try (Writer writer = new FileWriter(inventoryFile)) {
                    GSON.toJson(jsonObject, writer);
                }
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Failed to save offline inventory for " + uuid, e);
            }
        });
    }

    // NOVO: Verifica presença do item alugado no inventário offline SEM remover (usado em renewals)
    private void checkOfflineInventoryHasRental(ActiveRental rental, RentalCheckCallback callback) {
        asyncExecutor.submit(() -> {
            File inventoryFile = new File(offlineInventoryFolder, rental.renterUuid.toString() + ".dat");
            if (!inventoryFile.exists()) {
                callback.onResult(false, null);
                return;
            }
            try (Reader reader = new FileReader(inventoryFile)) {
                JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                // Check main inventory
                if (jsonObject.has("items")) {
                    JsonArray itemsArray = jsonObject.getAsJsonArray("items");
                    for (JsonElement element : itemsArray) {
                        JsonObject itemObj = element.getAsJsonObject();
                        String itemBase64 = itemObj.get("item").getAsString();
                        byte[] data = Base64.getDecoder().decode(itemBase64);
                        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
                             BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
                            ItemStack item = (ItemStack) bois.readObject();
                            if (item != null && rental.id.equals(getRentalId(item))) {
                                callback.onResult(true, item);
                                return;
                            }
                        } catch (Exception e) {
                            getLogger().warning("Failed to decode offline inventory item: " + e.getMessage());
                        }
                    }
                }
                // Check armor and offhand
                if (jsonObject.has("armor")) {
                    JsonArray armorArray = jsonObject.getAsJsonArray("armor");
                    for (JsonElement element : armorArray) {
                        JsonObject itemObj = element.getAsJsonObject();
                        String itemBase64 = itemObj.get("item").getAsString();
                        byte[] data = Base64.getDecoder().decode(itemBase64);
                        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
                             BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
                            ItemStack item = (ItemStack) bois.readObject();
                            if (item != null && rental.id.equals(getRentalId(item))) {
                                callback.onResult(true, item);
                                return;
                            }
                        } catch (Exception e) {
                            getLogger().warning("Failed to decode offline inventory item: " + e.getMessage());
                        }
                    }
                }
                callback.onResult(false, null);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to check offline inventory for " + rental.renterUuid, e);
                callback.onResult(false, null);
            }
        });
    }

    private void checkOfflineInventoryForRental(ActiveRental rental, RentalCheckCallback callback) {
        asyncExecutor.submit(() -> {
            File inventoryFile = new File(offlineInventoryFolder, rental.renterUuid.toString() + ".dat");
            if (!inventoryFile.exists()) {
                callback.onResult(false, null);
                return;
            }
            try (Reader reader = new FileReader(inventoryFile)) {
                JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                // Check main inventory
                if (jsonObject.has("items")) {
                    JsonArray itemsArray = jsonObject.getAsJsonArray("items");
                    for (JsonElement element : itemsArray) {
                        JsonObject itemObj = element.getAsJsonObject();
                        String itemBase64 = itemObj.get("item").getAsString();
                        byte[] data = Base64.getDecoder().decode(itemBase64);
                        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
                             BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
                            ItemStack item = (ItemStack) bois.readObject();
                            if (item != null && rental.id.equals(getRentalId(item))) {
                                removeItemFromOfflineInventory(rental.renterUuid, itemObj.get("slot").getAsInt(), item);
                                callback.onResult(true, item);
                                return;
                            }
                        } catch (Exception e) {
                            getLogger().warning("Failed to decode offline inventory item: " + e.getMessage());
                        }
                    }
                }
                // Check armor and offhand
                if (jsonObject.has("armor")) {
                    JsonArray armorArray = jsonObject.getAsJsonArray("armor");
                    for (JsonElement element : armorArray) {
                        JsonObject itemObj = element.getAsJsonObject();
                        String itemBase64 = itemObj.get("item").getAsString();
                        byte[] data = Base64.getDecoder().decode(itemBase64);
                        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
                             BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
                            ItemStack item = (ItemStack) bois.readObject();
                            if (item != null && rental.id.equals(getRentalId(item))) {
                                removeItemFromOfflineInventory(rental.renterUuid, itemObj.get("slot").getAsInt(), item);
                                callback.onResult(true, item);
                                return;
                            }
                        } catch (Exception e) {
                            getLogger().warning("Failed to decode offline inventory item: " + e.getMessage());
                        }
                    }
                }
                callback.onResult(false, null);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to check offline inventory for " + rental.renterUuid, e);
                callback.onResult(false, null);
            }
        });
    }

    private void removeItemFromOfflineInventory(UUID uuid, int slot, ItemStack itemToRemove) {
        asyncExecutor.submit(() -> {
            File inventoryFile = new File(offlineInventoryFolder, uuid.toString() + ".dat");
            if (!inventoryFile.exists()) return;
            try {
                JsonObject jsonObject;
                try (Reader reader = new FileReader(inventoryFile)) {
                    jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                }
                // Remove from main inventory
                if (slot < 36 && jsonObject.has("items")) {
                    JsonArray itemsArray = jsonObject.getAsJsonArray("items");
                    JsonArray newItemsArray = new JsonArray();
                    for (JsonElement element : itemsArray) {
                        JsonObject itemObj = element.getAsJsonObject();
                        int itemSlot = itemObj.get("slot").getAsInt();
                        if (itemSlot != slot) {
                            newItemsArray.add(itemObj);
                        }
                    }
                    jsonObject.add("items", newItemsArray);
                }
                // Remove from armor/offhand
                if (slot >= 36 && jsonObject.has("armor")) {
                    JsonArray armorArray = jsonObject.getAsJsonArray("armor");
                    JsonArray newArmorArray = new JsonArray();
                    for (JsonElement element : armorArray) {
                        JsonObject itemObj = element.getAsJsonObject();
                        int itemSlot = itemObj.get("slot").getAsInt();
                        if (itemSlot != slot) {
                            newArmorArray.add(itemObj);
                        }
                    }
                    jsonObject.add("armor", newArmorArray);
                }
                try (Writer writer = new FileWriter(inventoryFile)) {
                    GSON.toJson(jsonObject, writer);
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to remove item from offline inventory for " + uuid, e);
            }
        });
    }
    private void updateOfflineInventoryItem(UUID uuid, int slot, ItemStack newItem) {
        asyncExecutor.submit(() -> {
            File inventoryFile = new File(offlineInventoryFolder, uuid.toString() + ".dat");
            if (!inventoryFile.exists()) return;
            try {
                JsonObject jsonObject;
                try (Reader reader = new FileReader(inventoryFile)) {
                    jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                }
                JsonArray targetArray = slot < 36 ? jsonObject.getAsJsonArray("items") : jsonObject.getAsJsonArray("armor");
                JsonArray newArray = new JsonArray();
                boolean updated = false;
                for (JsonElement element : targetArray) {
                    JsonObject itemObj = element.getAsJsonObject();
                    int itemSlot = itemObj.get("slot").getAsInt();
                    if (itemSlot == slot) {
                        if (newItem != null && newItem.getType() != Material.AIR) {
                            JsonObject newItemObj = new JsonObject();
                            newItemObj.addProperty("slot", slot);
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            try (BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
                                boos.writeObject(newItem);
                            }
                            newItemObj.addProperty("item", Base64.getEncoder().encodeToString(baos.toByteArray()));
                            newArray.add(newItemObj);
                        }
                        updated = true;
                    } else {
                        newArray.add(itemObj);
                    }
                }
                if (!updated && newItem != null && newItem.getType() != Material.AIR) {
                    JsonObject newItemObj = new JsonObject();
                    newItemObj.addProperty("slot", slot);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try (BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
                        boos.writeObject(newItem);
                    }
                    newItemObj.addProperty("item", Base64.getEncoder().encodeToString(baos.toByteArray()));
                    newArray.add(newItemObj);
                }
                if (slot < 36) {
                    jsonObject.add("items", newArray);
                } else {
                    jsonObject.add("armor", newArray);
                }
                try (Writer writer = new FileWriter(inventoryFile)) {
                    GSON.toJson(jsonObject, writer);
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to update offline inventory for " + uuid, e);
            }
        });
    }
    private void saveItemToOfflineInventory(UUID uuid, ItemStack item) {
        asyncExecutor.submit(() -> {
            File inventoryFile = new File(offlineInventoryFolder, uuid.toString() + ".dat");
            try {
                JsonObject jsonObject;
                if (inventoryFile.exists()) {
                    try (Reader reader = new FileReader(inventoryFile)) {
                        jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                    }
                } else {
                    jsonObject = new JsonObject();
                    jsonObject.add("items", new JsonArray());
                    jsonObject.add("armor", new JsonArray());
                }
                JsonArray itemsArray = jsonObject.getAsJsonArray("items");
                Set<Integer> usedSlots = new HashSet<>();
                for (JsonElement element : itemsArray) {
                    JsonObject itemObj = element.getAsJsonObject();
                    usedSlots.add(itemObj.get("slot").getAsInt());
                }
                int targetSlot = -1;
                for (int i = 0; i < 36; i++) {
                    if (!usedSlots.contains(i)) {
                        targetSlot = i;
                        break;
                    }
                }
                if (targetSlot == -1) {
                    getLogger().warning("Offline inventory full for " + uuid + ", item lost!");
                    return;
                }
                JsonObject newItemObj = new JsonObject();
                newItemObj.addProperty("slot", targetSlot);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
                    boos.writeObject(item);
                }
                newItemObj.addProperty("item", Base64.getEncoder().encodeToString(baos.toByteArray()));
                itemsArray.add(newItemObj);
                jsonObject.add("items", itemsArray);
                try (Writer writer = new FileWriter(inventoryFile)) {
                    GSON.toJson(jsonObject, writer);
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to save item to offline inventory for " + uuid, e);
            }
        });
    }
    private void removeOfflineInventoryItem(UUID uuid, String rentalId) {
        asyncExecutor.submit(() -> {
            File inventoryFile = new File(offlineInventoryFolder, uuid.toString() + ".dat");
            if (!inventoryFile.exists()) return;
            try {
                JsonObject jsonObject;
                try (Reader reader = new FileReader(inventoryFile)) {
                    jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                }
                boolean modified = false;
                if (jsonObject.has("items")) {
                    JsonArray itemsArray = jsonObject.getAsJsonArray("items");
                    JsonArray newItemsArray = new JsonArray();
                    for (JsonElement element : itemsArray) {
                        JsonObject itemObj = element.getAsJsonObject();
                        String itemBase64 = itemObj.get("item").getAsString();
                        byte[] data = Base64.getDecoder().decode(itemBase64);
                        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
                             BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
                            ItemStack item = (ItemStack) bois.readObject();
                            if (item != null && !rentalId.equals(getRentalId(item))) {
                                newItemsArray.add(itemObj);
                            } else {
                                modified = true;
                            }
                        } catch (Exception e) {
                            newItemsArray.add(itemObj);
                        }
                    }
                    if (modified) {
                        jsonObject.add("items", newItemsArray);
                    }
                }
                if (jsonObject.has("armor")) {
                    JsonArray armorArray = jsonObject.getAsJsonArray("armor");
                    JsonArray newArmorArray = new JsonArray();
                    boolean armorModified = false;
                    for (JsonElement element : armorArray) {
                        JsonObject itemObj = element.getAsJsonObject();
                        String itemBase64 = itemObj.get("item").getAsString();
                        byte[] data = Base64.getDecoder().decode(itemBase64);
                        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
                             BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
                            ItemStack item = (ItemStack) bois.readObject();
                            if (item != null && !rentalId.equals(getRentalId(item))) {
                                newArmorArray.add(itemObj);
                            } else {
                                armorModified = true;
                            }
                        } catch (Exception e) {
                            newArmorArray.add(itemObj);
                        }
                    }
                    if (armorModified) {
                        jsonObject.add("armor", newArmorArray);
                    }
                    modified = modified || armorModified;
                }
                if (modified) {
                    try (Writer writer = new FileWriter(inventoryFile)) {
                        GSON.toJson(jsonObject, writer);
                    }
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to remove item from offline inventory for " + uuid, e);
            }
        });
    }

    // NOVO: Atualiza o item alugado no arquivo offline (preserva slot, durabilidade, NBT completo)
    private void replaceRentalItemInOffline(UUID uuid, ItemStack updatedItem, String rentalId) {
        File inventoryFile = new File(offlineInventoryFolder, uuid.toString() + ".dat");
        if (!inventoryFile.exists()) return;
        try {
            JsonObject jsonObject;
            try (Reader reader = new FileReader(inventoryFile)) {
                jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
            }
            boolean modified = false;
            String[] arrayNames = {"items", "armor"};
            for (String arrayName : arrayNames) {
                if (jsonObject.has(arrayName)) {
                    JsonArray array = jsonObject.getAsJsonArray(arrayName);
                    JsonArray newArray = new JsonArray();
                    for (JsonElement element : array) {
                        JsonObject itemObj = element.getAsJsonObject();
                        String itemBase64 = itemObj.get("item").getAsString();
                        byte[] data = Base64.getDecoder().decode(itemBase64);
                        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
                             BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
                            ItemStack item = (ItemStack) bois.readObject();
                            if (item != null && rentalId.equals(getRentalId(item))) {
                                // Substitui pelo item atualizado (com novos timestamps de cobrança)
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                try (BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
                                    boos.writeObject(updatedItem);
                                }
                                itemObj.addProperty("item", Base64.getEncoder().encodeToString(baos.toByteArray()));
                                modified = true;
                            }
                        } catch (Exception e) {
                            // mantém o item original se falhar
                        }
                        newArray.add(itemObj);
                    }
                    jsonObject.add(arrayName, newArray);
                }
            }
            if (modified) {
                try (Writer writer = new FileWriter(inventoryFile)) {
                    GSON.toJson(jsonObject, writer);
                }
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to replace rental item in offline inventory for " + uuid, e);
        }
    }
    // ====================================================
    // RENTAL DATA MANAGEMENT - SALVAMENTOS TOTALMENTE ASSÍNCRONOS
    // ====================================================
    private void loadRentalsData() {
        rentalsData = new RentalsData();
        if (rentalsFile.exists()) {
            try (Reader reader = new FileReader(rentalsFile)) {
                JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                if (jsonObject.has("rentals")) {
                    JsonArray rentalsArray = jsonObject.getAsJsonArray("rentals");
                    for (JsonElement element : rentalsArray) {
                        JsonObject rentalObj = element.getAsJsonObject();
                        String id = rentalObj.get("id").getAsString();
                        UUID ownerUuid = UUID.fromString(rentalObj.get("ownerUuid").getAsString());
                        String ownerName = rentalObj.get("ownerName").getAsString();
                        String ownerShopName = rentalObj.has("ownerShopName") ? rentalObj.get("ownerShopName").getAsString() : ownerName + "'s shop";
                        BigDecimal price = new BigDecimal(rentalObj.get("price").getAsString());
                        long listedAt = rentalObj.get("listedAt").getAsLong();
                        ItemStack item = null;
                        if (rentalObj.has("item")) {
                            String itemBase64 = rentalObj.get("item").getAsString();
                            byte[] data = Base64.getDecoder().decode(itemBase64);
                            try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
                                 BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
                                item = (ItemStack) bois.readObject();
                            } catch (Exception e) {
                                getLogger().warning("Failed to decode item: " + e.getMessage());
                            }
                        }
                        if (item != null) {
                            RentalItem rental = new RentalItem(id, ownerUuid, ownerName, ownerShopName, item, price, listedAt);
                            rentalsData.rentals.add(rental);
                        }
                    }
                }
                getLogger().info("Loaded " + rentalsData.rentals.size() + " rentals from database.");
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to load rentals data", e);
                rentalsData = new RentalsData();
            }
        } else {
            rentalsData = new RentalsData();
            getLogger().info("Created new rentals data file.");
        }
        long cutoff = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000);
        rentalsData.rentals.removeIf(rental -> rental.listedAt < cutoff);
    }
    // WRAPPER ASSÍNCRONO - chamado em tempo de execução (não trava o servidor)
    private void saveRentalsData() {
        if (asyncExecutor.isShutdown()) return;
        asyncExecutor.submit(this::saveRentalsDataSync);
    }
    // Versão síncrona real (usada apenas no onDisable)
    private void saveRentalsDataSync() {
        try {
            JsonObject jsonObject = new JsonObject();
            JsonArray rentalsArray = new JsonArray();
            for (RentalItem rental : rentalsData.rentals) {
                JsonObject rentalObj = new JsonObject();
                rentalObj.addProperty("id", rental.id);
                rentalObj.addProperty("ownerUuid", rental.ownerUuid.toString());
                rentalObj.addProperty("ownerName", rental.ownerName);
                rentalObj.addProperty("ownerShopName", rental.ownerShopName);
                rentalObj.addProperty("price", rental.price.toString());
                rentalObj.addProperty("listedAt", rental.listedAt);
                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try (BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
                        boos.writeObject(rental.item);
                    }
                    rentalObj.addProperty("item", Base64.getEncoder().encodeToString(baos.toByteArray()));
                } catch (Exception e) {
                    getLogger().warning("Failed to encode item: " + e.getMessage());
                }
                rentalsArray.add(rentalObj);
            }
            jsonObject.add("rentals", rentalsArray);
            try (Writer writer = new FileWriter(rentalsFile)) {
                GSON.toJson(jsonObject, writer);
                writer.flush();
            }
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Failed to save rentals data", e);
        }
    }
    private void loadActiveRentals() {
        File activeFile = new File(getDataFolder(), "active_rentals.dat");
        if (activeFile.exists()) {
            try (Reader reader = new FileReader(activeFile)) {
                JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                if (jsonObject.has("active")) {
                    JsonArray activeArray = jsonObject.getAsJsonArray("active");
                    for (JsonElement element : activeArray) {
                        JsonObject activeObj = element.getAsJsonObject();
                        String id = activeObj.get("id").getAsString();
                        UUID ownerUuid = UUID.fromString(activeObj.get("ownerUuid").getAsString());
                        UUID renterUuid = UUID.fromString(activeObj.get("renterUuid").getAsString());
                        String renterName = activeObj.get("renterName").getAsString();
                        BigDecimal price = new BigDecimal(activeObj.get("price").getAsString());
                        long startTime = activeObj.get("startTime").getAsLong();
                        long lastChargeTime = activeObj.has("lastChargeTime") ? activeObj.get("lastChargeTime").getAsLong() : startTime;
                        long nextChargeTime = activeObj.has("nextChargeTime") ? activeObj.get("nextChargeTime").getAsLong() : startTime + MILLIS_PER_HOUR;
                        int chargeCount = activeObj.has("chargeCount") ? activeObj.get("chargeCount").getAsInt() : 0;
                        ItemStack originalItem = null;
                        if (activeObj.has("originalItem")) {
                            String itemBase64 = activeObj.get("originalItem").getAsString();
                            byte[] data = Base64.getDecoder().decode(itemBase64);
                            try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
                                 BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
                                originalItem = (ItemStack) bois.readObject();
                            } catch (Exception e) {
                                getLogger().warning("Failed to decode original item: " + e.getMessage());
                            }
                        }
                        ItemStack currentItem = null;
                        if (activeObj.has("currentItem")) {
                            String itemBase64 = activeObj.get("currentItem").getAsString();
                            byte[] data = Base64.getDecoder().decode(itemBase64);
                            try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
                                 BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
                                currentItem = (ItemStack) bois.readObject();
                            } catch (Exception e) {
                                getLogger().warning("Failed to decode current item: " + e.getMessage());
                            }
                        }
                        if (originalItem != null && currentItem != null) {
                            ActiveRental rental = new ActiveRental(id, ownerUuid, renterUuid, renterName,
                                    originalItem, currentItem, price, startTime);
                            rental.lastChargeTime = lastChargeTime;
                            rental.nextChargeTime = nextChargeTime;
                            rental.chargeCount = chargeCount;
                            activeRentals.put(id, rental);
                            playerRentals.computeIfAbsent(renterUuid, k -> new ArrayList<>()).add(id);
                        }
                    }
                }
                getLogger().info("Loaded " + activeRentals.size() + " active rentals.");
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to load active rentals", e);
            }
        }
        loadChargeHistory();
    }
    // WRAPPER ASSÍNCRONO
    private void saveActiveRentals() {
        if (asyncExecutor.isShutdown()) return;
        asyncExecutor.submit(this::saveActiveRentalsSync);
    }
    // Versão síncrona real (onDisable)
    private void saveActiveRentalsSync() {
        File activeFile = new File(getDataFolder(), "active_rentals.dat");
        try {
            JsonObject jsonObject = new JsonObject();
            JsonArray activeArray = new JsonArray();
            for (ActiveRental rental : activeRentals.values()) {
                JsonObject activeObj = new JsonObject();
                activeObj.addProperty("id", rental.id);
                activeObj.addProperty("ownerUuid", rental.ownerUuid.toString());
                activeObj.addProperty("renterUuid", rental.renterUuid.toString());
                activeObj.addProperty("renterName", rental.renterName);
                activeObj.addProperty("price", rental.price.toString());
                activeObj.addProperty("startTime", rental.startTime);
                activeObj.addProperty("lastChargeTime", rental.lastChargeTime);
                activeObj.addProperty("nextChargeTime", rental.nextChargeTime);
                activeObj.addProperty("chargeCount", rental.chargeCount);
                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try (BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
                        boos.writeObject(rental.originalItem);
                    }
                    activeObj.addProperty("originalItem", Base64.getEncoder().encodeToString(baos.toByteArray()));
                    baos = new ByteArrayOutputStream();
                    try (BukkitObjectOutputStream boos2 = new BukkitObjectOutputStream(baos)) {
                        boos2.writeObject(rental.currentItem);
                    }
                    activeObj.addProperty("currentItem", Base64.getEncoder().encodeToString(baos.toByteArray()));
                } catch (Exception e) {
                    getLogger().warning("Failed to encode active rental item: " + e.getMessage());
                }
                activeArray.add(activeObj);
            }
            jsonObject.add("active", activeArray);
            try (Writer writer = new FileWriter(activeFile)) {
                GSON.toJson(jsonObject, writer);
                writer.flush();
            }
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Failed to save active rentals", e);
        }
    }
    private void loadChargeHistory() {
        File historyFile = new File(getDataFolder(), "charge_history.dat");
        if (historyFile.exists()) {
            try (Reader reader = new FileReader(historyFile)) {
                JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                if (jsonObject.has("history")) {
                    JsonArray historyArray = jsonObject.getAsJsonArray("history");
                    for (JsonElement element : historyArray) {
                        JsonObject historyObj = element.getAsJsonObject();
                        String rentalId = historyObj.get("rentalId").getAsString();
                        RentalChargeHistory history = new RentalChargeHistory(rentalId);
                        if (historyObj.has("charges")) {
                            JsonArray chargesArray = historyObj.getAsJsonArray("charges");
                            for (JsonElement chargeElement : chargesArray) {
                                JsonObject chargeObj = chargeElement.getAsJsonObject();
                                long timestamp = chargeObj.get("timestamp").getAsLong();
                                BigDecimal amount = new BigDecimal(chargeObj.get("amount").getAsString());
                                boolean success = chargeObj.get("success").getAsBoolean();
                                history.charges.add(new ChargeRecord(timestamp, amount, success));
                            }
                        }
                        chargeHistory.put(rentalId, history);
                    }
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to load charge history", e);
            }
        }
    }
    // WRAPPER ASSÍNCRONO
    private void saveChargeHistory() {
        if (asyncExecutor.isShutdown()) return;
        asyncExecutor.submit(this::saveChargeHistorySync);
    }
    // Versão síncrona real (onDisable)
    private void saveChargeHistorySync() {
        File historyFile = new File(getDataFolder(), "charge_history.dat");
        try {
            JsonObject jsonObject = new JsonObject();
            JsonArray historyArray = new JsonArray();
            for (Map.Entry<String, RentalChargeHistory> entry : chargeHistory.entrySet()) {
                JsonObject historyObj = new JsonObject();
                historyObj.addProperty("rentalId", entry.getKey());
                JsonArray chargesArray = new JsonArray();
                for (ChargeRecord record : entry.getValue().charges) {
                    JsonObject chargeObj = new JsonObject();
                    chargeObj.addProperty("timestamp", record.timestamp);
                    chargeObj.addProperty("amount", record.amount.toString());
                    chargeObj.addProperty("success", record.success);
                    chargesArray.add(chargeObj);
                }
                historyObj.add("charges", chargesArray);
                historyArray.add(historyObj);
            }
            jsonObject.add("history", historyArray);
            try (Writer writer = new FileWriter(historyFile)) {
                GSON.toJson(jsonObject, writer);
                writer.flush();
            }
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Failed to save charge history", e);
        }
    }
    // ====================================================
    // PLAYER DATA
    // ====================================================
    private PlayerData getPlayerData(UUID uuid) {
        if (uuid == null) return null;
        File playerFile = new File(usersFolder, uuid.toString() + ".yml");
        YamlConfiguration yamlConfig = YamlConfiguration.loadConfiguration(playerFile);
        String name = yamlConfig.getString("Name");
        if (name == null || name.isEmpty()) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            name = (offlinePlayer.getName() != null ? offlinePlayer.getName() : "Unknown") + "'s rentals";
        }
        return new PlayerData(uuid, name, playerFile);
    }
    private void savePlayerData(PlayerData data) {
        if (data == null || data.uuid == null) return;
        try {
            if (!usersFolder.exists()) usersFolder.mkdirs();
            YamlConfiguration yamlConfig = new YamlConfiguration();
            yamlConfig.set("Name", data.shopName);
            yamlConfig.save(data.file);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Failed to save player data for " + data.uuid, e);
        }
    }
    // ====================================================
    // RENTAL LOGIC (agora com salvamentos assíncronos)
    // ====================================================
    private boolean createRental(Player player, ItemStack item, BigDecimal price) {
        if (player == null || item == null || price == null) return false;
        String existingRentalId = getRentalId(item);
        if (existingRentalId != null && activeRentals.containsKey(existingRentalId)) {
            player.sendMessage(ChatColor.RED + "This item is currently being rented by someone and cannot be listed!");
            return false;
        }
        for (RentalItem rental : rentalsData.rentals) {
            if (rental.item != null && rental.item.isSimilar(item) && rental.item.getType() == item.getType()) {
                player.sendMessage(ChatColor.RED + "This item is already listed for rent!");
                return false;
            }
        }
        if (price.compareTo(minPrice) < 0 || price.compareTo(maxPrice) > 0) {
            player.sendMessage(ChatColor.RED + "Price must be between " + formatCoin(minPrice) +
                    " and " + formatCoin(maxPrice));
            return false;
        }
        if (isConsumable(item)) {
            player.sendMessage(ChatColor.RED + "Consumable items cannot be rented!");
            return false;
        }
        if (item.getAmount() != 1) {
            player.sendMessage(ChatColor.RED + "Only single items can be rented!");
            return false;
        }
        if (!hasPlayerCard(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You don't have a card set! Use /coin card <card> to set your card.");
            return false;
        }
        String rentalId = UUID.randomUUID().toString();
        PlayerData data = getPlayerData(player.getUniqueId());
        ItemStack markedItem = item.clone();
        markedItem = markAsRentalItem(markedItem, rentalId, player.getUniqueId(), price, item.clone());
        RentalItem rental = new RentalItem(
                rentalId,
                player.getUniqueId(),
                player.getName(),
                data.shopName != null ? data.shopName : player.getName() + "'s rentals",
                markedItem,
                price,
                System.currentTimeMillis()
        );
        rentalsData.rentals.add(rental);
        // SALVAMENTO ASSÍNCRONO - NÃO TRAVA O SERVIDOR
        saveRentalsData();
        // Broadcast announcement (continua no main thread, mas sem I/O)
        String itemName = item.getType().toString();
        if (item.getItemMeta() != null && item.getItemMeta().hasDisplayName()) {
            itemName = item.getItemMeta().getDisplayName();
        }
        BigDecimal totalWithTax = price.add(price.multiply(BigDecimal.valueOf(taxRate)))
                .setScale(8, RoundingMode.DOWN);
        String announcement = ChatColor.GOLD + "= " + ChatColor.GREEN + "NEW RENTAL " + ChatColor.GOLD + "=\n" +
                ChatColor.YELLOW + (data.shopName != null ? data.shopName : player.getName() + "'s rentals") +
                ChatColor.GRAY + " is renting " +
                ChatColor.AQUA + itemName + "\n" +
                ChatColor.GRAY + "Price per hour: " + ChatColor.GREEN + formatCoin(price) +
                ChatColor.GRAY + " (Total with " + (int)(taxRate * 100) + "% tax: " +
                ChatColor.YELLOW + formatCoin(totalWithTax) + ChatColor.GRAY + ")";
        Bukkit.broadcastMessage(announcement);
        player.sendMessage(ChatColor.GREEN + "Item listed for rent at " + formatCoin(price) + " coins/hour!");
        return true;
    }
    private boolean rentItem(Player renter, RentalItem rental) {
        if (renter == null || rental == null) return false;
        if (isRentalItem(rental.item)) {
            String rentalId = getRentalId(rental.item);
            if (rentalId != null && activeRentals.containsKey(rentalId)) {
                renter.sendMessage(ChatColor.RED + "This item is already being rented by someone!");
                return false;
            }
        }
        if (rental.ownerUuid.equals(renter.getUniqueId())) {
            renter.sendMessage(ChatColor.RED + "You cannot rent your own item!");
            return false;
        }
        if (!hasPlayerCard(renter.getUniqueId())) {
            renter.sendMessage(ChatColor.RED + "You don't have a card set! Use /coin card <card> to set your card.");
            return false;
        }
        for (ActiveRental active : activeRentals.values()) {
            if (active.id.equals(rental.id)) {
                renter.sendMessage(ChatColor.RED + "This item is already being rented!");
                return false;
            }
        }
        String renterCardId = getPlayerCardId(renter.getUniqueId());
        if (renterCardId == null || renterCardId.isEmpty()) {
            renter.sendMessage(ChatColor.RED + "Could not retrieve your card ID!");
            return false;
        }
        String ownerCardId = getPlayerCardId(rental.ownerUuid);
        if (ownerCardId == null || ownerCardId.isEmpty()) {
            renter.sendMessage(ChatColor.RED + "The owner doesn't have a card set!");
            return false;
        }
        BigDecimal totalPrice = rental.price;
        if (taxRate > 0) {
            totalPrice = totalPrice.add(totalPrice.multiply(BigDecimal.valueOf(taxRate)))
                    .setScale(8, RoundingMode.DOWN);
        }
        final BigDecimal finalTotalPrice = totalPrice;
        final String finalRenterCardId = renterCardId;
        final String finalOwnerCardId = ownerCardId;
        checkPlayerBalance(renterCardId, new BalanceCheckCallback() {
            @Override
            public void onSuccess(BigDecimal balance) {
                if (balance.compareTo(finalTotalPrice) < 0) {
                    renter.sendMessage(ChatColor.RED + "Insufficient balance! You need " +
                            formatCoin(finalTotalPrice) + " but have " + formatCoin(balance));
                    return;
                }
                String paymentId = UUID.randomUUID().toString();
                RentPayment payment = new RentPayment(paymentId, finalRenterCardId, finalOwnerCardId,
                        rental.price, rental.id, rental.ownerUuid, renter.getUniqueId());
                PendingPayment pending = new PendingPayment(paymentId, renter.getUniqueId(),
                        rental.ownerUuid, rental.id, rental.item.clone(), rental.price);
                pendingPayments.put(paymentId, pending);
                paymentQueue.add(payment);
                renter.sendMessage(ChatColor.GREEN + "✓ RENTAL QUEUED!");
                renter.sendMessage(ChatColor.GRAY + "Price: " + ChatColor.YELLOW + formatCoin(finalTotalPrice));
                renter.sendMessage(ChatColor.GRAY + "You will receive the item once the payment completes.");
                // Remove from listings
                rentalsData.rentals.remove(rental);
                // SALVAMENTO ASSÍNCRONO
                saveRentalsData();
                refreshAllOpenRentals();
            }
            @Override
            public void onFailure(String error) {
                renter.sendMessage(ChatColor.RED + "Failed to check balance: " + (error != null ? error : "Unknown error"));
            }
        });
        return true;
    }

    // ====================================================
    // COMPLETAR RENTAL (AGORA SUPORTA INITIAL + RECURRING SEM DUPLICAR)
    // ====================================================
    private void completeRental(PendingPayment pending, String txId) {
        if (pending == null) return;

        // === RECURRING CHARGE SUCCESS (evita duplicação) ===
        ActiveRental existingRental = activeRentals.get(pending.rentalId);
        if (existingRental != null) {
            // Atualiza o item com novos timestamps de cobrança (mantém durabilidade, NBT, mod items etc.)
            ItemStack updatedItem = markAsRentedItem(pending.item.clone(), pending.rentalId, pending.renterUuid,
                    pending.ownerUuid, pending.price, existingRental.startTime,
                    existingRental.lastChargeTime, existingRental.nextChargeTime, existingRental.chargeCount);
            existingRental.currentItem = updatedItem.clone();

            Player renter = Bukkit.getPlayer(pending.renterUuid);
            if (renter != null && renter.isOnline()) {
                boolean found = false;
                for (int i = 0; i < renter.getInventory().getSize(); i++) {
                    ItemStack it = renter.getInventory().getItem(i);
                    if (it != null && pending.rentalId.equals(getRentalId(it))) {
                        renter.getInventory().setItem(i, updatedItem.clone());
                        found = true;
                        break;
                    }
                }
                if (found) {
                    renter.updateInventory();
                    renter.sendMessage(ChatColor.GREEN + "✓ RENTAL RENEWED SUCCESSFULLY!");
                    renter.sendMessage(ChatColor.GRAY + "Price per hour: " + ChatColor.YELLOW + formatCoin(pending.price));
                    renter.sendMessage(ChatColor.GRAY + "Next charge in 1 hour.");
                }
            } else {
                // Offline: atualiza o arquivo sem remover nem duplicar
                asyncExecutor.submit(() -> replaceRentalItemInOffline(pending.renterUuid, updatedItem, pending.rentalId));
            }

            recordCharge(pending.rentalId, pending.price, true);
            pendingPayments.remove(pending.id);
            getLogger().info("Recurring charge completed for rental " + pending.rentalId);
            return; // NÃO executa lógica de initial rental
        }

        // === INITIAL RENTAL (código original inalterado) ===
        long currentTime = System.currentTimeMillis();
        ItemStack rentedItem = pending.item.clone();
        rentedItem = markAsRentedItem(rentedItem, pending.rentalId, pending.renterUuid,
                pending.ownerUuid, pending.price, currentTime, currentTime, currentTime + MILLIS_PER_HOUR, 0);
        Player renter = Bukkit.getPlayer(pending.renterUuid);
        if (renter != null && renter.isOnline()) {
            HashMap<Integer, ItemStack> leftover = renter.getInventory().addItem(rentedItem);
            if (!leftover.isEmpty()) {
                for (ItemStack drop : leftover.values()) {
                    if (drop != null) {
                        renter.getWorld().dropItemNaturally(renter.getLocation(), drop);
                    }
                }
                renter.sendMessage(ChatColor.YELLOW + "Some items were dropped because your inventory was full!");
            }
            renter.sendMessage(ChatColor.GREEN + "✓ RENTAL SUCCESSFUL!");
            renter.sendMessage(ChatColor.GRAY + "Item: " + ChatColor.WHITE + getItemName(rentedItem));
            renter.sendMessage(ChatColor.GRAY + "Price per hour: " + ChatColor.YELLOW + formatCoin(pending.price));
            renter.sendMessage(ChatColor.GRAY + "You will be charged every hour automatically.");
            renter.sendMessage(ChatColor.GRAY + "Transaction: " + ChatColor.WHITE +
                (txId != null ? txId.substring(0, 8) + "..." : "unknown"));
            saveOfflineInventory(pending.renterUuid, renter.getInventory());
        } else {
            ItemStack offlineItem = rentedItem.clone();
            saveItemToOfflineInventory(pending.renterUuid, offlineItem);
        }
        ItemStack originalItem = rentedItem.clone();
        originalItem = removeRentalTags(originalItem);
        ActiveRental active = new ActiveRental(pending.rentalId, pending.ownerUuid, pending.renterUuid,
                renter != null ? renter.getName() : "Unknown", originalItem, rentedItem,
                pending.price, currentTime);
        active.lastChargeTime = currentTime;
        active.nextChargeTime = currentTime + MILLIS_PER_HOUR;
        active.chargeCount = 0;
        activeRentals.put(pending.rentalId, active);
        playerRentals.computeIfAbsent(pending.renterUuid, k -> new ArrayList<>()).add(pending.rentalId);
        recordCharge(pending.rentalId, pending.price, true);
        // SALVAMENTO ASSÍNCRONO
        saveActiveRentals();
        Player owner = Bukkit.getPlayer(pending.ownerUuid);
        if (owner != null && owner.isOnline()) {
            owner.sendMessage(ChatColor.GREEN + "✓ ITEM RENTED!");
            owner.sendMessage(ChatColor.GRAY + "Item: " + ChatColor.WHITE + getItemName(originalItem));
            owner.sendMessage(ChatColor.GRAY + "Renter: " + ChatColor.WHITE + (renter != null ? renter.getName() : "Unknown"));
            owner.sendMessage(ChatColor.GRAY + "Price per hour: " + ChatColor.YELLOW + formatCoin(pending.price));
            owner.sendMessage(ChatColor.GREEN + "The amount has been credited to your card!");
            owner.sendMessage(ChatColor.GRAY + "The renter will be charged automatically every hour.");
        }
    }

    private void recordCharge(String rentalId, BigDecimal amount, boolean success) {
        RentalChargeHistory history = chargeHistory.computeIfAbsent(rentalId, k -> new RentalChargeHistory(rentalId));
        history.charges.add(new ChargeRecord(System.currentTimeMillis(), amount, success));
        while (history.charges.size() > 100) {
            history.charges.remove(0);
        }
        saveChargeHistory();
    }
    private void processRecurringCharge(ActiveRental rental) {
        if (rental == null) return;
        if (processingCharges.contains(rental.id)) {
            getLogger().fine("Charge for rental " + rental.id + " is already being processed, skipping");
            return;
        }
        Long lastAttempt = lastChargeAttempt.get(rental.id);
        if (lastAttempt != null && (System.currentTimeMillis() - lastAttempt) < CHARGE_COOLDOWN_MS) {
            getLogger().fine("Charge for rental " + rental.id + " is on cooldown, skipping");
            return;
        }
        processingCharges.add(rental.id);
        lastChargeAttempt.put(rental.id, System.currentTimeMillis());
        String renterCardId = getPlayerCardId(rental.renterUuid);
        if (renterCardId == null || renterCardId.isEmpty()) {
            getLogger().warning("Cannot charge renter " + rental.renterUuid + " - no card set");
            processingCharges.remove(rental.id);
            cancelRentalAndNotify(rental, false);
            return;
        }
        String ownerCardId = getPlayerCardId(rental.ownerUuid);
        if (ownerCardId == null || ownerCardId.isEmpty()) {
            getLogger().warning("Cannot credit owner " + rental.ownerUuid + " - no card set");
            processingCharges.remove(rental.id);
            cancelRentalAndNotify(rental, false);
            return;
        }
        checkRenterHasItem(rental, new RentalCheckCallback() {
            @Override
            public void onResult(boolean hasItem, ItemStack currentItem) {
                if (!hasItem) {
                    getLogger().info("Renter " + rental.renterUuid + " no longer has rental item " + rental.id);
                    processingCharges.remove(rental.id);
                    cancelRentalAndNotify(rental, true);
                    return;
                }
                if (currentItem != null) {
                    rental.currentItem = currentItem;
                    saveActiveRentals();
                }
                BigDecimal totalPrice = rental.price;
                if (taxRate > 0) {
                    totalPrice = totalPrice.add(totalPrice.multiply(BigDecimal.valueOf(taxRate)))
                            .setScale(8, RoundingMode.DOWN);
                }
                final BigDecimal finalTotalPrice = totalPrice;
                final String finalRenterCardId = renterCardId;
                final String finalOwnerCardId = ownerCardId;
                checkPlayerBalance(renterCardId, new BalanceCheckCallback() {
                    @Override
                    public void onSuccess(BigDecimal balance) {
                        if (balance.compareTo(finalTotalPrice) < 0) {
                            getLogger().info("Renter " + rental.renterUuid + " has insufficient funds for rental " + rental.id);
                            recordCharge(rental.id, finalTotalPrice, false);
                            processingCharges.remove(rental.id);
                            cancelRentalAndNotify(rental, false);
                            return;
                        }
                        String paymentId = UUID.randomUUID().toString();
                        RentPayment payment = new RentPayment(paymentId, finalRenterCardId, finalOwnerCardId,
                                rental.price, rental.id, rental.ownerUuid, rental.renterUuid);
                        PendingPayment pending = new PendingPayment(paymentId, rental.renterUuid,
                                rental.ownerUuid, rental.id, rental.currentItem.clone(), rental.price);
                        pendingPayments.put(paymentId, pending);
                        paymentQueue.add(payment);
                        rental.lastChargeTime = System.currentTimeMillis();
                        rental.nextChargeTime = rental.lastChargeTime + MILLIS_PER_HOUR;
                        rental.chargeCount++;
                        saveActiveRentals();
                        getLogger().info("Queued recurring charge #" + rental.chargeCount + " for rental " + rental.id);
                        processingCharges.remove(rental.id);
                    }
                    @Override
                    public void onFailure(String error) {
                        getLogger().warning("Failed to check balance for recurring charge: " + error);
                        recordCharge(rental.id, finalTotalPrice, false);
                        processingCharges.remove(rental.id);
                        cancelRentalAndNotify(rental, false);
                    }
                });
            }
        });
    }
    // ATUALIZADO: checkRenterHasItem agora é seguro (main thread para online) e usa check sem remover para renewals
    private void checkRenterHasItem(ActiveRental rental, RentalCheckCallback callback) {
        Player renter = Bukkit.getPlayer(rental.renterUuid);
        if (renter != null && renter.isOnline()) {
            // Main thread - seguro
            boolean found = false;
            ItemStack foundItem = null;
            for (ItemStack item : renter.getInventory().getContents()) {
                if (item != null && rental.id.equals(getRentalId(item))) {
                    found = true;
                    foundItem = item.clone();
                    break;
                }
            }
            callback.onResult(found, foundItem);
        } else {
            // Offline: verifica sem remover o item do arquivo
            checkOfflineInventoryHasRental(rental, new RentalCheckCallback() {
                @Override
                public void onResult(boolean hasItem, ItemStack currentItem) {
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            callback.onResult(hasItem, currentItem);
                        }
                    }.runTask(CoinRent.this);
                }
            });
        }
    }
    private void cancelRentalAndNotify(ActiveRental rental, boolean itemLost) {
        asyncExecutor.submit(() -> {
            Player renter = Bukkit.getPlayer(rental.renterUuid);
            if (renter != null && renter.isOnline()) {
                for (int i = 0; i < renter.getInventory().getSize(); i++) {
                    ItemStack item = renter.getInventory().getItem(i);
                    if (item != null && rental.id.equals(getRentalId(item))) {
                        renter.getInventory().setItem(i, null);
                    }
                }
                renter.updateInventory();
                renter.sendMessage(ChatColor.RED + "Your rental of " + getItemName(rental.currentItem) +
                        " has been cancelled " + (itemLost ? "because the item was lost!" : "due to insufficient funds!"));
            } else {
                removeOfflineInventoryItem(rental.renterUuid, rental.id);
            }
            activeRentals.remove(rental.id);
            chargeHistory.remove(rental.id);
            List<String> rentalsList = playerRentals.get(rental.renterUuid);
            if (rentalsList != null) {
                rentalsList.remove(rental.id);
                if (rentalsList.isEmpty()) playerRentals.remove(rental.renterUuid);
            }
            Player owner = Bukkit.getPlayer(rental.ownerUuid);
            if (owner != null && owner.isOnline()) {
                if (itemLost) {
                    owner.sendMessage(ChatColor.RED + "Your rental item " + getItemName(rental.originalItem) +
                            " has been lost! The renter no longer has the item.");
                } else {
                    owner.sendMessage(ChatColor.YELLOW + "The rental of " + getItemName(rental.originalItem) +
                            " has been cancelled due to insufficient funds.");
                }
            }
            if (!itemLost) {
                ItemStack returnedItem = rental.currentItem.clone();
                returnedItem = removeRentalTags(returnedItem);
                returnedItem = markAsRentalItem(returnedItem, rental.id, rental.ownerUuid, rental.price, returnedItem);
                RentalItem shopItem = new RentalItem(
                        rental.id,
                        rental.ownerUuid,
                        getPlayerData(rental.ownerUuid) != null ? getPlayerData(rental.ownerUuid).shopName : "Unknown",
                        getPlayerData(rental.ownerUuid) != null ? getPlayerData(rental.ownerUuid).shopName : "Unknown",
                        returnedItem,
                        rental.price,
                        System.currentTimeMillis()
                );
                rentalsData.rentals.add(shopItem);
                saveRentalsData();
                if (owner != null && owner.isOnline()) {
                    owner.sendMessage(ChatColor.GREEN + "Your item has been returned to the shop: " + getItemName(returnedItem));
                }
            } else {
                saveActiveRentals();
            }
            saveChargeHistory();
        });
    }
    private void cancelRental(Player owner, String rentalId) {
        RentalItem rental = null;
        for (RentalItem r : rentalsData.rentals) {
            if (r.id.equals(rentalId)) {
                rental = r;
                break;
            }
        }
        if (rental == null) {
            owner.sendMessage(ChatColor.RED + "Rental not found!");
            return;
        }
        if (!rental.ownerUuid.equals(owner.getUniqueId()) && !owner.hasPermission("coinrent.admin")) {
            owner.sendMessage(ChatColor.RED + "This is not your rental to cancel!");
            return;
        }
        rentalsData.rentals.remove(rental);
        saveRentalsData();
        ItemStack returnItem = rental.item.clone();
        returnItem = removeRentalTags(returnItem);
        HashMap<Integer, ItemStack> leftover = owner.getInventory().addItem(returnItem);
        if (!leftover.isEmpty()) {
            for (ItemStack drop : leftover.values()) {
                if (drop != null) {
                    owner.getWorld().dropItemNaturally(owner.getLocation(), drop);
                }
            }
            owner.sendMessage(ChatColor.YELLOW + "Some items were dropped because your inventory was full!");
        }
        owner.sendMessage(ChatColor.GREEN + "Rental cancelled successfully!");
        owner.sendMessage(ChatColor.GRAY + "Item returned: " + ChatColor.WHITE + getItemName(returnItem));
        refreshAllOpenRentals();
    }
    private void returnItemToShop(ActiveRental rental, boolean forceRemoveFromInventory) {
        if (rental == null) return;
        ItemStack restoredItem = rental.currentItem.clone();
        restoredItem = removeRentalTags(restoredItem);
        restoredItem = markAsRentalItem(restoredItem, rental.id, rental.ownerUuid, rental.price, restoredItem);
        RentalItem shopItem = new RentalItem(
                rental.id,
                rental.ownerUuid,
                getPlayerData(rental.ownerUuid) != null ? getPlayerData(rental.ownerUuid).shopName : "Unknown",
                getPlayerData(rental.ownerUuid) != null ? getPlayerData(rental.ownerUuid).shopName : "Unknown",
                restoredItem,
                rental.price,
                System.currentTimeMillis()
        );
        rentalsData.rentals.add(shopItem);
        saveRentalsData();
        activeRentals.remove(rental.id);
        chargeHistory.remove(rental.id);
        List<String> rentalsList = playerRentals.get(rental.renterUuid);
        if (rentalsList != null) {
            rentalsList.remove(rental.id);
            if (rentalsList.isEmpty()) playerRentals.remove(rental.renterUuid);
        }
        saveActiveRentals();
        saveChargeHistory();
        Player renter = Bukkit.getPlayer(rental.renterUuid);
        if (renter != null && renter.isOnline()) {
            for (int i = 0; i < renter.getInventory().getSize(); i++) {
                ItemStack item = renter.getInventory().getItem(i);
                if (item != null && rental.id.equals(getRentalId(item))) {
                    renter.getInventory().setItem(i, null);
                }
            }
            renter.updateInventory();
            renter.sendMessage(ChatColor.YELLOW + "Your rental of " + getItemName(rental.currentItem) + " has been returned to the shop.");
        } else {
            removeOfflineInventoryItem(rental.renterUuid, rental.id);
        }
        Player owner = Bukkit.getPlayer(rental.ownerUuid);
        if (owner != null && owner.isOnline()) {
            owner.sendMessage(ChatColor.GREEN + "Your item has been returned to the shop: " + getItemName(restoredItem));
        }
    }
    private void cancelRentalByRenter(Player renter, String rentalId) {
        ActiveRental rental = activeRentals.get(rentalId);
        if (rental == null) {
            renter.sendMessage(ChatColor.RED + "Rental not found!");
            return;
        }
        if (!rental.renterUuid.equals(renter.getUniqueId())) {
            renter.sendMessage(ChatColor.RED + "This is not your rental!");
            return;
        }
        returnItemToShop(rental, true);
        renter.sendMessage(ChatColor.GREEN + "Rental cancelled successfully!");
        renter.sendMessage(ChatColor.GRAY + "Item returned to shop: " + ChatColor.WHITE + getItemName(rental.currentItem));
        refreshAllOpenRentals();
    }
    // ====================================================
    // PAYMENT PROCESSOR
    // ====================================================
    private void startPaymentProcessor() {
        paymentProcessorTask = new BukkitRunnable() {
            @Override
            public void run() {
                processPayments();
            }
        }.runTaskTimer(this, 20L, 20L);
    }
    private void processPayments() {
        long now = System.currentTimeMillis();
        if (now - lastProcessTime.get() < cooldownMs) return;
        RentPayment payment = paymentQueue.poll();
        if (payment == null) return;
        lastProcessTime.set(now);
        PendingPayment pending = pendingPayments.get(payment.id);
        if (pending == null) return;
        asyncExecutor.submit(() -> {
            coinCardAPI.transfer(payment.fromCard, payment.toCard, payment.amount.doubleValue(),
                new TransferCallback() {
                    @Override
                    public void onSuccess(String txId, double amount) {
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                completeRental(pending, txId);
                                pendingPayments.remove(payment.id);
                                recordCharge(payment.rentalId, payment.amount, true);
                            }
                        }.runTask(CoinRent.this);
                    }
                    @Override
                    public void onFailure(String error) {
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                getLogger().warning("Rent payment failed: " + error);
                                pendingPayments.remove(payment.id);
                                recordCharge(payment.rentalId, payment.amount, false);
                                ActiveRental rental = activeRentals.get(payment.rentalId);
                                if (rental != null) {
                                    cancelRentalAndNotify(rental, false);
                                }
                            }
                        }.runTask(CoinRent.this);
                    }
                });
        });
    }
    // ====================================================
    // RENT CHECKER
    // ====================================================
    private void startRentChecker() {
        rentCheckerTask = new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                for (ActiveRental rental : new ArrayList<>(activeRentals.values())) {
                    if (now >= rental.nextChargeTime) {
                        processRecurringCharge(rental);
                    }
                }
            }
        }.runTaskTimer(this, RENT_CHECK_INTERVAL, RENT_CHECK_INTERVAL);
    }
    private void processMissedCharges(ActiveRental rental) {
        if (rental == null) return;
        long now = System.currentTimeMillis();
        long nextChargeTime = rental.nextChargeTime;
        long timeMissed = now - nextChargeTime;
        int missedCount = (int) (timeMissed / MILLIS_PER_HOUR);
        if (missedCount <= 0) return;
        missedCount = Math.min(missedCount, 24);
        getLogger().info("Processing " + missedCount + " missed charges for rental " + rental.id);
        if (!processingCharges.contains(rental.id)) {
            rental.nextChargeTime = nextChargeTime + (missedCount * MILLIS_PER_HOUR);
            saveActiveRentals();
            processRecurringCharge(rental);
        }
    }
    // ====================================================
    // ITEM MARKING
    // ====================================================
    private ItemStack markAsRentalItem(ItemStack item, String rentalId, UUID ownerUuid,
                                        BigDecimal price, ItemStack originalItem) {
        if (item == null || item.getType() == Material.AIR) return item;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(rentItemKey, PersistentDataType.BOOLEAN, true);
        container.set(rentIdKey, PersistentDataType.STRING, rentalId);
        container.set(rentOwnerKey, PersistentDataType.STRING, ownerUuid.toString());
        container.set(rentPriceKey, PersistentDataType.STRING, price.toPlainString());
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
                boos.writeObject(originalItem);
            }
            container.set(rentOriginalItemKey, PersistentDataType.STRING,
                    Base64.getEncoder().encodeToString(baos.toByteArray()));
        } catch (IOException e) {
            getLogger().warning("Failed to store original item: " + e.getMessage());
        }
        item.setItemMeta(meta);
        return item;
    }
    private ItemStack markAsRentedItem(ItemStack item, String rentalId, UUID renterUuid,
                                        UUID ownerUuid, BigDecimal price, long startTime,
                                        long lastChargeTime, long nextChargeTime, int chargeCount) {
        if (item == null || item.getType() == Material.AIR) return item;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(rentItemKey, PersistentDataType.BOOLEAN, true);
        container.set(rentIdKey, PersistentDataType.STRING, rentalId);
        container.set(rentOwnerKey, PersistentDataType.STRING, ownerUuid.toString());
        container.set(rentRenterKey, PersistentDataType.STRING, renterUuid.toString());
        container.set(rentPriceKey, PersistentDataType.STRING, price.toPlainString());
        container.set(rentStartTimeKey, PersistentDataType.LONG, startTime);
        container.set(rentLastChargeKey, PersistentDataType.LONG, lastChargeTime);
        container.set(rentNextChargeKey, PersistentDataType.LONG, nextChargeTime);
        container.set(rentChargeCountKey, PersistentDataType.INTEGER, chargeCount);
        item.setItemMeta(meta);
        return item;
    }
    private boolean isRentalItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();
        return container.has(rentItemKey, PersistentDataType.BOOLEAN);
    }
    private String getRentalId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();
        if (container.has(rentIdKey, PersistentDataType.STRING)) {
            return container.get(rentIdKey, PersistentDataType.STRING);
        }
        return null;
    }
    private UUID getRentalOwner(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();
        if (container.has(rentOwnerKey, PersistentDataType.STRING)) {
            try {
                return UUID.fromString(container.get(rentOwnerKey, PersistentDataType.STRING));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }
    private UUID getRentalRenter(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();
        if (container.has(rentRenterKey, PersistentDataType.STRING)) {
            try {
                return UUID.fromString(container.get(rentRenterKey, PersistentDataType.STRING));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }
    private ItemStack removeRentalTags(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return item;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.remove(rentItemKey);
        container.remove(rentIdKey);
        container.remove(rentOwnerKey);
        container.remove(rentPriceKey);
        container.remove(rentOriginalItemKey);
        container.remove(rentRenterKey);
        container.remove(rentStartTimeKey);
        container.remove(rentLastChargeKey);
        container.remove(rentNextChargeKey);
        container.remove(rentChargeCountKey);
        item.setItemMeta(meta);
        return item;
    }
    private ItemStack restoreOriginalItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return item;
        PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();
        if (container.has(rentOriginalItemKey, PersistentDataType.STRING)) {
            String data = container.get(rentOriginalItemKey, PersistentDataType.STRING);
            try {
                byte[] bytes = Base64.getDecoder().decode(data);
                try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                     BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
                    return (ItemStack) bois.readObject();
                }
            } catch (Exception e) {
                getLogger().warning("Failed to restore original item: " + e.getMessage());
            }
        }
        return item;
    }
    // ====================================================
    // GUI BUILDERS (mantidos idênticos)
    // ====================================================
    private Inventory buildMainMenu(Player player) {
        Inventory inv = Bukkit.createInventory(
            new RentInventoryHolder(RentInventoryHolder.Type.MAIN_MENU, null, 0, player.getUniqueId()),
            27,
            ChatColor.DARK_GREEN + "≡ CoinRent Main Menu ≡"
        );
        ItemStack myRentals = new ItemStack(Material.CHEST);
        ItemMeta myRentalsMeta = myRentals.getItemMeta();
        myRentalsMeta.setDisplayName(ChatColor.GREEN + "My Rentals");
        myRentalsMeta.setLore(Arrays.asList(
            ChatColor.GRAY + "View and manage your",
            ChatColor.GRAY + "listed rentals"
        ));
        myRentals.setItemMeta(myRentalsMeta);
        inv.setItem(11, myRentals);
        ItemStack browse = new ItemStack(Material.COMPASS);
        ItemMeta browseMeta = browse.getItemMeta();
        browseMeta.setDisplayName(ChatColor.AQUA + "Browse Categories");
        browseMeta.setLore(Arrays.asList(
            ChatColor.GRAY + "Browse items by category"
        ));
        browse.setItemMeta(browseMeta);
        inv.setItem(13, browse);
        ItemStack global = new ItemStack(Material.BOOK);
        ItemMeta globalMeta = global.getItemMeta();
        globalMeta.setDisplayName(ChatColor.YELLOW + "Global Rentals");
        globalMeta.setLore(Arrays.asList(
            ChatColor.GRAY + "View all available rentals"
        ));
        global.setItemMeta(globalMeta);
        inv.setItem(15, global);
        ItemStack cancel = new ItemStack(Material.BARRIER);
        ItemMeta cancelMeta = cancel.getItemMeta();
        cancelMeta.setDisplayName(ChatColor.RED + "Cancel Rentals");
        cancelMeta.setLore(Arrays.asList(
            ChatColor.GRAY + "Cancel your active rentals"
        ));
        cancel.setItemMeta(cancelMeta);
        inv.setItem(22, cancel);
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta glassMeta = glass.getItemMeta();
        glassMeta.setDisplayName(" ");
        glass.setItemMeta(glassMeta);
        for (int i = 0; i < 27; i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, glass);
            }
        }
        return inv;
    }
    private Inventory buildCategoriesMenu(Player player) {
        Inventory inv = Bukkit.createInventory(
            new RentInventoryHolder(RentInventoryHolder.Type.CATEGORIES_MENU, null, 0, player.getUniqueId()),
            54,
            ChatColor.DARK_GREEN + "≡ Categories Menu ≡"
        );
        ItemCategory[] categories = ItemCategory.values();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23};
        for (int i = 0; i < categories.length && i < slots.length; i++) {
            ItemCategory category = categories[i];
            ItemStack item = new ItemStack(category.getIcon());
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.GOLD + category.getDisplayName());
            meta.setLore(Arrays.asList(
                ChatColor.GRAY + category.getDescription(),
                "",
                ChatColor.YELLOW + "Click to browse " + category.getDisplayName().toLowerCase()
            ));
            item.setItemMeta(meta);
            inv.setItem(slots[i], item);
        }
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName(ChatColor.RED + "Back to Main Menu");
        back.setItemMeta(backMeta);
        inv.setItem(49, back);
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta glassMeta = glass.getItemMeta();
        glassMeta.setDisplayName(" ");
        glass.setItemMeta(glassMeta);
        for (int i = 0; i < 54; i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, glass);
            }
        }
        return inv;
    }
    private Inventory buildCategoryRentalsMenu(Player player, ItemCategory category, int page) {
        List<RentalItem> filtered = getFilteredItems(player, category, RentInventoryHolder.Type.CATEGORY_RENTALS);
        int totalPages = (int) Math.ceil(filtered.size() / 45.0);
        Inventory inv = Bukkit.createInventory(
            new RentInventoryHolder(RentInventoryHolder.Type.CATEGORY_RENTALS, category, page, player.getUniqueId()),
            54,
            ChatColor.DARK_GREEN + "≡ " + category.getDisplayName() + " ≡ " + ChatColor.GRAY + "Page " + (page + 1) + "/" + Math.max(1, totalPages)
        );
        int start = page * 45;
        int end = Math.min(start + 45, filtered.size());
        for (int i = start; i < end; i++) {
            RentalItem rental = filtered.get(i);
            ItemStack item = rental.item.clone();
            ItemMeta meta = item.getItemMeta();
            List<String> lore = new ArrayList<>();
            if (meta.hasLore()) {
                lore.addAll(meta.getLore());
            }
            lore.add("");
            lore.add(ChatColor.GRAY + "Owner: " + ChatColor.YELLOW + rental.ownerShopName);
            lore.add(ChatColor.GRAY + "Price: " + ChatColor.GREEN + formatCoin(rental.price) + ChatColor.GRAY + "/hour");
            lore.add(ChatColor.GRAY + "ID: " + ChatColor.DARK_GRAY + rental.id);
            lore.add("");
            lore.add(ChatColor.GREEN + "Click to rent this item");
            meta.setLore(lore);
            item.setItemMeta(meta);
            inv.setItem(i - start, item);
        }
        if (page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prev.getItemMeta();
            prevMeta.setDisplayName(ChatColor.GOLD + "Previous Page");
            prev.setItemMeta(prevMeta);
            inv.setItem(45, prev);
        }
        if (page + 1 < totalPages) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = next.getItemMeta();
            nextMeta.setDisplayName(ChatColor.GOLD + "Next Page");
            next.setItemMeta(nextMeta);
            inv.setItem(53, next);
        }
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName(ChatColor.RED + "Back to Categories");
        back.setItemMeta(backMeta);
        inv.setItem(49, back);
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta glassMeta = glass.getItemMeta();
        glassMeta.setDisplayName(" ");
        glass.setItemMeta(glassMeta);
        for (int i = 0; i < 54; i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, glass);
            }
        }
        return inv;
    }
    private Inventory buildMyRentalsMenu(Player player, int page) {
        List<RentalItem> myRentals = getFilteredItems(player, null, RentInventoryHolder.Type.MY_RENTALS);
        int totalPages = (int) Math.ceil(myRentals.size() / 45.0);
        Inventory inv = Bukkit.createInventory(
            new RentInventoryHolder(RentInventoryHolder.Type.MY_RENTALS, null, page, player.getUniqueId()),
            54,
            ChatColor.DARK_GREEN + "≡ My Rentals ≡ " + ChatColor.GRAY + "Page " + (page + 1) + "/" + Math.max(1, totalPages)
        );
        int start = page * 45;
        int end = Math.min(start + 45, myRentals.size());
        for (int i = start; i < end; i++) {
            RentalItem rental = myRentals.get(i);
            ItemStack item = rental.item.clone();
            ItemMeta meta = item.getItemMeta();
            List<String> lore = new ArrayList<>();
            if (meta.hasLore()) {
                lore.addAll(meta.getLore());
            }
            lore.add("");
            lore.add(ChatColor.GRAY + "Price: " + ChatColor.GREEN + formatCoin(rental.price) + ChatColor.GRAY + "/hour");
            lore.add(ChatColor.GRAY + "Listed: " + ChatColor.WHITE + new Date(rental.listedAt));
            lore.add(ChatColor.GRAY + "ID: " + ChatColor.DARK_GRAY + rental.id);
            lore.add("");
            lore.add(ChatColor.RED + "Click to cancel this rental");
            meta.setLore(lore);
            item.setItemMeta(meta);
            inv.setItem(i - start, item);
        }
        if (page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prev.getItemMeta();
            prevMeta.setDisplayName(ChatColor.GOLD + "Previous Page");
            prev.setItemMeta(prevMeta);
            inv.setItem(45, prev);
        }
        if (page + 1 < totalPages) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = next.getItemMeta();
            nextMeta.setDisplayName(ChatColor.GOLD + "Next Page");
            next.setItemMeta(nextMeta);
            inv.setItem(53, next);
        }
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName(ChatColor.RED + "Back to Main Menu");
        back.setItemMeta(backMeta);
        inv.setItem(49, back);
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta glassMeta = glass.getItemMeta();
        glassMeta.setDisplayName(" ");
        glass.setItemMeta(glassMeta);
        for (int i = 0; i < 54; i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, glass);
            }
        }
        return inv;
    }
    private Inventory buildGlobalRentalsMenu(Player player, int page) {
        List<RentalItem> allRentals = getFilteredItems(player, null, RentInventoryHolder.Type.GLOBAL_RENTALS);
        int totalPages = (int) Math.ceil(allRentals.size() / 45.0);
        Inventory inv = Bukkit.createInventory(
            new RentInventoryHolder(RentInventoryHolder.Type.GLOBAL_RENTALS, null, page, player.getUniqueId()),
            54,
            ChatColor.DARK_GREEN + "≡ Global Rentals ≡ " + ChatColor.GRAY + "Page " + (page + 1) + "/" + Math.max(1, totalPages)
        );
        int start = page * 45;
        int end = Math.min(start + 45, allRentals.size());
        for (int i = start; i < end; i++) {
            RentalItem rental = allRentals.get(i);
            ItemStack item = rental.item.clone();
            ItemMeta meta = item.getItemMeta();
            List<String> lore = new ArrayList<>();
            if (meta.hasLore()) {
                lore.addAll(meta.getLore());
            }
            lore.add("");
            lore.add(ChatColor.GRAY + "Owner: " + ChatColor.YELLOW + rental.ownerShopName);
            lore.add(ChatColor.GRAY + "Price: " + ChatColor.GREEN + formatCoin(rental.price) + ChatColor.GRAY + "/hour");
            lore.add(ChatColor.GRAY + "ID: " + ChatColor.DARK_GRAY + rental.id);
            lore.add("");
            lore.add(ChatColor.GREEN + "Click to rent this item");
            meta.setLore(lore);
            item.setItemMeta(meta);
            inv.setItem(i - start, item);
        }
        if (page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prev.getItemMeta();
            prevMeta.setDisplayName(ChatColor.GOLD + "Previous Page");
            prev.setItemMeta(prevMeta);
            inv.setItem(45, prev);
        }
        if (page + 1 < totalPages) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = next.getItemMeta();
            nextMeta.setDisplayName(ChatColor.GOLD + "Next Page");
            next.setItemMeta(nextMeta);
            inv.setItem(53, next);
        }
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName(ChatColor.RED + "Back to Main Menu");
        back.setItemMeta(backMeta);
        inv.setItem(49, back);
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta glassMeta = glass.getItemMeta();
        glassMeta.setDisplayName(" ");
        glass.setItemMeta(glassMeta);
        for (int i = 0; i < 54; i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, glass);
            }
        }
        return inv;
    }
    private Inventory buildCancelMenu(Player player) {
        List<ActiveRental> active = new ArrayList<>();
        List<String> rentalIds = playerRentals.get(player.getUniqueId());
        if (rentalIds != null) {
            for (String id : rentalIds) {
                ActiveRental rental = activeRentals.get(id);
                if (rental != null) {
                    active.add(rental);
                }
            }
        }
        Inventory inv = Bukkit.createInventory(
            new RentInventoryHolder(RentInventoryHolder.Type.CANCEL_MENU, null, 0, player.getUniqueId()),
            54,
            ChatColor.DARK_RED + "≡ Cancel Active Rentals ≡"
        );
        for (int i = 0; i < Math.min(45, active.size()); i++) {
            ActiveRental rental = active.get(i);
            ItemStack item = rental.currentItem.clone();
            ItemMeta meta = item.getItemMeta();
            List<String> lore = new ArrayList<>();
            if (meta.hasLore()) {
                lore.addAll(meta.getLore());
            }
            lore.add("");
            lore.add(ChatColor.GRAY + "Rented from: " + ChatColor.WHITE + Bukkit.getOfflinePlayer(rental.ownerUuid).getName());
            lore.add(ChatColor.GRAY + "Started: " + ChatColor.WHITE + new Date(rental.startTime));
            lore.add(ChatColor.GRAY + "Last charge: " + ChatColor.WHITE + new Date(rental.lastChargeTime));
            lore.add(ChatColor.GRAY + "Next charge: " + ChatColor.WHITE + new Date(rental.nextChargeTime));
            lore.add(ChatColor.GRAY + "Total charges: " + ChatColor.YELLOW + rental.chargeCount);
            lore.add(ChatColor.GRAY + "Price: " + ChatColor.GREEN + formatCoin(rental.price) + ChatColor.GRAY + "/hour");
            lore.add(ChatColor.GRAY + "ID: " + ChatColor.DARK_GRAY + rental.id);
            lore.add("");
            lore.add(ChatColor.RED + "Click to cancel this rental");
            meta.setLore(lore);
            item.setItemMeta(meta);
            inv.setItem(i, item);
        }
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName(ChatColor.RED + "Back to Main Menu");
        back.setItemMeta(backMeta);
        inv.setItem(45, back);
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta glassMeta = glass.getItemMeta();
        glassMeta.setDisplayName(" ");
        glass.setItemMeta(glassMeta);
        for (int i = 0; i < 54; i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, glass);
            }
        }
        return inv;
    }
    // ====================================================
    // UTILITY METHODS
    // ====================================================
    private String formatCoin(BigDecimal amount) {
        if (amount == null) return "0";
        String formatted = COIN_FORMAT.format(amount);
        if (!formatted.contains(".")) formatted += ".0";
        return formatted;
    }
    private String getItemName(ItemStack item) {
        if (item == null) return "Unknown";
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return item.getItemMeta().getDisplayName();
        }
        return item.getType().toString().replace('_', ' ').toLowerCase();
    }
    private boolean checkClickCooldown(Player player) {
        long now = System.currentTimeMillis();
        Long lastClick = playerClickCooldown.get(player.getUniqueId());
        if (lastClick != null && (now - lastClick) < clickCooldownMs) return false;
        playerClickCooldown.put(player.getUniqueId(), now);
        return true;
    }
    private void refreshAllOpenRentals() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshPlayerRentals(player);
        }
    }
    private void refreshPlayerRentals(Player player) {
        if (player == null || !player.isOnline()) return;
        Inventory openInv = player.getOpenInventory().getTopInventory();
        if (openInv.getHolder() instanceof RentInventoryHolder) {
            RentInventoryHolder holder = (RentInventoryHolder) openInv.getHolder();
            if (!holder.getViewerUuid().equals(player.getUniqueId())) {
                player.closeInventory();
                return;
            }
            switch (holder.getType()) {
                case MAIN_MENU:
                    player.openInventory(buildMainMenu(player));
                    break;
                case CATEGORIES_MENU:
                    player.openInventory(buildCategoriesMenu(player));
                    break;
                case CATEGORY_RENTALS:
                    if (holder.getCategory() != null) {
                        player.openInventory(buildCategoryRentalsMenu(player, holder.getCategory(), holder.getPage()));
                    }
                    break;
                case MY_RENTALS:
                    player.openInventory(buildMyRentalsMenu(player, holder.getPage()));
                    break;
                case GLOBAL_RENTALS:
                    player.openInventory(buildGlobalRentalsMenu(player, holder.getPage()));
                    break;
                case CANCEL_MENU:
                    player.openInventory(buildCancelMenu(player));
                    break;
            }
        }
    }
    // ====================================================
    // EVENT LISTENERS (mantidos idênticos + novos para detecção de itens em contêineres)
    // ====================================================
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory inv = event.getInventory();
        // Se for nosso GUI, trata normalmente
        if (inv.getHolder() instanceof RentInventoryHolder holder) {
            if (!holder.getViewerUuid().equals(player.getUniqueId())) {
                event.setCancelled(true);
                player.closeInventory();
                player.sendMessage(ChatColor.RED + "This inventory does not belong to you!");
                return;
            }
            RentInventoryHolder.Type type = holder.getType();
            int slot = event.getSlot();
            int page = holder.getPage();
            if (type == RentInventoryHolder.Type.CANCEL_MENU) {
                event.setCancelled(true);
                if (slot == 45) {
                    player.openInventory(buildMainMenu(player));
                    return;
                }
                if (slot < 45) {
                    ItemStack clicked = event.getCurrentItem();
                    if (clicked != null && clicked.getType() != Material.AIR) {
                        String rentalId = extractRentalId(clicked);
                        if (rentalId != null) {
                            cancelRentalByRenter(player, rentalId);
                            player.openInventory(buildCancelMenu(player));
                        }
                    }
                }
                return;
            }
            event.setCancelled(true);
            if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;
            switch (type) {
                case MAIN_MENU:
                    if (slot == 11) {
                        player.openInventory(buildMyRentalsMenu(player, 0));
                    } else if (slot == 13) {
                        player.openInventory(buildCategoriesMenu(player));
                    } else if (slot == 15) {
                        player.openInventory(buildGlobalRentalsMenu(player, 0));
                    } else if (slot == 22) {
                        player.openInventory(buildCancelMenu(player));
                    }
                    break;
                case CATEGORIES_MENU:
                    if (slot == 49) {
                        player.openInventory(buildMainMenu(player));
                        return;
                    }
                    int categorySlot = -1;
                    int[] categorySlots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23};
                    for (int i = 0; i < categorySlots.length; i++) {
                        if (slot == categorySlots[i]) {
                            categorySlot = i;
                            break;
                        }
                    }
                    if (categorySlot >= 0 && categorySlot < ItemCategory.values().length) {
                        ItemCategory category = ItemCategory.values()[categorySlot];
                        player.openInventory(buildCategoryRentalsMenu(player, category, 0));
                    }
                    break;
                case CATEGORY_RENTALS:
                case MY_RENTALS:
                case GLOBAL_RENTALS:
                    handleRentalClick(player, holder, slot, page, event.getCurrentItem());
                    break;
            }
            return;
        }

        // --- PROTEÇÃO PARA CONTÊINERES (BAUS, ETC) MAS NUNCA NOS MENUS DO PLUGIN ---
        Inventory clickedInv = event.getClickedInventory();
        if (clickedInv != null && !(clickedInv instanceof PlayerInventory)) {
            ItemStack current = event.getCurrentItem();
            ItemStack cursor = event.getCursor();
            if (current != null && isRentalItem(current)) {
                boolean movingToContainer = false;
                switch (event.getAction()) {
                    case MOVE_TO_OTHER_INVENTORY:
                    case PICKUP_ALL:
                    case PICKUP_HALF:
                    case PICKUP_SOME:
                    case PICKUP_ONE:
                    case COLLECT_TO_CURSOR:
                        movingToContainer = true;
                        break;
                    default:
                        break;
                }
                if (movingToContainer) {
                    event.setCancelled(true);
                    handleRentalItemMoved(player, current, clickedInv, event.getSlot(), true);
                }
            } else if (cursor != null && isRentalItem(cursor)) {
                event.setCancelled(true);
                handleRentalItemMoved(player, cursor, clickedInv, event.getSlot(), true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        // Se for nosso GUI, cancela e não interfere
        if (event.getInventory().getHolder() instanceof RentInventoryHolder) {
            event.setCancelled(true);
            return;
        }
        // Se estiver arrastando um item alugado para slots de contêiner, cancela
        if (event.getCursor() != null && isRentalItem(event.getCursor())) {
            boolean containerDragged = false;
            for (int slot : event.getRawSlots()) {
                Inventory inv = event.getView().getInventory(slot);
                if (inv != null && !(inv instanceof PlayerInventory) && !(inv.getHolder() instanceof RentInventoryHolder)) {
                    containerDragged = true;
                    break;
                }
            }
            if (containerDragged) {
                event.setCancelled(true);
                handleRentalItemMoved(player, event.getCursor(), null, -1, true);
                player.setItemOnCursor(null);
                player.updateInventory();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        // Ignora se origem ou destino for nosso GUI
        if (event.getSource().getHolder() instanceof RentInventoryHolder ||
            event.getDestination().getHolder() instanceof RentInventoryHolder) {
            event.setCancelled(true);
            return;
        }
        ItemStack item = event.getItem();
        if (item != null && isRentalItem(item)) {
            event.setCancelled(true);
            // Remove o item da origem (hoppers, etc.)
            Inventory source = event.getSource();
            new BukkitRunnable() {
                @Override
                public void run() {
                    for (int i = 0; i < source.getSize(); i++) {
                        ItemStack it = source.getItem(i);
                        if (it != null && isRentalItem(it) && it.isSimilar(item)) {
                            source.setItem(i, null);
                            break;
                        }
                    }
                    String rentalId = getRentalId(item);
                    if (rentalId != null) {
                        ActiveRental rental = activeRentals.get(rentalId);
                        if (rental != null) {
                            returnItemToShop(rental, true);
                        }
                    }
                }
            }.runTask(this);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Inventory inv = event.getInventory();
        // Nunca escaneie nossos próprios menus
        if (inv.getHolder() instanceof RentInventoryHolder) return;
        if (inv.getType() == InventoryType.PLAYER) return;
        scanAndHandleRentalItems(inv, player);
    }

    // Helper para tratar item alugado movido para contêiner
    private void handleRentalItemMoved(Player player, ItemStack item, Inventory container, int slot, boolean fromPlayer) {
        if (fromPlayer) {
            if (slot >= 0 && slot < player.getInventory().getSize()) {
                player.getInventory().setItem(slot, null);
            } else {
                for (int i = 0; i < player.getInventory().getSize(); i++) {
                    ItemStack it = player.getInventory().getItem(i);
                    if (it != null && isRentalItem(it) && it.isSimilar(item)) {
                        player.getInventory().setItem(i, null);
                        break;
                    }
                }
            }
        } else {
            if (container != null && slot >= 0 && slot < container.getSize()) {
                container.setItem(slot, null);
            } else if (container != null) {
                for (int i = 0; i < container.getSize(); i++) {
                    ItemStack it = container.getItem(i);
                    if (it != null && isRentalItem(it) && it.isSimilar(item)) {
                        container.setItem(i, null);
                        break;
                    }
                }
            }
        }

        String rentalId = getRentalId(item);
        if (rentalId != null) {
            ActiveRental rental = activeRentals.get(rentalId);
            if (rental != null) {
                returnItemToShop(rental, true);
                player.sendMessage(ChatColor.YELLOW + "Your rental item has been returned to the shop because you tried to store it!");
            } else {
                player.sendMessage(ChatColor.RED + "Invalid rental item! It has been removed.");
            }
        } else {
            player.sendMessage(ChatColor.RED + "Invalid rental item! It has been removed.");
        }

        player.updateInventory();
    }

    // Escaneia um inventário e remove itens alugados (devolvendo à loja ou deletando)
    private void scanAndHandleRentalItems(Inventory inv, Player player) {
        new BukkitRunnable() {
            @Override
            public void run() {
                boolean modified = false;
                for (int i = 0; i < inv.getSize(); i++) {
                    ItemStack item = inv.getItem(i);
                    if (item != null && isRentalItem(item)) {
                        String rentalId = getRentalId(item);
                        if (rentalId != null) {
                            ActiveRental rental = activeRentals.get(rentalId);
                            if (rental != null) {
                                returnItemToShop(rental, true);
                                player.sendMessage(ChatColor.YELLOW + "A rental item was found in a container and has been returned to the shop.");
                            } else {
                                inv.setItem(i, null);
                                player.sendMessage(ChatColor.RED + "An invalid rental item was found and removed.");
                            }
                        } else {
                            inv.setItem(i, null);
                            player.sendMessage(ChatColor.RED + "An invalid rental item was found and removed.");
                        }
                        modified = true;
                    }
                }
                if (modified) {
                    player.updateInventory();
                }
            }
        }.runTaskAsynchronously(this);
    }

    // ====================================================
    // HANDLER PARA CLICKS NOS GUI (já existentes)
    // ====================================================
    private void handleRentalClick(Player player, RentInventoryHolder holder, int slot,
                                    int page, ItemStack clickedItem) {
        if (slot == 49) {
            if (holder.getType() == RentInventoryHolder.Type.CATEGORY_RENTALS && holder.getCategory() != null) {
                player.openInventory(buildCategoriesMenu(player));
            } else {
                player.openInventory(buildMainMenu(player));
            }
            return;
        }
        if (slot == 45 && page > 0) {
            reopenPage(player, holder.getCategory(), page - 1, holder.getType());
            return;
        }
        if (slot == 53) {
            reopenPage(player, holder.getCategory(), page + 1, holder.getType());
            return;
        }
        if (slot >= 45) return;
        List<RentalItem> items = getFilteredItems(player, holder.getCategory(), holder.getType());
        int index = page * 45 + slot;
        if (index >= items.size()) return;
        String rentalId = extractRentalId(clickedItem);
        if (rentalId == null) {
            player.sendMessage(ChatColor.RED + "Invalid rental item!");
            return;
        }
        RentalItem rental = null;
        for (RentalItem r : items) {
            if (r.id.equals(rentalId)) {
                rental = r;
                break;
            }
        }
        if (rental == null) {
            player.sendMessage(ChatColor.RED + "This item is no longer available!");
            refreshPlayerRentals(player);
            return;
        }
        if (holder.getType() == RentInventoryHolder.Type.MY_RENTALS) {
            cancelRental(player, rental.id);
            reopenPage(player, holder.getCategory(), page, holder.getType());
        } else {
            if (isRentalItem(rental.item)) {
                String activeRentalId = getRentalId(rental.item);
                if (activeRentalId != null && activeRentals.containsKey(activeRentalId)) {
                    player.sendMessage(ChatColor.RED + "This item is already being rented by someone!");
                    return;
                }
            }
            rentItem(player, rental);
        }
    }

    private String extractRentalId(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) return null;
        for (String line : item.getItemMeta().getLore()) {
            String cleanLine = ChatColor.stripColor(line);
            if (cleanLine.startsWith("ID: ")) {
                return cleanLine.substring(4).trim();
            }
        }
        return null;
    }

    private void reopenPage(Player player, ItemCategory category, int page, RentInventoryHolder.Type type) {
        switch (type) {
            case MY_RENTALS:
                player.openInventory(buildMyRentalsMenu(player, page));
                break;
            case CATEGORY_RENTALS:
                player.openInventory(buildCategoryRentalsMenu(player, category, page));
                break;
            case GLOBAL_RENTALS:
                player.openInventory(buildGlobalRentalsMenu(player, page));
                break;
            default:
                break;
        }
    }

    private List<RentalItem> getFilteredItems(Player player, ItemCategory category, RentInventoryHolder.Type type) {
        if (type == RentInventoryHolder.Type.MY_RENTALS) {
            return rentalsData.rentals.stream()
                    .filter(rental -> rental != null && rental.ownerUuid != null &&
                           rental.ownerUuid.equals(player.getUniqueId()))
                    .sorted((a, b) -> Long.compare(b.listedAt, a.listedAt))
                    .collect(Collectors.toList());
        }
        if (category != null && category != ItemCategory.ALL) {
            return rentalsData.rentals.stream()
                    .filter(rental -> rental != null && rental.item != null)
                    .filter(rental -> ItemCategory.fromMaterial(rental.item.getType()) == category)
                    .sorted((a, b) -> Long.compare(b.listedAt, a.listedAt))
                    .collect(Collectors.toList());
        }
        return rentalsData.rentals.stream()
                .filter(Objects::nonNull)
                .sorted((a, b) -> Long.compare(b.listedAt, a.listedAt))
                .collect(Collectors.toList());
    }

    // ====================================================
    // OUTROS EVENTOS EXISTENTES (mantidos inalterados)
    // ====================================================
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof RentInventoryHolder) {
            playerSessions.remove(event.getPlayer().getUniqueId());
        }
    }
    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (isRentalItem(item)) {
            String rentalId = getRentalId(item);
            if (rentalId != null) {
                ActiveRental rental = activeRentals.get(rentalId);
                if (rental != null) {
                    event.getItemDrop().remove();
                    returnItemToShop(rental, true);
                    event.getPlayer().sendMessage(ChatColor.YELLOW + "The rental item has been returned to the shop.");
                } else {
                    event.getItemDrop().remove();
                    event.getPlayer().sendMessage(ChatColor.YELLOW + "This rental listing has been removed.");
                }
            }
        }
    }
    @EventHandler
    public void onItemSpawn(ItemSpawnEvent event) {
        ItemStack item = event.getEntity().getItemStack();
        if (isRentalItem(item)) {
            String rentalId = getRentalId(item);
            if (rentalId != null) {
                ActiveRental rental = activeRentals.get(rentalId);
                if (rental != null) {
                    event.setCancelled(true);
                    event.getEntity().remove();
                    returnItemToShop(rental, true);
                }
            }
        }
    }
    @EventHandler
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player) {
            ItemStack item = event.getItem().getItemStack();
            if (isRentalItem(item)) {
                UUID renter = getRentalRenter(item);
                UUID owner = getRentalOwner(item);
                if (renter != null && renter.equals(event.getEntity().getUniqueId())) {
                    return;
                }
                if (owner != null && owner.equals(event.getEntity().getUniqueId())) {
                    String rentalId = getRentalId(item);
                    if (rentalId != null && !activeRentals.containsKey(rentalId)) {
                        return;
                    }
                }
                event.setCancelled(true);
                event.getItem().remove();
                String rentalId = getRentalId(item);
                if (rentalId != null) {
                    ActiveRental rental = activeRentals.get(rentalId);
                    if (rental != null) {
                        returnItemToShop(rental, true);
                    }
                }
                ((Player) event.getEntity()).sendMessage(ChatColor.RED + "You cannot pick up this rental item!");
            }
        }
    }
    @EventHandler
    public void onInventoryClickNonRental(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack current = event.getCurrentItem();
        if (current != null && isRentalItem(current)) {
            if (event.getClickedInventory() != null &&
                event.getClickedInventory().getType() != InventoryType.PLAYER &&
                event.getClickedInventory().getType() != InventoryType.CRAFTING &&
                !(event.getClickedInventory().getHolder() instanceof RentInventoryHolder)) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "You cannot store rented items!");
            }
            if (event.getInventory().getHolder() instanceof RentInventoryHolder &&
                event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "You cannot move rental items to your inventory from here!");
            }
        }
    }
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (isRentalItem(item)) {
            if (isConsumable(item)) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "You cannot use consumable rented items!");
                return;
            }
            if (event.hasBlock() && item.getType().isBlock()) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "You cannot place rented blocks!");
            }
        }
    }
    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (isRentalItem(item)) {
            if (!(event.getRightClicked() instanceof Player)) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "You cannot use rented items on entities!");
            }
        }
    }
    @EventHandler
    public void onPlayerItemDamage(PlayerItemDamageEvent event) {
        ItemStack item = event.getItem();
        if (isRentalItem(item)) {
            String rentalId = getRentalId(item);
            if (rentalId != null) {
                ActiveRental rental = activeRentals.get(rentalId);
                if (rental != null) {
                    rental.currentItem = item.clone();
                    saveActiveRentals();
                }
            }
        }
    }
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (isRentalItem(item)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "You cannot place rented blocks!");
        }
    }
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        cardCache.remove(player.getUniqueId());
        cardCacheTimestamp.remove(player.getUniqueId());
        List<String> rentals = playerRentals.get(player.getUniqueId());
        if (rentals != null) {
            for (String rentalId : rentals) {
                ActiveRental rental = activeRentals.get(rentalId);
                if (rental != null) {
                    boolean hasItem = false;
                    for (ItemStack invItem : player.getInventory().getContents()) {
                        if (invItem != null && rentalId.equals(getRentalId(invItem))) {
                            hasItem = true;
                            break;
                        }
                    }
                    if (!hasItem) {
                        checkOfflineInventoryForRental(rental, new RentalCheckCallback() {
                            @Override
                            public void onResult(boolean hasOfflineItem, ItemStack offlineItem) {
                                if (hasOfflineItem && offlineItem != null) {
                                    new BukkitRunnable() {
                                        @Override
                                        public void run() {
                                            player.getInventory().addItem(offlineItem);
                                            player.sendMessage(ChatColor.GREEN + "Your rental item has been returned to your inventory.");
                                        }
                                    }.runTask(CoinRent.this);
                                }
                            }
                        });
                    }
                    long now = System.currentTimeMillis();
                    if (now >= rental.nextChargeTime) {
                        processMissedCharges(rental);
                    }
                }
            }
        }
        saveOfflineInventory(player.getUniqueId(), player.getInventory());
    }
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        saveOfflineInventory(event.getPlayer().getUniqueId(), event.getPlayer().getInventory());
        playerSessions.remove(event.getPlayer().getUniqueId());
        playerClickCooldown.remove(event.getPlayer().getUniqueId());
    }
    // ====================================================
    // COMMAND HANDLER
    // ====================================================
    public class RentCommand implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                    if (!sender.hasPermission("coinrent.admin")) {
                        sender.sendMessage(ChatColor.RED + "You don't have permission!");
                        return true;
                    }
                    loadConfig();
                    sender.sendMessage(ChatColor.GREEN + "CoinRent configuration reloaded!");
                    return true;
                }
                sender.sendMessage("This command can only be used by players.");
                return true;
            }
            Player player = (Player) sender;
            if (args.length == 0) {
                player.openInventory(buildMainMenu(player));
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "reload":
                    if (!player.hasPermission("coinrent.admin")) {
                        player.sendMessage(ChatColor.RED + "You don't have permission!");
                        return true;
                    }
                    loadConfig();
                    player.sendMessage(ChatColor.GREEN + "CoinRent configuration reloaded!");
                    break;
                case "rent":
                    if (args.length < 3) {
                        player.sendMessage(ChatColor.RED + "Usage: /crent rent <amount> <price>");
                        return true;
                    }
                    handleRentCommand(player, args);
                    break;
                case "cancel":
                    player.openInventory(buildCancelMenu(player));
                    break;
                case "name":
                    if (args.length < 2) {
                        player.sendMessage(ChatColor.RED + "Usage: /crent name <shop name>");
                        return true;
                    }
                    String shopName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                    setPlayerShopName(player, shopName);
                    break;
                default:
                    player.openInventory(buildMainMenu(player));
                    break;
            }
            return true;
        }
        private void handleRentCommand(Player player, String[] args) {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item == null || item.getType() == Material.AIR) {
                player.sendMessage(ChatColor.RED + "You must hold an item to rent!");
                return;
            }
            if (isConsumable(item)) {
                player.sendMessage(ChatColor.RED + "Consumable items cannot be rented!");
                return;
            }
            int amount;
            try {
                amount = Integer.parseInt(args[1]);
                if (amount <= 0 || amount > item.getAmount()) {
                    player.sendMessage(ChatColor.RED + "Invalid amount!");
                    return;
                }
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Amount must be a number!");
                return;
            }
            BigDecimal price;
            String priceStr = args[2].replace(',', '.');
            try {
                if (priceStr.contains(".")) {
                    price = new BigDecimal(priceStr);
                } else {
                    long intValue = Long.parseLong(priceStr);
                    if (intValue <= 0) {
                        player.sendMessage(ChatColor.RED + "Price must be positive!");
                        return;
                    }
                    price = BigDecimal.valueOf(intValue).divide(BigDecimal.valueOf(100_000_000), 8, RoundingMode.DOWN);
                }
                if (price.compareTo(BigDecimal.ZERO) <= 0) {
                    player.sendMessage(ChatColor.RED + "Price must be positive!");
                    return;
                }
                price = price.setScale(8, RoundingMode.DOWN);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Invalid price format! Use numbers like 1.5, 0.001, or 150");
                return;
            }
            String rentalId = getRentalId(item);
            if (rentalId != null && activeRentals.containsKey(rentalId)) {
                player.sendMessage(ChatColor.RED + "This item is currently being rented by someone and cannot be listed!");
                return;
            }
            ItemStack rentItem = item.clone();
            rentItem.setAmount(1);
            item.setAmount(item.getAmount() - 1);
            player.getInventory().setItemInMainHand(item);
            createRental(player, rentItem, price);
            player.sendMessage(ChatColor.GRAY + "Price set to: " + ChatColor.GREEN + formatCoin(price));
        }
        private void setPlayerShopName(Player player, String shopName) {
            if (shopName.length() > 32) {
                player.sendMessage(ChatColor.RED + "Shop name too long! Maximum 32 characters.");
                return;
            }
            PlayerData data = getPlayerData(player.getUniqueId());
            if (data != null) {
                data.shopName = shopName;
                savePlayerData(data);
                player.sendMessage(ChatColor.GREEN + "Your rental shop name has been set to: " + shopName);
            }
        }
        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            List<String> completions = new ArrayList<>();
            if (args.length == 1) {
                completions.add("reload");
                completions.add("rent");
                completions.add("cancel");
                completions.add("name");
                return completions.stream()
                        .filter(s -> s.startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
            return completions;
        }
    }
    // ====================================================
    // CATEGORY SYSTEM
    // ====================================================
    public enum ItemCategory {
        ALL("All Items", Material.COMPASS, "All items available for rent"),
        TOOLS("Tools", Material.DIAMOND_PICKAXE, "Pickaxes, axes, shovels, hoes"),
        WEAPONS("Weapons", Material.DIAMOND_SWORD, "Swords, bows, crossbows"),
        ARMOR("Armor", Material.DIAMOND_CHESTPLATE, "Helmets, chestplates, leggings, boots"),
        MISC("Miscellaneous", Material.CHEST, "Other items");
        private final String displayName;
        private final Material icon;
        private final String description;
        ItemCategory(String displayName, Material icon, String description) {
            this.displayName = displayName;
            this.icon = icon;
            this.description = description;
        }
        public String getDisplayName() { return displayName; }
        public Material getIcon() { return icon; }
        public String getDescription() { return description; }
        public static ItemCategory fromMaterial(Material material) {
            String name = material.name();
            if (name.contains("PICKAXE") || name.contains("AXE") || name.contains("SHOVEL") ||
                name.contains("HOE") || name.contains("FISHING_ROD") || name.contains("SHEARS") ||
                name.contains("FLINT_AND_STEEL")) {
                return TOOLS;
            }
            if (name.contains("SWORD") || name.contains("BOW") || name.contains("CROSSBOW") ||
                name.contains("TRIDENT") || name.contains("SHIELD") || name.contains("ARROW")) {
                return WEAPONS;
            }
            if (name.contains("HELMET") || name.contains("CHESTPLATE") || name.contains("LEGGINGS") ||
                name.contains("BOOTS") || name.contains("ELYTRA") || name.contains("HORSE_ARMOR")) {
                return ARMOR;
            }
            return MISC;
        }
    }
    // ====================================================
    // DATA CLASSES (mantidas idênticas)
    // ====================================================
    private static class RentalsData {
        List<RentalItem> rentals = new ArrayList<>();
    }
    private static class RentalItem {
        String id;
        UUID ownerUuid;
        String ownerName;
        String ownerShopName;
        ItemStack item;
        BigDecimal price;
        long listedAt;
        RentalItem(String id, UUID ownerUuid, String ownerName, String ownerShopName,
                   ItemStack item, BigDecimal price, long listedAt) {
            this.id = id;
            this.ownerUuid = ownerUuid;
            this.ownerName = ownerName;
            this.ownerShopName = ownerShopName;
            this.item = item;
            this.price = price;
            this.listedAt = listedAt;
        }
    }
    private static class ActiveRental {
        String id;
        UUID ownerUuid;
        UUID renterUuid;
        String renterName;
        ItemStack originalItem;
        ItemStack currentItem;
        BigDecimal price;
        long startTime;
        long lastChargeTime;
        long nextChargeTime;
        int chargeCount;
        ActiveRental(String id, UUID ownerUuid, UUID renterUuid, String renterName,
                     ItemStack originalItem, ItemStack currentItem, BigDecimal price, long startTime) {
            this.id = id;
            this.ownerUuid = ownerUuid;
            this.renterUuid = renterUuid;
            this.renterName = renterName;
            this.originalItem = originalItem;
            this.currentItem = currentItem;
            this.price = price;
            this.startTime = startTime;
            this.lastChargeTime = startTime;
            this.nextChargeTime = startTime + MILLIS_PER_HOUR;
            this.chargeCount = 0;
        }
    }
    private static class RentalChargeHistory {
        String rentalId;
        List<ChargeRecord> charges = new ArrayList<>();
        RentalChargeHistory(String rentalId) {
            this.rentalId = rentalId;
        }
    }
    private static class ChargeRecord {
        long timestamp;
        BigDecimal amount;
        boolean success;
        ChargeRecord(long timestamp, BigDecimal amount, boolean success) {
            this.timestamp = timestamp;
            this.amount = amount;
            this.success = success;
        }
    }
    private static class RentPayment {
        String id;
        String fromCard;
        String toCard;
        BigDecimal amount;
        String rentalId;
        UUID ownerUuid;
        UUID renterUuid;
        RentPayment(String id, String fromCard, String toCard, BigDecimal amount,
                    String rentalId, UUID ownerUuid, UUID renterUuid) {
            this.id = id;
            this.fromCard = fromCard;
            this.toCard = toCard;
            this.amount = amount;
            this.rentalId = rentalId;
            this.ownerUuid = ownerUuid;
            this.renterUuid = renterUuid;
        }
    }
    private static class PendingPayment {
        String id;
        UUID renterUuid;
        UUID ownerUuid;
        String rentalId;
        ItemStack item;
        BigDecimal price;
        PendingPayment(String id, UUID renterUuid, UUID ownerUuid, String rentalId,
                       ItemStack item, BigDecimal price) {
            this.id = id;
            this.renterUuid = renterUuid;
            this.ownerUuid = ownerUuid;
            this.rentalId = rentalId;
            this.item = item;
            this.price = price;
        }
    }
    private static class PlayerData {
        UUID uuid;
        String shopName;
        File file;
        PlayerData(UUID uuid, String shopName, File file) {
            this.uuid = uuid;
            this.shopName = shopName;
            this.file = file;
        }
    }
    private static class PlayerSession {
        UUID uuid;
        long lastAction;
        Set<String> selectedCancellations = new HashSet<>();
        PlayerSession(UUID uuid) {
            this.uuid = uuid;
            this.lastAction = System.currentTimeMillis();
        }
    }
    private interface BalanceCheckCallback {
        void onSuccess(BigDecimal balance);
        void onFailure(String error);
    }
    private interface RentalCheckCallback {
        void onResult(boolean hasItem, ItemStack currentItem);
    }
    // ====================================================
    // INVENTORY HOLDER
    // ====================================================
    public static class RentInventoryHolder implements InventoryHolder {
        public enum Type {
            MAIN_MENU,
            CATEGORIES_MENU,
            CATEGORY_RENTALS,
            MY_RENTALS,
            GLOBAL_RENTALS,
            CANCEL_MENU
        }
        private final Type type;
        private final ItemCategory category;
        private final int page;
        private final UUID viewerUuid;
        public RentInventoryHolder(Type type, ItemCategory category, int page, UUID viewerUuid) {
            this.type = type;
            this.category = category;
            this.page = page;
            this.viewerUuid = viewerUuid;
        }
        @Override
        public Inventory getInventory() { return null; }
        public Type getType() { return type; }
        public ItemCategory getCategory() { return category; }
        public int getPage() { return page; }
        public UUID getViewerUuid() { return viewerUuid; }
    }
}
