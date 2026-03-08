package com.bencodez.simpleapi.dialog;

import java.util.function.Consumer;

import io.github.projectunified.unidialog.core.payload.DialogPayload;
import lombok.Getter;
import lombok.Setter;

/**
 * Request to show a notice dialog.
 */
@Getter
@Setter
public class UniDialogNoticeRequest {

    private String title;
    private String body;
    private String buttonText = "Ok";
    private String namespace;
    private String actionId;
    private Consumer<DialogPayload> callback;
}
