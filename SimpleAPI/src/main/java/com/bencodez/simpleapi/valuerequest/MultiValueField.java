package com.bencodez.simpleapi.valuerequest;

import lombok.Getter;
import lombok.Setter;

/**
 * Represents a field in a multi-value request.
 */
@Getter
@Setter
public class MultiValueField {

	public enum FieldType {
		STRING,
		NUMBER,
		BOOLEAN
	}

	private String id;

	private String label;

	private FieldType type = FieldType.STRING;

	private String currentStringValue = "";

	private Number currentNumberValue = null;

	private Boolean currentBooleanValue = null;

	private boolean required = true;

	public MultiValueField(String id, String label, FieldType type) {
		this.id = id;
		this.label = label;
		this.type = type;
	}

	public MultiValueField stringValue(String value) {
		this.currentStringValue = value;
		return this;
	}

	public MultiValueField numberValue(Number value) {
		this.currentNumberValue = value;
		return this;
	}

	public MultiValueField booleanValue(Boolean value) {
		this.currentBooleanValue = value;
		return this;
	}

	public MultiValueField required(boolean required) {
		this.required = required;
		return this;
	}
}