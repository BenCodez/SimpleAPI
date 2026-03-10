package com.bencodez.simpleapi.dialog;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UniDialogInput {

    private String id;
    private String label;
    private String placeholder;
    private boolean required = false;

}