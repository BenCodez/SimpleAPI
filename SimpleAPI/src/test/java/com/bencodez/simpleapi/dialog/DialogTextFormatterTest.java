package com.bencodez.simpleapi.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;

import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

class DialogTextFormatterTest {

    @Test
    void removesMarkersOnlyAfterSpigotAndPaperComponentParsing() {
        HashMap<String, String> values = new HashMap<>();
        values.put("site", "\u2060Minecraft Menu\u2060");

        String formatted = DialogTextFormatter.format(null, "&7Site: &e%site%", values);
        assertTrue(formatted.contains("\u2060"), "Keep guards through UniDialog's final legacy parser");
        assertEquals("Site: Minecraft Menu", SpigotUniDialogPlatform.renderedText(formatted).toPlainText());
        assertEquals("Site: Minecraft Menu", PlainTextComponentSerializer.plainText()
                .serialize(PaperUniDialogPlatform.renderedText(formatted)));
    }

    @Test
    void boundariesProtectBothAmpersandAndSectionSignCodesAcrossSubstitution() {
        HashMap<String, String> values = new HashMap<>();
        values.put("site", "\u2060a\u2060");

        // Color code can be formed only if the boundaries are dropped BEFORE
        // either parser runs. Verify the finished components still show literal
        // text in both legacy serializers, including when a template has §.
        String ampersand = DialogTextFormatter.format(null, "&%site%", values);
        assertEquals("&a", SpigotUniDialogPlatform.renderedText(ampersand).toPlainText());
        assertEquals("&a", PlainTextComponentSerializer.plainText()
                .serialize(PaperUniDialogPlatform.renderedText(ampersand)));

        String section = DialogTextFormatter.format(null, "\u00A7%site%", values);
        // A legacy parser may drop the isolated invalid § character, but
        // the site name must remain text, not turn into a green color token.
        var spigot = SpigotUniDialogPlatform.renderedText(section);
        var paper = PaperUniDialogPlatform.renderedText(section);
        assertTrue(spigot.toPlainText().endsWith("a"));
        assertTrue(PlainTextComponentSerializer.plainText().serialize(paper).endsWith("a"));
        assertFalse(spigot.toLegacyText().contains("\u00A7a"));
        // Re-serializing a literal § followed by 'a' to a legacy String can
        // produce an ambiguous §a sequence; check the actual component style.
        org.junit.jupiter.api.Assertions.assertNotEquals(
                net.kyori.adventure.text.format.NamedTextColor.GREEN, paper.color());
    }

    @Test
    void nestedPlaceholderNeverResolvesFromGuardedSiteName() {
        HashMap<String, String> values = new HashMap<>();
        values.put("site", "\u2060%\u2060player_name%\u2060");
        values.put("player_name", "NotTheSite");

        String formatted = DialogTextFormatter.format(null, "%site%", values);
        assertFalse(formatted.contains("NotTheSite"));
        assertEquals("%player_name%", SpigotUniDialogPlatform.renderedText(formatted).toPlainText());
        assertEquals("%player_name%", PlainTextComponentSerializer.plainText()
                .serialize(PaperUniDialogPlatform.renderedText(formatted)));
    }
}
