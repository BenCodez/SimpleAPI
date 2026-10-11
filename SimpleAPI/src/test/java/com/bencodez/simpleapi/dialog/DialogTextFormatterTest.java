package com.bencodez.simpleapi.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashMap;

import org.junit.jupiter.api.Test;

class DialogTextFormatterTest {

    @Test
    void stripsInertMarkersAfterFormattingSiteNames() {
        HashMap<String, String> values = new HashMap<>();
        values.put("site", "\u2060Minecraft Menu\u2060");

        String result = DialogTextFormatter.format(null, "&7Site: &e%site%", values);

        assertEquals("\u00A77Site: \u00A7eMinecraft Menu", result);
        assertFalse(result.contains("\u2060"));
    }

    @Test
    void boundariesStillBlockColorsFormedAcrossPlaceholderEdges() {
        HashMap<String, String> values = new HashMap<>();
        values.put("site", "\u2060a\u2060");

        // Without the guards, a trailing & in the trusted template would
        // become a Minecraft color code before the message is sent.
        assertEquals("&a", DialogTextFormatter.format(null, "&%site%", values));
        assertEquals("\u00A7a", DialogTextFormatter.format(null, "&%site%",
                new HashMap<>(java.util.Map.of("site", "a"))));
    }

    @Test
    void guardedNestedPlaceholderRemainsLiteralText() {
        HashMap<String, String> values = new HashMap<>();
        values.put("site", "\u2060%\u2060player_name%\u2060");
        values.put("player_name", "NotTheSite");

        assertEquals("%player_name%", DialogTextFormatter.format(null, "%site%", values));
    }
}
