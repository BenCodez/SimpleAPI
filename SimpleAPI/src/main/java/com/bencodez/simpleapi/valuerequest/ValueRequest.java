package com.bencodez.simpleapi.valuerequest;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.bukkit.conversations.Conversable;
import org.bukkit.conversations.ConversationContext;
import org.bukkit.conversations.ConversationFactory;
import org.bukkit.conversations.Prompt;
import org.bukkit.conversations.StringPrompt;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.bencodez.simpleapi.dialog.MultiActionDialogBuilder;
import com.bencodez.simpleapi.dialog.UniDialogService;

// Imports intentionally left minimal; inventory and book handling is delegated
import lombok.Getter;
import lombok.Setter;

/**
 * Utility class for requesting values from players. Supports both modern
 * graphical dialogs via UniDialog as well as a chat based fallback for older
 * versions or servers without dialog support. This class is inspired by the
 * ValueRequest system from AdvancedCore but is designed to be simpler and more
 * self contained.
 */
public class ValueRequest {

	/**
	 * The plugin instance used for conversations and inventory registration.
	 */
	@Getter
	@Setter
	private Plugin plugin;
	/**
	 * The dialog service used to present GUI dialogs when available.
	 */
	@Getter
	@Setter
	private UniDialogService dialogService;
	/**
	 * The default input method for this request. If null the player's preference
	 * will be used.
	 */
	@Getter
	@Setter
	private InputMethod defaultMethod;

	/**
	 * Create a new value requester using the provided plugin and dialog service.
	 * The {@code method} parameter can be used to override the player's preferred
	 * method; if {@code null} then {@link InputMethod#DIALOG} will be used by
	 * default when available.
	 *
	 * @param plugin        the plugin used to construct conversations
	 * @param dialogService the dialog service used for GUI dialogs (may be null)
	 * @param method        the preferred input method for this request or null
	 */
	public ValueRequest(Plugin plugin, UniDialogService dialogService, InputMethod method) {
		this.plugin = plugin;
		this.dialogService = dialogService;
		this.defaultMethod = method;
		// ensure inventory listener registered
		InventoryRequestManager.initialize(plugin);
		// ensure book input manager registered
		BookInputManager.initialize(plugin);
		// ensure sign input manager registered
		SignInputManager.initialize(plugin);
	}

	/**
	 * Create a new value requester using the provided plugin and dialog service.
	 * This constructor defaults to using {@link InputMethod#DIALOG} when available.
	 *
	 * @param plugin        the plugin used to construct conversations
	 * @param dialogService the dialog service used for GUI dialogs (may be null)
	 */
	public ValueRequest(Plugin plugin, UniDialogService dialogService) {
		this(plugin, dialogService, null);
	}

	/**
	 * Determine the input method for a request. If a method was specified when
	 * constructing this {@link ValueRequest} it will be returned. Otherwise this
	 * method returns {@link InputMethod#DIALOG}.
	 *
	 * @return the resolved input method
	 */
	private InputMethod getEffectiveMethod(Player player) {
		if (defaultMethod != null) {
			return defaultMethod;
		}
		return PlayerInputManager.getInputMethod(player.getUniqueId());
	}

	/**
	 * Request a string value from a player. If no method is provided the default
	 * method for this requester will be used. This method has no options and allows
	 * custom input.
	 *
	 * @param player   the player to prompt
	 * @param listener the callback to invoke with the provided value
	 */
	public void requestString(Player player, StringListener listener) {
		requestString(player, null, null, true, null, listener);
	}

	/**
	 * Request a string value with a list of selectable options. If
	 * {@code allowCustom} is true the player may provide their own value in
	 * addition to selecting from the provided options. When no options are provided
	 * and custom input is not allowed the method falls back to chat regardless of
	 * the selected input method.
	 *
	 * @param player       the player to prompt
	 * @param currentValue the currently set value (may be null)
	 * @param options      a list of valid options (may be null or empty)
	 * @param allowCustom  whether players may enter their own value
	 * @param promptText   an optional prompt to display above the dialog; if null a
	 *                     default will be used
	 * @param listener     the callback to invoke with the provided value
	 */
	public void requestString(Player player, String currentValue, List<String> options, boolean allowCustom,
			String promptText, StringListener listener) {
		InputMethod method = getEffectiveMethod(player);
		boolean hasOptions = options != null && !options.isEmpty();

		// Determine if a dialog can be shown
		boolean canUseDialog = hasOptions || allowCustom;
		if (method == InputMethod.SIGN) {
			// Use sign input for string. Validate against options if necessary
			String start = currentValue != null ? currentValue : "";
			SignInputManager.requestSignInput(player, start, input -> {
				String value = input != null ? input.trim() : "";
				if (!allowCustom && hasOptions && options != null && !options.contains(value)) {
					player.sendMessage("Invalid value: " + value);
					return;
				}
				listener.onInput(player, value);
			});
			return;
		} else if (method == InputMethod.INVENTORY) {
			// Use inventory interface for selection
			InventoryRequestManager.openStringRequest(plugin, this, player, currentValue, options, allowCustom,
					promptText, listener);
			return;
		} else if (method == InputMethod.BOOK) {
			// Use book input; fall back to chat if not allowed
			String start = currentValue != null ? currentValue : "";
			BookInputManager.requestBookInput(player, start, input -> listener.onInput(player, input));
			return;
		} else if (method == InputMethod.DIALOG && dialogService != null && canUseDialog) {
			// Build a UniDialog multi action dialog
			String inputId = "value_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
			String title = (promptText != null && !promptText.isEmpty()) ? promptText : "Select a value";
			String body = "";
			if (currentValue != null && !currentValue.isEmpty()) {
				body = "Current value: " + currentValue;
			}

			MultiActionDialogBuilder builder = dialogService.multiAction(player).title(title).body(body);

			if (allowCustom) {
				builder.input(inputId, inputBuilder -> {
					inputBuilder.label("Custom value");
					if (currentValue != null && !currentValue.isEmpty()) {
						inputBuilder.textInput().initial(currentValue);
					} else {
						inputBuilder.textInput().initial("Enter a value");
					}
					inputBuilder.required(!hasOptions);
				});

				builder.button("Use Custom Value", payload -> {
					String input = payload.textValue(inputId);
					if (input == null) {
						input = "";
					}
					input = input.trim();
					if (input.isEmpty()) {
						player.sendMessage("No custom value entered");
						return;
					}
					listener.onInput(player, input);
				});
			}

			if (hasOptions) {
				for (String option : options) {
					final String selected = option;
					builder.button(selected, payload -> listener.onInput(player, selected));
				}
			}
			// button to change method
			builder.button("Change Method",
					payload -> InventoryRequestManager.openMethodSelection(player, newMethod -> {
						PlayerInputManager.setInputMethod(player.getUniqueId(), newMethod);
						ValueRequest newRequest = new ValueRequest(plugin, dialogService, newMethod);
						newRequest.requestString(player, currentValue, options, allowCustom, promptText, listener);
					}));

			builder.open();
			return;
		}

		// Fallback to chat based conversation
		StringBuilder promptBuilder = new StringBuilder();
		if (promptText != null && !promptText.isEmpty()) {
			promptBuilder.append(promptText);
		} else {
			promptBuilder.append("Enter a value");
		}
		if (currentValue != null && !currentValue.isEmpty()) {
			promptBuilder.append(" (Current: ").append(currentValue).append(")");
		}
		if (hasOptions) {
			String opts = options.stream().collect(Collectors.joining(", "));
			promptBuilder.append(" [Options: ").append(opts).append("]");
		}

		ConversationFactory factory = new ConversationFactory(plugin).withModality(true).withTimeout(60)
				.withEscapeSequence("cancel");
		factory.withFirstPrompt(new StringPrompt() {
			@Override
			public String getPromptText(ConversationContext context) {
				return promptBuilder.toString();
			}

			@Override
			public Prompt acceptInput(ConversationContext context, String input) {
				// Trim input and handle cancel explicitly
				String value = input != null ? input.trim() : "";
				if (value.equalsIgnoreCase("cancel")) {
					return Prompt.END_OF_CONVERSATION;
				}
				if (!allowCustom && hasOptions && !options.contains(value)) {
					player.sendMessage("Invalid value: " + value);
					return Prompt.END_OF_CONVERSATION;
				}
				listener.onInput(player, value);
				return Prompt.END_OF_CONVERSATION;
			}
		}).buildConversation((Conversable) player).begin();
	}

	/**
	 * Request a numeric value from a player. If no options are provided the player
	 * may supply any number. When options are provided and {@code allowCustom} is
	 * false the player must choose one of the provided options.
	 *
	 * @param player       the player to prompt
	 * @param currentValue the currently set value (may be null)
	 * @param options      a list of allowed numeric options (may be null or empty)
	 * @param allowCustom  whether players may enter their own number
	 * @param promptText   an optional prompt to display above the dialog; if null a
	 *                     default will be used
	 * @param listener     the callback to invoke with the provided numeric value
	 */
	public void requestNumber(Player player, Number currentValue, List<? extends Number> options, boolean allowCustom,
			String promptText, NumberListener listener) {
		InputMethod method = getEffectiveMethod(player);
		boolean hasOptions = options != null && !options.isEmpty();
		boolean canUseDialog = hasOptions || allowCustom;

		if (method == InputMethod.SIGN) {
			// Use sign input for number values
			String start = currentValue != null ? currentValue.toString() : "";
			SignInputManager.requestSignInput(player, start, input -> {
				String text = input != null ? input.trim() : "";
				if (text.isEmpty()) {
					player.sendMessage("No value entered");
					return;
				}
				try {
					Double number = Double.valueOf(text);
					if (!allowCustom && hasOptions) {
						boolean match = false;
						if (options != null) {
							for (Number n : options) {
								if (number.doubleValue() == n.doubleValue()) {
									match = true;
									break;
								}
							}
						}
						if (!match) {
							player.sendMessage("Invalid number: " + text);
							return;
						}
					}
					listener.onInput(player, number);
				} catch (NumberFormatException ex) {
					player.sendMessage("Invalid number: " + text);
				}
			});
			return;
		} else if (method == InputMethod.INVENTORY) {
			InventoryRequestManager.openNumberRequest(plugin, this, player, currentValue, options, allowCustom,
					promptText, listener);
			return;
		} else if (method == InputMethod.BOOK) {
			// Use book to input number
			BookInputManager.requestBookInput(player, currentValue != null ? currentValue.toString() : "", input -> {
				String trimmed = input != null ? input.trim() : "";
				if (trimmed.isEmpty()) {
					player.sendMessage("No value entered");
					return;
				}
				try {
					Double number = Double.valueOf(trimmed);
					listener.onInput(player, number);
				} catch (NumberFormatException ex) {
					player.sendMessage("Invalid number: " + trimmed);
				}
			});
			return;
		} else if (method == InputMethod.DIALOG && dialogService != null && canUseDialog) {
			String inputId = "value_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
			String title = (promptText != null && !promptText.isEmpty()) ? promptText : "Select a number";
			String body = "";
			if (currentValue != null) {
				body = "Current value: " + currentValue;
			}
			MultiActionDialogBuilder builder = dialogService.multiAction(player).title(title).body(body);

			if (allowCustom) {
				builder.input(inputId, inputBuilder -> {
					inputBuilder.label("Custom number");
					if (currentValue != null) {
						inputBuilder.textInput().initial(currentValue.toString());
					} else {
						inputBuilder.textInput().initial("Enter a number");
					}
					inputBuilder.required(!hasOptions);
				});
				builder.button("Use Custom Value", payload -> {
					String text = payload.textValue(inputId);
					if (text == null) {
						text = "";
					}
					text = text.trim();
					if (text.isEmpty()) {
						player.sendMessage("No custom value entered");
						return;
					}
					try {
						Double number = Double.valueOf(text);
						listener.onInput(player, number);
					} catch (NumberFormatException ex) {
						player.sendMessage("Invalid number: " + text);
					}
				});
			}

			if (hasOptions) {
				for (Number num : options) {
					final Number selected = num;
					builder.button(selected.toString(), payload -> listener.onInput(player, selected));
				}
			}
			builder.button("Change Method",
					payload -> InventoryRequestManager.openMethodSelection(player, newMethod -> {
						PlayerInputManager.setInputMethod(player.getUniqueId(), newMethod);
						ValueRequest newRequest = new ValueRequest(plugin, dialogService, newMethod);
						newRequest.requestNumber(player, currentValue, options, allowCustom, promptText, listener);
					}));
			builder.open();
			return;
		}

		// Fallback to chat
		StringBuilder promptBuilder = new StringBuilder();
		if (promptText != null && !promptText.isEmpty()) {
			promptBuilder.append(promptText);
		} else {
			promptBuilder.append("Enter a number");
		}
		if (currentValue != null) {
			promptBuilder.append(" (Current: ").append(currentValue).append(")");
		}
		if (hasOptions) {
			String opts = options.stream().map(Object::toString).collect(Collectors.joining(", "));
			promptBuilder.append(" [Options: ").append(opts).append("]");
		}

		ConversationFactory factory = new ConversationFactory(plugin).withModality(true).withTimeout(60)
				.withEscapeSequence("cancel");
		factory.withFirstPrompt(new StringPrompt() {
			@Override
			public String getPromptText(ConversationContext context) {
				return promptBuilder.toString();
			}

			@Override
			public Prompt acceptInput(ConversationContext context, String input) {
				String value = input != null ? input.trim() : "";
				if (value.equalsIgnoreCase("cancel")) {
					return Prompt.END_OF_CONVERSATION;
				}
				Double number;
				try {
					number = Double.valueOf(value);
				} catch (NumberFormatException ex) {
					player.sendMessage("Invalid number: " + value);
					return Prompt.END_OF_CONVERSATION;
				}
				if (!allowCustom && hasOptions) {
					boolean match = false;
					for (Number n : options) {
						if (number.doubleValue() == n.doubleValue()) {
							match = true;
							break;
						}
					}
					if (!match) {
						player.sendMessage("Invalid number: " + value);
						return Prompt.END_OF_CONVERSATION;
					}
				}
				listener.onInput(player, number);
				return Prompt.END_OF_CONVERSATION;
			}
		}).buildConversation((Conversable) player).begin();
	}

	/**
	 * Request a boolean value from a player. This shows a dialog with true/false
	 * buttons when possible or falls back to chat input using "true" or "false".
	 *
	 * @param player       the player to prompt
	 * @param currentValue the currently set value (may be null)
	 * @param promptText   an optional prompt to display above the dialog; if null a
	 *                     default will be used
	 * @param listener     the callback to invoke with the provided boolean value
	 */
	public void requestBoolean(Player player, Boolean currentValue, String promptText, BooleanListener listener) {
		InputMethod method = getEffectiveMethod(player);
		if (method == InputMethod.SIGN) {
			// Use sign input for boolean values
			String start = currentValue != null ? currentValue.toString() : "";
			SignInputManager.requestSignInput(player, start, input -> {
				if (input == null) {
					return;
				}
				String trimmed = input.trim().toLowerCase();
				if (trimmed.equals("true") || trimmed.equals("yes") || trimmed.equals("y")) {
					listener.onInput(player, true);
				} else if (trimmed.equals("false") || trimmed.equals("no") || trimmed.equals("n")) {
					listener.onInput(player, false);
				} else {
					player.sendMessage("Invalid boolean value: " + input);
				}
			});
			return;
		} else if (method == InputMethod.INVENTORY) {
			InventoryRequestManager.openBooleanRequest(plugin, this, player, currentValue, promptText, listener);
			return;
		} else if (method == InputMethod.BOOK) {
			// Use book for boolean input
			BookInputManager.requestBookInput(player, currentValue != null ? currentValue.toString() : "", input -> {
				if (input == null) {
					return;
				}
				String trimmed = input.trim().toLowerCase();
				if (trimmed.equals("true") || trimmed.equals("yes") || trimmed.equals("y")) {
					listener.onInput(player, true);
				} else if (trimmed.equals("false") || trimmed.equals("no") || trimmed.equals("n")) {
					listener.onInput(player, false);
				} else {
					player.sendMessage("Invalid boolean value: " + input);
				}
			});
			return;
		} else if (method == InputMethod.DIALOG && dialogService != null) {
			String title = (promptText != null && !promptText.isEmpty()) ? promptText : "Select an option";
			String body = "";
			if (currentValue != null) {
				body = "Current value: " + currentValue;
			}
			MultiActionDialogBuilder builder = dialogService.multiAction(player).title(title).body(body);

			builder.button("True", payload -> listener.onInput(player, true));
			builder.button("False", payload -> listener.onInput(player, false));

			builder.button("Change Method",
					payload -> InventoryRequestManager.openMethodSelection(player, newMethod -> {
						PlayerInputManager.setInputMethod(player.getUniqueId(), newMethod);
						ValueRequest newRequest = new ValueRequest(plugin, dialogService, newMethod);
						newRequest.requestBoolean(player, currentValue, promptText, listener);
					}));
			builder.open();
			return;
		}

		// Chat fallback
		StringBuilder promptBuilder = new StringBuilder();
		if (promptText != null && !promptText.isEmpty()) {
			promptBuilder.append(promptText);
		} else {
			promptBuilder.append("Enter true or false");
		}
		if (currentValue != null) {
			promptBuilder.append(" (Current: ").append(currentValue).append(")");
		}

		ConversationFactory factory = new ConversationFactory(plugin).withModality(true).withTimeout(60)
				.withEscapeSequence("cancel");
		factory.withFirstPrompt(new StringPrompt() {
			@Override
			public String getPromptText(ConversationContext context) {
				return promptBuilder.toString();
			}

			@Override
			public Prompt acceptInput(ConversationContext context, String input) {
				String value = input != null ? input.trim() : "";
				if (value.equalsIgnoreCase("cancel")) {
					return Prompt.END_OF_CONVERSATION;
				}
				if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("y")) {
					listener.onInput(player, true);
				} else if (value.equalsIgnoreCase("false") || value.equalsIgnoreCase("no")
						|| value.equalsIgnoreCase("n")) {
					listener.onInput(player, false);
				} else {
					player.sendMessage("Invalid boolean value: " + value);
				}
				return Prompt.END_OF_CONVERSATION;
			}
		}).buildConversation((Conversable) player).begin();
	}

	// Convenience overloads

	/**
	 * Request a number with no predefined options. Players may provide any numeric
	 * value.
	 *
	 * @param player   the player to prompt
	 * @param listener the callback to invoke with the provided number
	 */
	public void requestNumber(Player player, NumberListener listener) {
		requestNumber(player, null, null, true, null, listener);
	}

	/**
	 * Request a boolean with no predefined current value.
	 *
	 * @param player   the player to prompt
	 * @param listener the callback to invoke with the provided boolean
	 */
	public void requestBoolean(Player player, BooleanListener listener) {
		requestBoolean(player, null, null, listener);
	}

	public void requestMultipleValues(Player player, List<MultiValueField> fields, MultiValueListener listener) {
		if (fields == null || fields.isEmpty()) {
			listener.onInput(player, new MultiValueResult());
			return;
		}

		InputMethod method = getEffectiveMethod(player);

		if (method == InputMethod.DIALOG && dialogService != null) {
			requestMultipleValuesDialog(player, fields, listener);
			return;
		}

		requestMultipleValuesSequential(player, fields, 0, new MultiValueResult(), listener);
	}

	private void requestMultipleValuesDialog(Player player, List<MultiValueField> fields, MultiValueListener listener) {
		MultiActionDialogBuilder builder = dialogService.multiAction(player).title("Enter values").body("");

		for (MultiValueField field : fields) {
			if (field == null || field.getId() == null || field.getId().isEmpty()) {
				continue;
			}

			switch (field.getType()) {
			case BOOLEAN:
				builder.input(field.getId(), inputBuilder -> {
					boolean initial = field.getCurrentBooleanValue() != null
							? field.getCurrentBooleanValue().booleanValue()
							: false;
					inputBuilder.checkbox().label(field.getLabel()).initial(initial);
				});
				break;
			case NUMBER:
				builder.input(field.getId(), inputBuilder -> {
					String initial = field.getCurrentNumberValue() != null ? field.getCurrentNumberValue().toString()
							: "";
					if (initial.isEmpty()) {
						inputBuilder.textInput().label(field.getLabel());
					} else {
						inputBuilder.textInput().label(field.getLabel()).initial(initial);
					}
				});
				break;
			case STRING:
			default:
				String initial = field.getCurrentStringValue() != null ? field.getCurrentStringValue() : "";
				builder.input(field.getId(), inputBuilder -> {
					if (initial.isEmpty()) {
						inputBuilder.textInput().label(field.getLabel());
					} else {
						inputBuilder.textInput().label(field.getLabel()).initial(initial);
					}
				});
				break;
			}
		}

		builder.button("Submit", payload -> {
			MultiValueResult result = new MultiValueResult();

			for (MultiValueField field : fields) {
				if (field == null || field.getId() == null || field.getId().isEmpty()) {
					continue;
				}

				switch (field.getType()) {
				case BOOLEAN:
					result.set(field.getId(), payload.booleanValue(field.getId()));
					break;
				case NUMBER:
					String numberText = payload.textValue(field.getId());
					if (numberText == null || numberText.trim().isEmpty()) {
						if (field.isRequired()) {
							player.sendMessage("Missing value for " + field.getLabel());
							return;
						}
						result.set(field.getId(), null);
					} else {
						try {
							result.set(field.getId(), Double.valueOf(numberText.trim()));
						} catch (NumberFormatException ex) {
							player.sendMessage("Invalid number for " + field.getLabel() + ": " + numberText);
							return;
						}
					}
					break;
				case STRING:
				default:
					String text = payload.textValue(field.getId());
					if ((text == null || text.trim().isEmpty()) && field.isRequired()) {
						player.sendMessage("Missing value for " + field.getLabel());
						return;
					}
					result.set(field.getId(), text != null ? text.trim() : "");
					break;
				}
			}

			listener.onInput(player, result);
		});

		builder.button("Change Method", payload -> InventoryRequestManager.openMethodSelection(player, newMethod -> {
			PlayerInputManager.setInputMethod(player.getUniqueId(), newMethod);
			ValueRequest newRequest = new ValueRequest(plugin, dialogService, newMethod);
			newRequest.requestMultipleValues(player, fields, listener);
		}));

		builder.open();
	}

	private void requestMultipleValuesSequential(Player player, List<MultiValueField> fields, int index,
			MultiValueResult result, MultiValueListener listener) {
		if (index >= fields.size()) {
			listener.onInput(player, result);
			return;
		}

		MultiValueField field = fields.get(index);
		if (field == null) {
			requestMultipleValuesSequential(player, fields, index + 1, result, listener);
			return;
		}

		switch (field.getType()) {
		case BOOLEAN:
			requestBoolean(player, field.getCurrentBooleanValue(), field.getLabel(), (p, value) -> {
				result.set(field.getId(), Boolean.valueOf(value));
				requestMultipleValuesSequential(player, fields, index + 1, result, listener);
			});
			break;
		case NUMBER:
			requestNumber(player, field.getCurrentNumberValue(), null, true, field.getLabel(), (p, value) -> {
				result.set(field.getId(), value);
				requestMultipleValuesSequential(player, fields, index + 1, result, listener);
			});
			break;
		case STRING:
		default:
			requestString(player, field.getCurrentStringValue(), null, true, field.getLabel(), (p, value) -> {
				result.set(field.getId(), value);
				requestMultipleValuesSequential(player, fields, index + 1, result, listener);
			});
			break;
		}
	}

	/**
	 * Request multiple string values from a player using the modern multi-value
	 * request system. This method internally converts prompts into
	 * {@link MultiValueField} objects and delegates to
	 * {@link #requestMultipleValues(Player, List, MultiValueListener)}.
	 *
	 * @param player the player to prompt
	 * @param prompts list of prompt labels
	 * @param currentValues optional default values
	 * @param listener callback containing ordered string results
	 */
	public void requestMultipleStrings(Player player, List<String> prompts, List<String> currentValues,
	        MultiStringListener listener) {

	    if (prompts == null || prompts.isEmpty()) {
	        listener.onInput(player, java.util.Collections.emptyList());
	        return;
	    }

	    List<MultiValueField> fields = new java.util.ArrayList<MultiValueField>();

	    for (int i = 0; i < prompts.size(); i++) {
	        String id = "value" + i;
	        String label = prompts.get(i);

	        MultiValueField field = new MultiValueField(id, label, MultiValueField.FieldType.STRING);

	        if (currentValues != null && i < currentValues.size()) {
	            field.stringValue(currentValues.get(i));
	        }

	        field.required(true);

	        fields.add(field);
	    }

	    requestMultipleValues(player, fields, (p, result) -> {
	        java.util.List<String> values = new java.util.ArrayList<String>();

	        for (int i = 0; i < prompts.size(); i++) {
	            String id = "value" + i;
	            String value = result.getString(id);
	            if (value == null) {
	                value = "";
	            }
	            values.add(value);
	        }

	        listener.onInput(p, values);
	    });
	}
}