package com.bencodez.simpleapi.dialog;

import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import io.github.projectunified.unidialog.bungeecord.dialog.BungeeMultiActionDialog;
import io.github.projectunified.unidialog.core.payload.DialogPayload;
import io.github.projectunified.unidialog.spigot.SpigotDialogManager;
import io.github.projectunified.unidialog.spigot.opener.SpigotDialogOpener;

/**
 * Spigot implementation using UniDialog's Spigot manager.
 */
public class SpigotUniDialogPlatform extends AbstractUniDialogPlatform {

    private final SpigotDialogManager manager;

    public SpigotUniDialogPlatform(Plugin plugin, String namespace) {
        super(plugin, namespace);
        this.manager = new SpigotDialogManager(plugin, namespace);
    }

    @Override
    public UniDialogBackendType getBackendType() {
        return UniDialogBackendType.SPIGOT;
    }

    @Override
    public void register() {
        manager.register();
    }

    @Override
    public void unregister() {
        manager.unregister();
    }

    @Override
    public boolean clearDialog(UUID uuid) {
        return manager.clearDialog(uuid);
    }

    @Override
    public void registerCustomAction(String namespace, String id, Consumer<DialogPayload> action) {
        manager.registerCustomAction(namespace, id, action);
    }

    @Override
    public void unregisterCustomAction(String namespace, String id) {
        manager.unregisterCustomAction(namespace, id);
    }

    @Override
    public void unregisterAllCustomActions() {
        manager.unregisterAllCustomActions();
    }

    @Override
    public void showNotice(Player player, UniDialogNoticeRequest request) {
        String namespace = resolveNamespace(request.getNamespace());
        String actionId = resolveActionId(request.getActionId());

        if (request.getCallback() != null) {
            registerCustomAction(namespace, actionId, request.getCallback());
        }

        manager.createNoticeDialog()
                .title(request.getTitle())
                .body(builder -> builder.text().text(request.getBody()))
                .action(action -> {
                    action.label(request.getButtonText());
                    action.dynamicCustom(namespace, actionId);
                })
                .opener()
                .open(player.getUniqueId());
    }

    @Override
    public void showConfirmation(Player player, UniDialogConfirmationRequest request) {
        String namespace = resolveNamespace(request.getNamespace());
        String yesActionId = resolveActionId(request.getYesActionId());
        String noActionId = resolveActionId(request.getNoActionId());

        if (request.getYesCallback() != null) {
            registerCustomAction(namespace, yesActionId, request.getYesCallback());
        }

        if (request.getNoCallback() != null) {
            registerCustomAction(namespace, noActionId, request.getNoCallback());
        }

        manager.createConfirmationDialog()
                .title(request.getTitle())
                .body(builder -> builder.text().text(request.getBody()))
                .yesAction(action -> {
                    action.label(request.getYesText());
                    action.dynamicCustom(namespace, yesActionId);
                })
                .noAction(action -> {
                    action.label(request.getNoText());
                    action.dynamicCustom(namespace, noActionId);
                })
                .opener()
                .open(player.getUniqueId());
    }

    @Override
    public void showMultiAction(Player player, UniDialogMultiActionRequest request) {
        String namespace = resolveNamespace(request.getNamespace());

        BungeeMultiActionDialog<SpigotDialogOpener> dialog = manager.createMultiActionDialog()
                .title(request.getTitle())
                .body(builder -> builder.text().text(request.getBody()))
                .columns(request.getColumns());

        for (UniDialogButton button : request.getButtons()) {

            String actionId = resolveActionId(button.getActionId());

            if (button.getCallback() != null) {
                registerCustomAction(namespace, actionId, button.getCallback());
            }

            dialog.action(action -> {
                action.label(button.getText());

                if (button.getTooltip() != null && !button.getTooltip().isEmpty()) {
                    action.tooltip(button.getTooltip());
                }

                Integer width = button.getWidth();
                if (width == null) {
                    width = request.getButtonWidth();
                }

                if (width != null) {
                    action.width(width.intValue());
                }

                action.dynamicCustom(namespace, actionId);
            });
        }

        dialog.opener().open(player.getUniqueId());
    }
}