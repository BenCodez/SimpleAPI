package com.bencodez.simpleapi.dialog;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UniDialogInput {

	public enum InputType {
		TEXT,
		BOOLEAN
	}

	private String id;
	private String label;
	private String initialValue;
	private boolean required;
	private InputType type = InputType.TEXT;
	private boolean initialBoolean;
}
