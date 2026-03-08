package com.bencodez.simpleapi.dialog;

import java.util.function.Consumer;

import org.bukkit.entity.Player;

import io.github.projectunified.unidialog.core.payload.DialogPayload;

/**
 * Builder for confirmation dialogs.
 */
public class ConfirmationDialogBuilder {

    private final UniDialogService service;
    private final Player player;
    private final UniDialogConfirmationRequest request = new UniDialogConfirmationRequest();

    public ConfirmationDialogBuilder(UniDialogService service, Player player) {
        this.service = service;
        this.player = player;
    }

    public ConfirmationDialogBuilder title(String title) {
        request.setTitle(title);
        return this;
    }

    public ConfirmationDialogBuilder body(String body) {
        request.setBody(body);
        return this;
    }

    public ConfirmationDialogBuilder yesText(String text) {
        request.setYesText(text);
        return this;
    }

    public ConfirmationDialogBuilder noText(String text) {
        request.setNoText(text);
        return this;
    }

    public ConfirmationDialogBuilder namespace(String namespace) {
        request.setNamespace(namespace);
        return this;
    }

    public ConfirmationDialogBuilder yesActionId(String actionId) {
        request.setYesActionId(actionId);
        return this;
    }

    public ConfirmationDialogBuilder noActionId(String actionId) {
        request.setNoActionId(actionId);
        return this;
    }

    public ConfirmationDialogBuilder onYes(Consumer<DialogPayload> callback) {
        request.setYesCallback(callback);
        return this;
    }

    public ConfirmationDialogBuilder onNo(Consumer<DialogPayload> callback) {
        request.setNoCallback(callback);
        return this;
    }

    public void open() {
        service.showConfirmation(player, request);
    }
}
