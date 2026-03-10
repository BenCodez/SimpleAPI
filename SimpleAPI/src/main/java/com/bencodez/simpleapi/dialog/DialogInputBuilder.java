package com.bencodez.simpleapi.dialog;

import java.util.HashMap;

import org.bukkit.entity.Player;

public class DialogInputBuilder {

    private final UniDialogInput input = new UniDialogInput();
    private final Player player;
    private final HashMap<String,String> placeholders;

    public DialogInputBuilder(String id, Player player, HashMap<String,String> placeholders) {
        this.player = player;
        this.placeholders = placeholders;
        input.setId(id);
    }

    public DialogInputBuilder label(String label) {
        input.setLabel(DialogTextFormatter.format(player, label, placeholders));
        return this;
    }

    public DialogInputBuilder placeholder(String text) {
        input.setPlaceholder(DialogTextFormatter.format(player, text, placeholders));
        return this;
    }

    public DialogInputBuilder required(boolean required) {
        input.setRequired(required);
        return this;
    }

    public UniDialogInput build() {
        return input;
    }
}