package com.bencodez.simpleapi.valuerequest;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility class to manage per-player input method preferences. When a player
 * chooses to change their input method this manager stores the preference so
 * subsequent value requests use the new method. If no preference is set the
 * default method will be {@link InputMethod#DIALOG}.
 */
public final class PlayerInputManager {

    /**
     * Internal map of player unique identifiers to their selected input method.
     */
    private static final Map<UUID, InputMethod> preferences = new ConcurrentHashMap<>();

    private PlayerInputManager() {
        // static utility class
    }

    /**
     * Retrieve the stored input method for a player. If none has been set this
     * returns {@link InputMethod#DIALOG}.
     *
     * @param uuid the player's unique identifier
     * @return the player's preferred input method or {@link InputMethod#DIALOG}
     */
    public static InputMethod getInputMethod(UUID uuid) {
        InputMethod method = preferences.get(uuid);
        if (method == null) {
            return InputMethod.DIALOG;
        }
        return method;
    }

    /**
     * Set the input method preference for a player. Passing {@code null} will
     * remove the player's preference and revert to the default method.
     *
     * @param uuid   the player's unique identifier
     * @param method the new input method or null
     */
    public static void setInputMethod(UUID uuid, InputMethod method) {
        if (method == null) {
            preferences.remove(uuid);
        } else {
            preferences.put(uuid, method);
        }
    }

    /**
     * Cycle the player's current input method to the next available method. This
     * rotates through the defined {@link InputMethod} values in their ordinal
     * order. The new method is stored and returned.
     *
     * @param uuid the player's unique identifier
     * @return the newly selected input method
     */
    public static InputMethod cycleInputMethod(UUID uuid) {
        InputMethod current = getInputMethod(uuid);
        InputMethod[] values = InputMethod.values();
        int index = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                index = i;
                break;
            }
        }
        int nextIndex = (index + 1) % values.length;
        InputMethod nextMethod = values[nextIndex];
        setInputMethod(uuid, nextMethod);
        return nextMethod;
    }
}