package com.bencodez.simpleapi.valuerequest;

/**
 * Represents the different input mechanisms available when requesting a value
 * from a player. DIALOG is preferred on modern servers with UniDialog
 * support. CHAT falls back to simple chat prompts when no dialog platform
 * is available or when running on older versions.
 */
public enum InputMethod {
    /**
     * Use a UniDialog multi action dialog. This provides a GUI with buttons
     * and inputs where available.
     */
    DIALOG,
    /**
     * Use a chat based conversation. Players type their response into chat.
     */
    CHAT,
    /**
     * Use an in-game inventory interface for selecting options. When using this
     * method, options are presented as items in a chest-style inventory and a
     * player may click to select a value. If custom input is allowed a special
     * item will appear that directs the player to the chat input fallback.
     */
    INVENTORY,
    /**
     * Use a writable book and quill for input. When this method is selected the
     * player receives a book in their inventory and must write their response
     * inside. Once the book is signed the input is captured and processed.
     */
    BOOK,

    /**
     * Use a sign editing interface for input. When selected the player will be
     * shown a sign where they can type text across multiple lines. Upon
     * confirmation the text from the sign will be passed to the value request
     * handler. This method is useful on some server versions that support
     * {@link org.bukkit.entity.Player#openSign(org.bukkit.block.Sign)}. It
     * behaves similarly to {@link #BOOK} but uses a sign instead of a
     * writable book.
     */
    SIGN;

    /**
     * Attempt to parse an input method from a string. If the value is not
     * recognised this method returns {@link #DIALOG} by default.
     *
     * @param method the method name to parse
     * @return the resolved input method
     */
    public static InputMethod getMethod(String method) {
        if (method != null) {
            for (InputMethod input : values()) {
                if (input.toString().equalsIgnoreCase(method)) {
                    return input;
                }
            }
        }
        return DIALOG;
    }
}