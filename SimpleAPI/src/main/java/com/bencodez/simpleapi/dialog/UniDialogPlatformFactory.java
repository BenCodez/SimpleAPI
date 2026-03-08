package com.bencodez.simpleapi.dialog;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Factory for selecting the UniDialog platform.
 */
public final class UniDialogPlatformFactory {

    private UniDialogPlatformFactory() {
    }

    public static UniDialogPlatform create(Plugin plugin, String namespace) {
        if (isPaper()) {
            return new PaperUniDialogPlatform(plugin, namespace);
        }
        return new SpigotUniDialogPlatform(plugin, namespace);
    }

    public static boolean isPaper() {
        try {
            Class.forName("io.papermc.paper.event.player.PlayerCustomClickEvent");
            return true;
        } catch (ClassNotFoundException ignored) {
            return Bukkit.getName().toLowerCase().contains("paper");
        }
    }
}
