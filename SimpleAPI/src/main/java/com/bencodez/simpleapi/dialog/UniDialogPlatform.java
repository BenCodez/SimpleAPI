package com.bencodez.simpleapi.dialog;

import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.entity.Player;

import io.github.projectunified.unidialog.core.payload.DialogPayload;

/**
 * Common wrapper over UniDialog platform managers.
 */
public interface UniDialogPlatform {

    UniDialogBackendType getBackendType();

    void register();

    void unregister();

    boolean clearDialog(UUID uuid);

    void registerCustomAction(String namespace, String id, Consumer<DialogPayload> action);

    void unregisterCustomAction(String namespace, String id);

    void unregisterAllCustomActions();

    void showNotice(Player player, UniDialogNoticeRequest request);

    void showConfirmation(Player player, UniDialogConfirmationRequest request);

    void showMultiAction(Player player, UniDialogMultiActionRequest request);
}
