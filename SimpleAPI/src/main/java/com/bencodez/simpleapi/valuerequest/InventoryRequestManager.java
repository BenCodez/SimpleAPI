package com.bencodez.simpleapi.valuerequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/**
 * Handles value requests that use an inventory interface for selection.
 *
 * Options are presented as clickable items. Additional buttons are provided for
 * custom input and cycling the player's preferred input method.
 */
public class InventoryRequestManager implements Listener {

    /**
     * Flag to ensure the listener is only registered once per plugin instance.
     */
    private static boolean initialized;

    /**
     * Plugin used for synchronous task scheduling.
     */
    private static Plugin plugin;

    /**
     * Map of active inventory requests keyed by the player's unique identifier.
     */
    private static final Map<UUID, RequestContext> contexts = new ConcurrentHashMap<>();

    /**
     * Map of active input method selection inventories keyed by player unique id.
     */
    private static final Map<UUID, Consumer<InputMethod>> methodSelections = new ConcurrentHashMap<>();

    /**
     * Registers this listener with the plugin manager if it has not already been
     * registered.
     *
     * @param pluginInstance the plugin used for event registration and scheduling
     */
    public static void initialize(Plugin pluginInstance) {
        if (pluginInstance == null) {
            throw new IllegalArgumentException("pluginInstance cannot be null");
        }

        plugin = pluginInstance;

        if (!initialized) {
            Bukkit.getPluginManager().registerEvents(new InventoryRequestManager(), pluginInstance);
            initialized = true;
        }
    }

    /**
     * Opens an inventory for selecting a string value.
     *
     * @param pluginInstance the plugin instance
     * @param request the originating value request
     * @param player the player to prompt
     * @param currentValue the current value
     * @param options list of selectable string options
     * @param allowCustom whether a custom input button should be provided
     * @param promptText the inventory title
     * @param listener the callback invoked with the selected value
     */
    public static void openStringRequest(Plugin pluginInstance, ValueRequest request, Player player,
            String currentValue, List<String> options, boolean allowCustom, String promptText,
            StringListener listener) {
        List<String> safeOptions = options == null ? new ArrayList<>() : new ArrayList<>(options);

        runSynchronously(pluginInstance, player, () -> openStringRequestSync(request, player, currentValue,
                safeOptions, allowCustom, promptText, listener));
    }

    /**
     * Opens the string selection inventory on the server thread.
     *
     * @param request the originating value request
     * @param player the player to prompt
     * @param currentValue the current value
     * @param options list of selectable string options
     * @param allowCustom whether a custom input button should be provided
     * @param promptText the inventory title
     * @param listener the callback invoked with the selected value
     */
    private static void openStringRequestSync(ValueRequest request, Player player, String currentValue,
            List<String> options, boolean allowCustom, String promptText, StringListener listener) {
        int slotCount = options.size() + 1;

        if (allowCustom) {
            slotCount++;
        }

        Inventory inventory = Bukkit.createInventory(null, getInventorySize(slotCount),
                getTitle(promptText, "Select a value"));

        for (String option : options) {
            if (option == null) {
                continue;
            }

            inventory.addItem(createItem(Material.PAPER, option));
        }

        if (allowCustom) {
            inventory.addItem(createItem(Material.ANVIL, "Enter Custom Value"));
        }

        inventory.addItem(createItem(Material.COMPASS, "Change Method"));

        RequestContext context = new RequestContext();
        context.type = RequestType.STRING;
        context.stringOptions = options;
        context.stringListener = listener;
        context.currentString = currentValue;
        context.allowCustom = allowCustom;
        context.request = request;
        context.promptText = promptText;

        contexts.put(player.getUniqueId(), context);
        player.openInventory(inventory);
    }

    /**
     * Opens an inventory for selecting a numeric value.
     *
     * @param pluginInstance the plugin instance
     * @param request the originating value request
     * @param player the player to prompt
     * @param currentValue the current numeric value
     * @param options list of selectable numbers
     * @param allowCustom whether a custom input button should be provided
     * @param promptText the inventory title
     * @param listener the callback invoked with the selected number
     */
    public static void openNumberRequest(Plugin pluginInstance, ValueRequest request, Player player,
            Number currentValue, List<? extends Number> options, boolean allowCustom, String promptText,
            NumberListener listener) {
        List<? extends Number> safeNumberOptions = options == null ? new ArrayList<>() : new ArrayList<>(options);

        runSynchronously(pluginInstance, player, () -> openNumberRequestSync(request, player, currentValue,
                safeNumberOptions, allowCustom, promptText, listener));
    }

    /**
     * Opens the numeric selection inventory on the server thread.
     *
     * @param request the originating value request
     * @param player the player to prompt
     * @param currentValue the current numeric value
     * @param options list of selectable numbers
     * @param allowCustom whether a custom input button should be provided
     * @param promptText the inventory title
     * @param listener the callback invoked with the selected number
     */
    private static void openNumberRequestSync(ValueRequest request, Player player, Number currentValue,
            List<? extends Number> options, boolean allowCustom, String promptText, NumberListener listener) {
        List<String> stringOptions = new ArrayList<>();

        for (Number number : options) {
            if (number != null) {
                stringOptions.add(number.toString());
            }
        }

        int slotCount = stringOptions.size() + 1;

        if (allowCustom) {
            slotCount++;
        }

        Inventory inventory = Bukkit.createInventory(null, getInventorySize(slotCount),
                getTitle(promptText, "Select a value"));

        for (String option : stringOptions) {
            inventory.addItem(createItem(Material.PAPER, option));
        }

        if (allowCustom) {
            inventory.addItem(createItem(Material.ANVIL, "Enter Custom Value"));
        }

        inventory.addItem(createItem(Material.COMPASS, "Change Method"));

        RequestContext context = new RequestContext();
        context.type = RequestType.NUMBER;
        context.stringOptions = stringOptions;
        context.numberOptions = options;
        context.numberListener = listener;
        context.currentNumber = currentValue;
        context.allowCustom = allowCustom;
        context.request = request;
        context.promptText = promptText;

        contexts.put(player.getUniqueId(), context);
        player.openInventory(inventory);
    }

    /**
     * Opens an inventory for selecting a boolean value.
     *
     * @param pluginInstance the plugin instance
     * @param request the originating value request
     * @param player the player to prompt
     * @param currentValue the current boolean value
     * @param promptText the inventory title
     * @param listener the callback invoked with the selected boolean
     */
    public static void openBooleanRequest(Plugin pluginInstance, ValueRequest request, Player player,
            Boolean currentValue, String promptText, BooleanListener listener) {
        runSynchronously(pluginInstance, player,
                () -> openBooleanRequestSync(request, player, currentValue, promptText, listener));
    }

    /**
     * Opens the boolean selection inventory on the server thread.
     *
     * @param request the originating value request
     * @param player the player to prompt
     * @param currentValue the current boolean value
     * @param promptText the inventory title
     * @param listener the callback invoked with the selected boolean
     */
    private static void openBooleanRequestSync(ValueRequest request, Player player, Boolean currentValue,
            String promptText, BooleanListener listener) {
        Inventory inventory = Bukkit.createInventory(null, 9, getTitle(promptText, "Select an option"));

        inventory.setItem(3, createItem(Material.LIME_DYE, "True"));
        inventory.setItem(5, createItem(Material.RED_DYE, "False"));
        inventory.setItem(8, createItem(Material.COMPASS, "Change Method"));

        RequestContext context = new RequestContext();
        context.type = RequestType.BOOLEAN;
        context.booleanListener = listener;
        context.currentBoolean = currentValue;
        context.request = request;
        context.promptText = promptText;

        contexts.put(player.getUniqueId(), context);
        player.openInventory(inventory);
    }

    /**
     * Opens an inventory that lets a player choose their preferred input method.
     *
     * @param player the player to show the method selection to
     * @param callback the callback invoked with the selected method
     */
    public static void openMethodSelection(Player player, Consumer<InputMethod> callback) {
        Plugin schedulingPlugin = plugin;

        if (schedulingPlugin == null) {
            throw new IllegalStateException("InventoryRequestManager has not been initialized");
        }

        runSynchronously(schedulingPlugin, player, () -> openMethodSelectionSync(player, callback));
    }

    /**
     * Opens the input method selection inventory on the server thread.
     *
     * @param player the player to show the method selection to
     * @param callback the callback invoked with the selected method
     */
    private static void openMethodSelectionSync(Player player, Consumer<InputMethod> callback) {
        if (callback == null) {
            return;
        }

        InputMethod[] methods = InputMethod.values();
        Inventory inventory = Bukkit.createInventory(null, getInventorySize(methods.length),
                "Select Input Method");

        for (InputMethod method : methods) {
            inventory.addItem(createItem(Material.PAPER, method.name()));
        }

        methodSelections.put(player.getUniqueId(), callback);
        player.openInventory(inventory);
    }

    /**
     * Handles inventory clicks for value selection.
     *
     * @param event the inventory click event
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        UUID uuid = player.getUniqueId();
        Consumer<InputMethod> selectionCallback = methodSelections.get(uuid);

        if (selectionCallback != null) {
            handleMethodSelection(event, player, uuid, selectionCallback);
            return;
        }

        RequestContext context = contexts.get(uuid);

        if (context == null) {
            return;
        }

        event.setCancelled(true);

        String itemName = getItemName(event.getCurrentItem());

        if (itemName == null) {
            return;
        }

        if (itemName.equalsIgnoreCase("Change Method")) {
            contexts.remove(uuid);
            player.closeInventory();

            runNextTick(player, () -> openMethodSelectionSync(player, newMethod -> {
                PlayerInputManager.setInputMethod(uuid, newMethod);
                reopenRequest(player, context, newMethod);
            }));
            return;
        }

        if (itemName.equalsIgnoreCase("Enter Custom Value")) {
            contexts.remove(uuid);
            player.closeInventory();

            runNextTick(player, () -> reopenRequest(player, context, InputMethod.CHAT));
            return;
        }

        contexts.remove(uuid);

        if (context.type == RequestType.STRING && context.stringListener != null) {
            context.stringListener.onInput(player, itemName);
        } else if (context.type == RequestType.NUMBER && context.numberListener != null) {
            try {
                context.numberListener.onInput(player, Double.valueOf(itemName));
            } catch (NumberFormatException exception) {
                player.sendMessage("Invalid number: " + itemName);
            }
        } else if (context.type == RequestType.BOOLEAN && context.booleanListener != null) {
            if (itemName.equalsIgnoreCase("True")) {
                context.booleanListener.onInput(player, true);
            } else if (itemName.equalsIgnoreCase("False")) {
                context.booleanListener.onInput(player, false);
            }
        }

        player.closeInventory();
    }

    /**
     * Handles selection of a preferred input method.
     *
     * @param event the inventory click event
     * @param player the player selecting a method
     * @param uuid the player's unique identifier
     * @param callback the method-selection callback
     */
    private void handleMethodSelection(InventoryClickEvent event, Player player, UUID uuid,
            Consumer<InputMethod> callback) {
        event.setCancelled(true);

        String methodName = getItemName(event.getCurrentItem());

        if (methodName == null) {
            return;
        }

        try {
            InputMethod selectedMethod = InputMethod.valueOf(methodName.toUpperCase());
            methodSelections.remove(uuid);
            player.closeInventory();
            callback.accept(selectedMethod);
        } catch (IllegalArgumentException exception) {
            player.sendMessage("Invalid input method: " + methodName);
        }
    }

    /**
     * Reopens a request using the supplied input method.
     *
     * @param player the target player
     * @param context the stored request context
     * @param inputMethod the input method to use
     */
    private static void reopenRequest(Player player, RequestContext context, InputMethod inputMethod) {
        ValueRequest valueRequest = new ValueRequest(context.request.getPlugin(),
                context.request.getDialogService(), inputMethod);

        if (context.type == RequestType.STRING) {
            valueRequest.requestString(player, context.currentString, context.stringOptions,
                    context.allowCustom, context.promptText, context.stringListener);
        } else if (context.type == RequestType.NUMBER) {
            valueRequest.requestNumber(player, context.currentNumber, context.numberOptions,
                    context.allowCustom, context.promptText, context.numberListener);
        } else if (context.type == RequestType.BOOLEAN) {
            valueRequest.requestBoolean(player, context.currentBoolean, context.promptText,
                    context.booleanListener);
        }
    }

    /**
     * Cleans up context when a player closes a request inventory.
     *
     * @param event the inventory close event
     */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        UUID uuid = player.getUniqueId();
        contexts.remove(uuid);
        methodSelections.remove(uuid);
    }

    /**
     * Runs an operation synchronously.
     *
     * @param pluginInstance the plugin used for scheduling
     * @param player the target player
     * @param operation the operation to run
     */
    private static void runSynchronously(Plugin pluginInstance, Player player, Runnable operation) {
        if (pluginInstance == null || player == null || operation == null) {
            return;
        }

        if (Bukkit.isPrimaryThread()) {
            if (player.isOnline()) {
                operation.run();
            }
            return;
        }

        Bukkit.getScheduler().runTask(pluginInstance, () -> {
            if (player.isOnline()) {
                operation.run();
            }
        });
    }

    /**
     * Runs an operation on the next server tick.
     *
     * @param player the target player
     * @param operation the operation to run
     */
    private static void runNextTick(Player player, Runnable operation) {
        Plugin schedulingPlugin = plugin;

        if (schedulingPlugin == null || player == null || operation == null) {
            return;
        }

        Bukkit.getScheduler().runTask(schedulingPlugin, () -> {
            if (player.isOnline()) {
                operation.run();
            }
        });
    }

    /**
     * Creates an inventory item with a display name.
     *
     * @param material the item material
     * @param displayName the display name
     * @return the created item
     */
    private static ItemStack createItem(Material material, String displayName) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(displayName == null ? "" : displayName);
            item.setItemMeta(meta);
        }

        return item;
    }

    /**
     * Gets the display name of an inventory item.
     *
     * @param item the inventory item
     * @return the display name, or null when unavailable
     */
    private static String getItemName(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();

        if (meta == null || !meta.hasDisplayName()) {
            return null;
        }

        return meta.getDisplayName();
    }

    /**
     * Calculates a valid chest inventory size.
     *
     * @param slotCount the number of required slots
     * @return a chest inventory size between 9 and 54
     */
    private static int getInventorySize(int slotCount) {
        int size = Math.max(9, ((slotCount + 8) / 9) * 9);
        return Math.min(size, 54);
    }

    /**
     * Resolves an inventory title.
     *
     * @param requestedTitle the requested title
     * @param fallbackTitle the fallback title
     * @return the resolved title
     */
    private static String getTitle(String requestedTitle, String fallbackTitle) {
        if (requestedTitle == null || requestedTitle.isEmpty()) {
            return fallbackTitle;
        }

        return requestedTitle;
    }

    /**
     * Types of requests supported by the inventory interface.
     */
    private enum RequestType {

        /**
         * String request type.
         */
        STRING,

        /**
         * Number request type.
         */
        NUMBER,

        /**
         * Boolean request type.
         */
        BOOLEAN
    }

    /**
     * Context information for an active inventory request.
     */
    private static class RequestContext {

        private RequestType type;
        private List<String> stringOptions;
        private List<? extends Number> numberOptions;
        private StringListener stringListener;
        private NumberListener numberListener;
        private BooleanListener booleanListener;
        private String currentString;
        private Number currentNumber;
        private boolean allowCustom;
        private Boolean currentBoolean;
        private ValueRequest request;
        private String promptText;
    }
}