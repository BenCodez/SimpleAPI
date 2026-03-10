package com.bencodez.simpleapi.dialog;

import java.util.HashMap;
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
    private final HashMap<String, String> placeholders = new HashMap<String, String>();

    public MultiActionDialogBuilder(UniDialogService service, Player player) {
        this.service = service;
        this.player = player;
    }

    public MultiActionDialogBuilder placeholder(String placeholder, String value) {
        placeholders.put(placeholder, value);
        return this;
    }

    public MultiActionDialogBuilder placeholders(HashMap<String, String> placeholders) {
        if (placeholders != null) {
            this.placeholders.putAll(placeholders);
        }
        return this;
    }

    public MultiActionDialogBuilder title(String title) {
        request.setTitle(DialogTextFormatter.format(player, title, placeholders));
        return this;
    }

    public MultiActionDialogBuilder body(String body) {
        request.setBody(DialogTextFormatter.format(player, body, placeholders));
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

    public MultiActionDialogBuilder input(String id, java.util.function.Consumer<DialogInputBuilder> builderConsumer) {
        DialogInputBuilder builder = new DialogInputBuilder(id, player, placeholders);
        builderConsumer.accept(builder);
        request.addInput(builder.build());
        return this;
    }

    public MultiActionDialogBuilder button(String text, Consumer<DialogPayload> callback) {
        UniDialogButton button = new UniDialogButton();
        button.setText(DialogTextFormatter.format(player, text, placeholders));
        button.setCallback(callback);
        request.getButtons().add(button);
        return this;
    }

    public MultiActionDialogBuilder button(String text, String tooltip, Consumer<DialogPayload> callback) {
        UniDialogButton button = new UniDialogButton();
        button.setText(DialogTextFormatter.format(player, text, placeholders));
        button.setTooltip(DialogTextFormatter.format(player, tooltip, placeholders));
        button.setCallback(callback);
        request.getButtons().add(button);
        return this;
    }

    public MultiActionDialogBuilder button(String text, String tooltip, Integer width, String actionId,
            Consumer<DialogPayload> callback) {
        UniDialogButton button = new UniDialogButton();
        button.setText(DialogTextFormatter.format(player, text, placeholders));
        button.setTooltip(DialogTextFormatter.format(player, tooltip, placeholders));
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