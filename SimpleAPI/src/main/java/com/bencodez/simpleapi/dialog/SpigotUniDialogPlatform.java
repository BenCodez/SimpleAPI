package com.bencodez.simpleapi.dialog;

import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import io.github.projectunified.unidialog.bungeecord.dialog.BungeeConfirmationDialog;
import io.github.projectunified.unidialog.bungeecord.dialog.BungeeMultiActionDialog;
import io.github.projectunified.unidialog.bungeecord.dialog.BungeeNoticeDialog;
import io.github.projectunified.unidialog.core.payload.DialogPayload;
import io.github.projectunified.unidialog.spigot.SpigotDialogManager;
import io.github.projectunified.unidialog.spigot.opener.SpigotDialogOpener;

/**
 * Spigot implementation using UniDialog's Spigot manager.
 */
public class SpigotUniDialogPlatform extends AbstractUniDialogPlatform {

	private final SpigotDialogManager manager;

	public SpigotUniDialogPlatform(Plugin plugin, String namespace) {
		super(plugin, namespace);
		this.manager = new SpigotDialogManager(plugin, namespace);
	}

	@Override
	public UniDialogBackendType getBackendType() {
		return UniDialogBackendType.SPIGOT;
	}

	@Override
	public void register() {
		manager.register();
	}

	@Override
	public void unregister() {
		manager.unregister();
	}

	@Override
	public boolean clearDialog(UUID uuid) {
		return manager.clearDialog(uuid);
	}

	@Override
	public void registerCustomAction(String namespace, String id, Consumer<DialogPayload> action) {
		manager.registerCustomAction(namespace, id, action);
	}

	@Override
	public void unregisterCustomAction(String namespace, String id) {
		manager.unregisterCustomAction(namespace, id);
	}

	@Override
	public void unregisterAllCustomActions() {
		manager.unregisterAllCustomActions();
	}

	@Override
	public void showNotice(Player player, UniDialogNoticeRequest request) {
		String namespace = resolveNamespace(request.getNamespace());
		String actionId = resolveActionId(request.getActionId());

		if (request.getCallback() != null) {
			registerCustomAction(namespace, actionId, request.getCallback());
		}

		BungeeNoticeDialog<SpigotDialogOpener> dialog = manager.createNoticeDialog().title(request.getTitle())
				.body(builder -> builder.text().text(request.getBody()));

		applyInputs(dialog, request.getInputs());

		dialog.action(action -> {
			action.label(request.getButtonText());
			action.dynamicCustom(namespace, actionId);
		});

		dialog.opener().open(player.getUniqueId());
	}

	@Override
	public void showConfirmation(Player player, UniDialogConfirmationRequest request) {
		String namespace = resolveNamespace(request.getNamespace());
		String yesActionId = resolveActionId(request.getYesActionId());
		String noActionId = resolveActionId(request.getNoActionId());

		if (request.getYesCallback() != null) {
			registerCustomAction(namespace, yesActionId, request.getYesCallback());
		}

		if (request.getNoCallback() != null) {
			registerCustomAction(namespace, noActionId, request.getNoCallback());
		}

		BungeeConfirmationDialog<SpigotDialogOpener> dialog = manager.createConfirmationDialog()
				.title(request.getTitle()).body(builder -> builder.text().text(request.getBody()));

		applyInputs(dialog, request.getInputs());

		dialog.yesAction(action -> {
			action.label(request.getYesText());
			action.dynamicCustom(namespace, yesActionId);
		});

		dialog.noAction(action -> {
			action.label(request.getNoText());
			action.dynamicCustom(namespace, noActionId);
		});

		dialog.opener().open(player.getUniqueId());
	}

	@Override
	public void showMultiAction(Player player, UniDialogMultiActionRequest request) {
		String namespace = resolveNamespace(request.getNamespace());

		BungeeMultiActionDialog<SpigotDialogOpener> dialog = manager.createMultiActionDialog().title(request.getTitle())
				.body(builder -> builder.text().text(request.getBody())).columns(request.getColumns());

		applyInputs(dialog, request.getInputs());

		for (UniDialogButton button : request.getButtons()) {
			String actionId = resolveActionId(button.getActionId());

			if (button.getCallback() != null) {
				registerCustomAction(namespace, actionId, button.getCallback());
			}

			dialog.action(action -> {
				action.label(button.getText());

				if (button.getTooltip() != null && !button.getTooltip().isEmpty()) {
					action.tooltip(button.getTooltip());
				}

				Integer width = button.getWidth();
				if (width == null) {
					width = request.getButtonWidth();
				}

				if (width != null) {
					action.width(width.intValue());
				}

				action.dynamicCustom(namespace, actionId);
			});
		}

		dialog.opener().open(player.getUniqueId());
	}

	private void applyInputs(BungeeNoticeDialog<SpigotDialogOpener> dialog, Iterable<UniDialogInput> inputs) {
		if (inputs == null) {
			return;
		}

		for (UniDialogInput input : inputs) {
			applyInput(dialog, input);
		}
	}

	private void applyInputs(BungeeConfirmationDialog<SpigotDialogOpener> dialog, Iterable<UniDialogInput> inputs) {
		if (inputs == null) {
			return;
		}

		for (UniDialogInput input : inputs) {
			applyInput(dialog, input);
		}
	}

	private void applyInputs(BungeeMultiActionDialog<SpigotDialogOpener> dialog, Iterable<UniDialogInput> inputs) {
		if (inputs == null) {
			return;
		}

		for (UniDialogInput input : inputs) {
			applyInput(dialog, input);
		}
	}

	private void applyInput(BungeeNoticeDialog<SpigotDialogOpener> dialog, UniDialogInput input) {
		if (input == null || input.getId() == null || input.getId().isEmpty()) {
			return;
		}

		dialog.input(input.getId(), builder -> {
			if (input.getType() == UniDialogInput.InputType.BOOLEAN) {
				builder.booleanInput().label(getInputLabel(input)).initial(input.isInitialBoolean());
			} else {
				String initial = getInputInitial(input);
				if (initial != null && !initial.isEmpty()) {
					builder.textInput().label(getInputLabel(input)).initial(initial);
				} else {
					builder.textInput().label(getInputLabel(input));
				}
			}
		});
	}

	private void applyInput(BungeeConfirmationDialog<SpigotDialogOpener> dialog, UniDialogInput input) {
		if (input == null || input.getId() == null || input.getId().isEmpty()) {
			return;
		}

		dialog.input(input.getId(), builder -> {
			if (input.getType() == UniDialogInput.InputType.BOOLEAN) {
				builder.booleanInput().label(getInputLabel(input)).initial(input.isInitialBoolean());
			} else {
				String initial = getInputInitial(input);
				if (initial != null && !initial.isEmpty()) {
					builder.textInput().label(getInputLabel(input)).initial(initial);
				} else {
					builder.textInput().label(getInputLabel(input));
				}
			}
		});
	}

	private void applyInput(BungeeMultiActionDialog<SpigotDialogOpener> dialog, UniDialogInput input) {
		if (input == null || input.getId() == null || input.getId().isEmpty()) {
			return;
		}

		dialog.input(input.getId(), builder -> {
			if (input.getType() == UniDialogInput.InputType.BOOLEAN) {
				builder.booleanInput().label(getInputLabel(input)).initial(input.isInitialBoolean());
			} else {
				String initial = getInputInitial(input);
				if (initial != null && !initial.isEmpty()) {
					builder.textInput().label(getInputLabel(input)).initial(initial);
				} else {
					builder.textInput().label(getInputLabel(input));
				}
			}
		});
	}

	private String getInputLabel(UniDialogInput input) {
		String label = input.getLabel();

		if (label == null || label.isEmpty()) {
			label = input.getId();
		}

		return label;
	}

	private String getInputInitial(UniDialogInput input) {
		return input.getInitialValue();
	}
}
