package com.bencodez.simpleapi.dialog;

import java.util.HashMap;
import java.util.function.Consumer;

import org.bukkit.entity.Player;

import io.github.projectunified.unidialog.core.payload.DialogPayload;

/**
 * Builder for confirmation dialogs.
 */
public class ConfirmationDialogBuilder {

	private final UniDialogService service;
	private final Player player;
	private final UniDialogConfirmationRequest request = new UniDialogConfirmationRequest();
	private final HashMap<String, String> placeholders = new HashMap<String, String>();

	public ConfirmationDialogBuilder(UniDialogService service, Player player) {
		this.service = service;
		this.player = player;
	}

	public ConfirmationDialogBuilder placeholder(String placeholder, String value) {
		placeholders.put(placeholder, value);
		return this;
	}

	public ConfirmationDialogBuilder placeholders(HashMap<String, String> placeholders) {
		if (placeholders != null) {
			this.placeholders.putAll(placeholders);
		}
		return this;
	}

	public ConfirmationDialogBuilder title(String title) {
		request.setTitle(DialogTextFormatter.format(player, title, placeholders));
		return this;
	}

	public ConfirmationDialogBuilder body(String body) {
		request.setBody(DialogTextFormatter.format(player, body, placeholders));
		return this;
	}

	public ConfirmationDialogBuilder yesText(String text) {
		request.setYesText(DialogTextFormatter.format(player, text, placeholders));
		return this;
	}

	public ConfirmationDialogBuilder noText(String text) {
		request.setNoText(DialogTextFormatter.format(player, text, placeholders));
		return this;
	}


	public ConfirmationDialogBuilder input(String id, Consumer<DialogInputBuilder> builderConsumer) {
		DialogInputBuilder builder = new DialogInputBuilder(id, player, placeholders);
		builderConsumer.accept(builder);
		request.addInput(builder.build());
		return this;
	}

	public ConfirmationDialogBuilder namespace(String namespace) {
		request.setNamespace(namespace);
		return this;
	}

	public ConfirmationDialogBuilder yesActionId(String actionId) {
		request.setYesActionId(actionId);
		return this;
	}

	public ConfirmationDialogBuilder noActionId(String actionId) {
		request.setNoActionId(actionId);
		return this;
	}

	public ConfirmationDialogBuilder onYes(Consumer<DialogPayload> callback) {
		request.setYesCallback(callback);
		return this;
	}

	public ConfirmationDialogBuilder onNo(Consumer<DialogPayload> callback) {
		request.setNoCallback(callback);
		return this;
	}

	public void open() {
		service.showConfirmation(player, request);
	}
}