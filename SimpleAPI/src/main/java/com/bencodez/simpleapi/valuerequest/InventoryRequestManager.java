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
 * Handles value requests that use an inventory interface for selection. Options
 * are presented as clickable items. Additional buttons are provided for custom
 * input and cycling the player's preferred input method.
 */
public class InventoryRequestManager implements Listener {

    /**
     * Flag to ensure the listener is only registered once per plugin instance.
     */
    private static boolean initialized = false;

    /**
     * Map of active inventory requests keyed by the player's unique identifier.
     */
    private static final Map<UUID, RequestContext> contexts = new ConcurrentHashMap<>();

    /**
     * Map of active input method selection inventories keyed by player unique id.
     */
    private static final Map<UUID, Consumer<InputMethod>> methodSelections = new ConcurrentHashMap<>();

    /**
     * Register this listener with the plugin manager if not already registered.
     *
     * @param plugin the plugin used for event registration
     */
    public static void initialize(Plugin plugin) {
        if (!initialized) {
            Bukkit.getPluginManager().registerEvents(new InventoryRequestManager(), plugin);
            initialized = true;
        }
    }

    /**
     * Open an inventory for selecting a string value. Options are displayed as
     * paper items, with optional buttons for custom input and changing the input
     * method.
     *
     * @param plugin       the plugin instance
     * @param request      the originating value request
     * @param player       the player to prompt
     * @param currentValue the current value (unused in this interface but may be
     *                     displayed via the title)
     * @param options      list of selectable string options
     * @param allowCustom  whether a custom input button should be provided
     * @param promptText   the inventory title
     * @param listener     the callback invoked with the selected value
     */
    public static void openStringRequest(Plugin plugin, ValueRequest request, Player player, String currentValue,
            List<String> options, boolean allowCustom, String promptText, StringListener listener) {
        if (options == null) {
            options = new ArrayList<>();
        }
        int slotCount = options.size();
        if (allowCustom) {
            slotCount++;
        }
        slotCount++; // for change method
        int size = ((slotCount + 8) / 9) * 9;
        if (size == 0) {
            size = 9;
        }
        String title = (promptText != null && !promptText.isEmpty()) ? promptText : "Select a value";
        Inventory inv = Bukkit.createInventory(null, size, title);
        // add option items
        for (String option : options) {
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(option);
                item.setItemMeta(meta);
            }
            inv.addItem(item);
        }
        // add custom input button
        if (allowCustom) {
            ItemStack custom = new ItemStack(Material.ANVIL);
            ItemMeta m = custom.getItemMeta();
            if (m != null) {
                m.setDisplayName("Enter Custom Value");
                custom.setItemMeta(m);
            }
            inv.addItem(custom);
        }
        // add change method button
        ItemStack change = new ItemStack(Material.COMPASS);
        ItemMeta cm = change.getItemMeta();
        if (cm != null) {
            cm.setDisplayName("Change Method");
            change.setItemMeta(cm);
        }
        inv.addItem(change);
        RequestContext ctx = new RequestContext();
        ctx.type = RequestType.STRING;
        ctx.stringOptions = options;
        ctx.numberOptions = null;
        ctx.stringListener = listener;
        ctx.numberListener = null;
        ctx.booleanListener = null;
        ctx.currentString = currentValue;
        ctx.currentNumber = null;
        ctx.allowCustom = allowCustom;
        ctx.request = request;
        ctx.promptText = promptText;
        contexts.put(player.getUniqueId(), ctx);
        player.openInventory(inv);
    }

    /**
     * Open an inventory for selecting a numeric value. Numbers are displayed as
     * paper items. A custom input button allows chat input for arbitrary values.
     *
     * @param plugin       the plugin instance
     * @param request      the originating value request
     * @param player       the player to prompt
     * @param currentValue the current numeric value
     * @param options      list of selectable numbers
     * @param allowCustom  whether a custom input button should be provided
     * @param promptText   the inventory title
     * @param listener     the callback invoked with the selected number
     */
    public static void openNumberRequest(Plugin plugin, ValueRequest request, Player player, Number currentValue,
            List<? extends Number> options, boolean allowCustom, String promptText, NumberListener listener) {
        List<String> stringOptions = new ArrayList<>();
        if (options != null) {
            for (Number n : options) {
                stringOptions.add(n.toString());
            }
        }
        int slotCount = stringOptions.size();
        if (allowCustom) {
            slotCount++;
        }
        slotCount++;
        int size = ((slotCount + 8) / 9) * 9;
        if (size == 0) {
            size = 9;
        }
        String title = (promptText != null && !promptText.isEmpty()) ? promptText : "Select a value";
        Inventory inv = Bukkit.createInventory(null, size, title);
        for (String option : stringOptions) {
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(option);
                item.setItemMeta(meta);
            }
            inv.addItem(item);
        }
        if (allowCustom) {
            ItemStack custom = new ItemStack(Material.ANVIL);
            ItemMeta m = custom.getItemMeta();
            if (m != null) {
                m.setDisplayName("Enter Custom Value");
                custom.setItemMeta(m);
            }
            inv.addItem(custom);
        }
        ItemStack change = new ItemStack(Material.COMPASS);
        ItemMeta cm = change.getItemMeta();
        if (cm != null) {
            cm.setDisplayName("Change Method");
            change.setItemMeta(cm);
        }
        inv.addItem(change);
        RequestContext ctx = new RequestContext();
        ctx.type = RequestType.NUMBER;
        ctx.stringOptions = stringOptions;
        ctx.numberOptions = options;
        ctx.stringListener = null;
        ctx.numberListener = listener;
        ctx.booleanListener = null;
        ctx.currentString = null;
        ctx.currentNumber = currentValue;
        ctx.allowCustom = allowCustom;
        ctx.request = request;
        ctx.promptText = promptText;
        contexts.put(player.getUniqueId(), ctx);
        player.openInventory(inv);
    }

    /**
     * Open an inventory for selecting a boolean value. Two items represent true and
     * false along with a button to change the player's preferred method. There
     * is no custom input for boolean values.
     *
     * @param plugin       the plugin instance
     * @param request      the originating value request
     * @param player       the player to prompt
     * @param currentValue the current boolean value (unused in this interface)
     * @param promptText   the inventory title
     * @param listener     the callback invoked with the selected boolean
     */
    public static void openBooleanRequest(Plugin plugin, ValueRequest request, Player player, Boolean currentValue,
            String promptText, BooleanListener listener) {
        int size = 9;
        String title = (promptText != null && !promptText.isEmpty()) ? promptText : "Select an option";
        Inventory inv = Bukkit.createInventory(null, size, title);
        // True button
        ItemStack trueItem = new ItemStack(Material.LIME_DYE);
        ItemMeta trueMeta = trueItem.getItemMeta();
        if (trueMeta != null) {
            trueMeta.setDisplayName("True");
            trueItem.setItemMeta(trueMeta);
        }
        inv.setItem(3, trueItem);
        // False button
        ItemStack falseItem = new ItemStack(Material.RED_DYE);
        ItemMeta falseMeta = falseItem.getItemMeta();
        if (falseMeta != null) {
            falseMeta.setDisplayName("False");
            falseItem.setItemMeta(falseMeta);
        }
        inv.setItem(5, falseItem);
        // Change method button
        ItemStack change = new ItemStack(Material.COMPASS);
        ItemMeta cm = change.getItemMeta();
        if (cm != null) {
            cm.setDisplayName("Change Method");
            change.setItemMeta(cm);
        }
        inv.setItem(8, change);
        RequestContext ctx = new RequestContext();
        ctx.type = RequestType.BOOLEAN;
        ctx.stringOptions = null;
        ctx.numberOptions = null;
        ctx.stringListener = null;
        ctx.numberListener = null;
        ctx.booleanListener = listener;
        ctx.currentString = null;
        ctx.currentNumber = null;
        ctx.allowCustom = false;
        ctx.request = request;
        ctx.promptText = promptText;
        ctx.currentBoolean = currentValue;
        contexts.put(player.getUniqueId(), ctx);
        player.openInventory(inv);
    }

    /**
     * Open an inventory that lets a player choose their preferred input method
     * from the available list. Once the player clicks one of the methods, the
     * provided callback is invoked with the selected method.
     *
     * @param player the player to show the method selection to
     * @param callback the callback invoked with the selected method
     */
    public static void openMethodSelection(Player player, Consumer<InputMethod> callback) {
        if (player == null || callback == null) {
            return;
        }
        InputMethod[] methods = InputMethod.values();
        int size = ((methods.length + 8) / 9) * 9;
        if (size == 0) {
            size = 9;
        }
        Inventory inv = Bukkit.createInventory(null, size, "Select Input Method");
        for (InputMethod method : methods) {
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(method.name());
                item.setItemMeta(meta);
            }
            inv.addItem(item);
        }
        methodSelections.put(player.getUniqueId(), callback);
        player.openInventory(inv);
    }

    /**
     * Handle inventory clicks for value selection. When a player interacts with a
     * request inventory this method determines the clicked item and invokes the
     * appropriate callback or action.
     *
     * @param event the inventory click event
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        UUID uuid = player.getUniqueId();

        Consumer<InputMethod> selectionCallback = methodSelections.get(uuid);
        if (selectionCallback != null) {
            event.setCancelled(true);
            ItemStack selectionItem = event.getCurrentItem();
            if (selectionItem == null || selectionItem.getType() == Material.AIR) {
                return;
            }
            ItemMeta selectionMeta = selectionItem.getItemMeta();
            if (selectionMeta == null || !selectionMeta.hasDisplayName()) {
                return;
            }
            String methodName = selectionMeta.getDisplayName();
            try {
                InputMethod selectedMethod = InputMethod.valueOf(methodName.toUpperCase());
                methodSelections.remove(uuid);
                player.closeInventory();
                selectionCallback.accept(selectedMethod);
            } catch (IllegalArgumentException ex) {
                player.sendMessage("Invalid input method: " + methodName);
            }
            return;
        }

        RequestContext ctx = contexts.get(uuid);
        if (ctx == null) {
            return;
        }
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            return;
        }
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return;
        }
        String name = meta.getDisplayName();
        // Change method button
        if (name.equalsIgnoreCase("Change Method")) {
            contexts.remove(uuid);
            player.closeInventory();
            openMethodSelection(player, newMethod -> {
                PlayerInputManager.setInputMethod(uuid, newMethod);
                if (ctx.type == RequestType.STRING) {
                    new ValueRequest(ctx.request.getPlugin(), ctx.request.getDialogService(), newMethod)
                            .requestString(player, ctx.currentString, ctx.stringOptions, ctx.allowCustom, ctx.promptText,
                                    ctx.stringListener);
                } else if (ctx.type == RequestType.NUMBER) {
                    new ValueRequest(ctx.request.getPlugin(), ctx.request.getDialogService(), newMethod)
                            .requestNumber(player, ctx.currentNumber, ctx.numberOptions, ctx.allowCustom,
                                    ctx.promptText, ctx.numberListener);
                } else if (ctx.type == RequestType.BOOLEAN) {
                    new ValueRequest(ctx.request.getPlugin(), ctx.request.getDialogService(), newMethod)
                            .requestBoolean(player, ctx.currentBoolean, ctx.promptText, ctx.booleanListener);
                }
            });
            return;
        }
        // Custom input button
        if (name.equalsIgnoreCase("Enter Custom Value")) {
            contexts.remove(uuid);
            // fallback to chat
            if (ctx.type == RequestType.STRING) {
                new ValueRequest(ctx.request.getPlugin(), ctx.request.getDialogService(), InputMethod.CHAT)
                        .requestString(player, ctx.currentString, ctx.stringOptions, ctx.allowCustom, ctx.promptText,
                                ctx.stringListener);
            } else if (ctx.type == RequestType.NUMBER) {
                new ValueRequest(ctx.request.getPlugin(), ctx.request.getDialogService(), InputMethod.CHAT)
                        .requestNumber(player, ctx.currentNumber,
                                ctx.numberOptions,
                                ctx.allowCustom, ctx.promptText, ctx.numberListener);
            }
            player.closeInventory();
            return;
        }
        // handle selection
        contexts.remove(uuid);
        if (ctx.type == RequestType.STRING) {
            String selected = name;
            ctx.stringListener.onInput(player, selected);
        } else if (ctx.type == RequestType.NUMBER) {
            try {
                Double number = Double.valueOf(name);
                ctx.numberListener.onInput(player, number);
            } catch (NumberFormatException ex) {
                player.sendMessage("Invalid number: " + name);
            }
        } else if (ctx.type == RequestType.BOOLEAN) {
            if (name.equalsIgnoreCase("True")) {
                ctx.booleanListener.onInput(player, true);
            } else if (name.equalsIgnoreCase("False")) {
                ctx.booleanListener.onInput(player, false);
            }
        }
        player.closeInventory();
    }

    /**
     * Clean up context when a player closes the request inventory. This prevents
     * stale data and unintended interactions after the inventory is closed.
     *
     * @param event the inventory close event
     */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player) {
            UUID uuid = ((Player) event.getPlayer()).getUniqueId();
            contexts.remove(uuid);
            methodSelections.remove(uuid);
        }
    }

    /**
     * Types of requests supported by the inventory interface.
     */
    private enum RequestType {
        /** String request type. */
        STRING,
        /** Number request type. */
        NUMBER,
        /** Boolean request type. */
        BOOLEAN
    }

    /**
     * Context information for an active inventory request. Stores the request
     * parameters so they can be reused when the player selects to change their
     * input method or enters a custom value.
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
        /**
         * The current boolean value when requesting a boolean input. May be null.
         */
        private Boolean currentBoolean;
        private ValueRequest request;
        private String promptText;
    }
}