package com.bencodez.simpleapi.dialog;

import java.util.function.Consumer;

import io.github.projectunified.unidialog.core.payload.DialogPayload;
import lombok.Getter;
import lombok.Setter;

/**
 * Request to show a confirmation dialog.
 */
@Getter
@Setter
public class UniDialogConfirmationRequest {

    private String title;
    private String body;
    private String yesText = "Yes";
    private String noText = "No";
    private String namespace;
    private String yesActionId;
    private String noActionId;
    private Consumer<DialogPayload> yesCallback;
    private Consumer<DialogPayload> noCallback;
}
