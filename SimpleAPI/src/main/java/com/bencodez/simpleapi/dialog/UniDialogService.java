package com.bencodez.simpleapi.dialog;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import io.github.projectunified.unidialog.core.payload.DialogPayload;
import lombok.Getter;

/**
 * Main service for showing dialogs.
 *
 * This service adds:
 * - platform auto selection
 * - one-shot custom action cleanup
 * - builder-style dialog helpers
 */
@Getter
public class UniDialogService {

    private final Plugin plugin;
    private final String namespace;
    private final UniDialogPlatform platform;
    private final Map<String, Boolean> managedActions = new ConcurrentHashMap<>();

    public UniDialogService(Plugin plugin) {
        this(plugin, plugin.getName().toLowerCase());
    }

    public UniDialogService(Plugin plugin, String namespace) {
        this.plugin = plugin;
        this.namespace = namespace;
        this.platform = UniDialogPlatformFactory.create(plugin, namespace);
    }

    public void register() {
        platform.register();
    }

    public void unregister() {
        cleanupManagedActions();
        platform.unregister();
        platform.unregisterAllCustomActions();
    }

    public boolean clearDialog(Player player) {
        return platform.clearDialog(player.getUniqueId());
    }

    public void showNotice(Player player, UniDialogNoticeRequest request) {
        String resolvedNamespace = resolveNamespace(request.getNamespace());
        String actionId = resolveActionId(request.getActionId());

        if (request.getCallback() != null) {
            registerManagedAction(resolvedNamespace, actionId, request.getCallback());
            request.setCallback(null);
        }

        request.setNamespace(resolvedNamespace);
        request.setActionId(actionId);
        platform.showNotice(player, request);
    }

    public void showConfirmation(Player player, UniDialogConfirmationRequest request) {
        String resolvedNamespace = resolveNamespace(request.getNamespace());
        String yesActionId = resolveActionId(request.getYesActionId());
        String noActionId = resolveActionId(request.getNoActionId());

        if (request.getYesCallback() != null) {
            registerManagedAction(resolvedNamespace, yesActionId, request.getYesCallback());
            request.setYesCallback(null);
        }

        if (request.getNoCallback() != null) {
            registerManagedAction(resolvedNamespace, noActionId, request.getNoCallback());
            request.setNoCallback(null);
        }

        request.setNamespace(resolvedNamespace);
        request.setYesActionId(yesActionId);
        request.setNoActionId(noActionId);
        platform.showConfirmation(player, request);
    }

    public void showMultiAction(Player player, UniDialogMultiActionRequest request) {
        String resolvedNamespace = resolveNamespace(request.getNamespace());
        request.setNamespace(resolvedNamespace);

        if (request.getButtons() != null) {
            for (UniDialogButton button : request.getButtons()) {
                if (button == null) {
                    continue;
                }

                String actionId = resolveActionId(button.getActionId());
                if (button.getCallback() != null) {
                    registerManagedAction(resolvedNamespace, actionId, button.getCallback());
                    button.setCallback(null);
                }
                button.setActionId(actionId);
            }
        }

        platform.showMultiAction(player, request);
    }

    public NoticeDialogBuilder notice(Player player) {
        return new NoticeDialogBuilder(this, player);
    }

    public ConfirmationDialogBuilder confirmation(Player player) {
        return new ConfirmationDialogBuilder(this, player);
    }

    public MultiActionDialogBuilder multiAction(Player player) {
        return new MultiActionDialogBuilder(this, player);
    }

    public void cleanupManagedActions() {
        for (String key : managedActions.keySet()) {
            String[] split = key.split(":", 2);
            if (split.length == 2) {
                platform.unregisterCustomAction(split[0], split[1]);
            }
        }
        managedActions.clear();
    }

    public void registerManagedAction(String namespace, String id, Consumer<DialogPayload> callback) {
        String key = namespace + ":" + id;
        managedActions.put(key, Boolean.TRUE);

        platform.registerCustomAction(namespace, id, payload -> {
            try {
                callback.accept(payload);
            } finally {
                platform.unregisterCustomAction(namespace, id);
                managedActions.remove(key);
            }
        });
    }

    public String resolveNamespace(String value) {
        if (value == null || value.isEmpty()) {
            return namespace;
        }
        return value;
    }

    public String resolveActionId(String value) {
        if (value == null || value.isEmpty()) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        return value;
    }
}
