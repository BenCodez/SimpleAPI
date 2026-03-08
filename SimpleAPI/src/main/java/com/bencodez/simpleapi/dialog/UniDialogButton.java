package com.bencodez.simpleapi.dialog;

import java.util.function.Consumer;

import io.github.projectunified.unidialog.core.payload.DialogPayload;
import lombok.Getter;
import lombok.Setter;

/**
 * Button for a multi-action dialog.
 */
@Getter
@Setter
public class UniDialogButton {

    private String text;
    private String tooltip;
    private Integer width;
    private String actionId;
    private Consumer<DialogPayload> callback;
}
