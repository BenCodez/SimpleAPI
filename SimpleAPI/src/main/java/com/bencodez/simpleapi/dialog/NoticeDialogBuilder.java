package com.bencodez.simpleapi.dialog;

import java.util.HashMap;
import java.util.function.Consumer;

import org.bukkit.entity.Player;

import io.github.projectunified.unidialog.core.payload.DialogPayload;

/**
 * Builder for notice dialogs.
 */
public class NoticeDialogBuilder {

    private final UniDialogService service;
    private final Player player;
    private final UniDialogNoticeRequest request = new UniDialogNoticeRequest();
    private final HashMap<String, String> placeholders = new HashMap<String, String>();

    public NoticeDialogBuilder(UniDialogService service, Player player) {
        this.service = service;
        this.player = player;
    }

    public NoticeDialogBuilder placeholder(String placeholder, String value) {
        placeholders.put(placeholder, value);
        return this;
    }

    public NoticeDialogBuilder placeholders(HashMap<String, String> placeholders) {
        if (placeholders != null) {
            this.placeholders.putAll(placeholders);
        }
        return this;
    }

    public NoticeDialogBuilder title(String title) {
        request.setTitle(DialogTextFormatter.format(player, title, placeholders));
        return this;
    }

    public NoticeDialogBuilder body(String body) {
        request.setBody(DialogTextFormatter.format(player, body, placeholders));
        return this;
    }

    public NoticeDialogBuilder button(String buttonText) {
        request.setButtonText(DialogTextFormatter.format(player, buttonText, placeholders));
        return this;
    }

    public NoticeDialogBuilder namespace(String namespace) {
        request.setNamespace(namespace);
        return this;
    }

    public NoticeDialogBuilder actionId(String actionId) {
        request.setActionId(actionId);
        return this;
    }

    public NoticeDialogBuilder input(String id, java.util.function.Consumer<DialogInputBuilder> builderConsumer) {
        DialogInputBuilder builder = new DialogInputBuilder(id, player, placeholders);
        builderConsumer.accept(builder);
        request.addInput(builder.build());
        return this;
    }

    public NoticeDialogBuilder onClick(Consumer<DialogPayload> callback) {
        request.setCallback(callback);
        return this;
    }

    public void open() {
        service.showNotice(player, request);
    }
}