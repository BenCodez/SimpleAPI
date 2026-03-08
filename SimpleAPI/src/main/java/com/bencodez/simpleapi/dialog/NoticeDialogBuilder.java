package com.bencodez.simpleapi.dialog;

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

    public NoticeDialogBuilder(UniDialogService service, Player player) {
        this.service = service;
        this.player = player;
    }

    public NoticeDialogBuilder title(String title) {
        request.setTitle(title);
        return this;
    }

    public NoticeDialogBuilder body(String body) {
        request.setBody(body);
        return this;
    }

    public NoticeDialogBuilder button(String buttonText) {
        request.setButtonText(buttonText);
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

    public NoticeDialogBuilder onClick(Consumer<DialogPayload> callback) {
        request.setCallback(callback);
        return this;
    }

    public void open() {
        service.showNotice(player, request);
    }
}
