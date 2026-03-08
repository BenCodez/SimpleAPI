package com.bencodez.simpleapi.dialog;

import java.util.function.Consumer;

import org.bukkit.entity.Player;

import io.github.projectunified.unidialog.core.payload.DialogPayload;

/**
 * Builder for multi-action dialogs.
 */
public class MultiActionDialogBuilder {

    private final UniDialogService service;
    private final Player player;
    private final UniDialogMultiActionRequest request = new UniDialogMultiActionRequest();

    public MultiActionDialogBuilder(UniDialogService service, Player player) {
        this.service = service;
        this.player = player;
    }

    public MultiActionDialogBuilder title(String title) {
        request.setTitle(title);
        return this;
    }

    public MultiActionDialogBuilder body(String body) {
        request.setBody(body);
        return this;
    }

    public MultiActionDialogBuilder namespace(String namespace) {
        request.setNamespace(namespace);
        return this;
    }

    public MultiActionDialogBuilder columns(int columns) {
        request.setColumns(columns);
        return this;
    }

    public MultiActionDialogBuilder buttonWidth(int width) {
        request.setButtonWidth(Integer.valueOf(width));
        return this;
    }

    public MultiActionDialogBuilder button(String text, Consumer<DialogPayload> callback) {
        UniDialogButton button = new UniDialogButton();
        button.setText(text);
        button.setCallback(callback);
        request.getButtons().add(button);
        return this;
    }

    public MultiActionDialogBuilder button(String text, String tooltip, Consumer<DialogPayload> callback) {
        UniDialogButton button = new UniDialogButton();
        button.setText(text);
        button.setTooltip(tooltip);
        button.setCallback(callback);
        request.getButtons().add(button);
        return this;
    }

    public MultiActionDialogBuilder button(String text, String tooltip, Integer width, String actionId,
            Consumer<DialogPayload> callback) {
        UniDialogButton button = new UniDialogButton();
        button.setText(text);
        button.setTooltip(tooltip);
        button.setWidth(width);
        button.setActionId(actionId);
        button.setCallback(callback);
        request.getButtons().add(button);
        return this;
    }

    public void open() {
        service.showMultiAction(player, request);
    }
}
