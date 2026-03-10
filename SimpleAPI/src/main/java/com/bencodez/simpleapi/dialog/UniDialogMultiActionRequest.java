package com.bencodez.simpleapi.dialog;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * Request to show a multi-action dialog.
 */
@Getter
@Setter
public class UniDialogMultiActionRequest {

    private String title;
    private String body;
    private String namespace;
    private int columns = 1;
    private Integer buttonWidth;
    private List<UniDialogButton> buttons = new ArrayList<UniDialogButton>();
    private List<UniDialogInput> inputs = new ArrayList<UniDialogInput>();

    public void addInput(UniDialogInput input) {
        inputs.add(input);
    }
}