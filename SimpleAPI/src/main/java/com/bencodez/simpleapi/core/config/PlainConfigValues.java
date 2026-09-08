package com.bencodez.simpleapi.core.config;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Bounded copying of configuration data; no platform object serialization. */
public final class PlainConfigValues {
    private static final int MAX_DEPTH = 64;
    private static final int MAX_NODES = 100_000;

    private PlainConfigValues() { }

    public static StructuredConfigView.Kind kind(Object value) {
        if (value == null) return StructuredConfigView.Kind.MISSING;
        if (value instanceof String) return StructuredConfigView.Kind.STRING;
        if (value instanceof Boolean) return StructuredConfigView.Kind.BOOLEAN;
        if (value instanceof Number) return StructuredConfigView.Kind.NUMBER;
        if (value instanceof List<?>) return StructuredConfigView.Kind.LIST;
        if (value instanceof Map<?, ?>) return StructuredConfigView.Kind.MAP;
        return StructuredConfigView.Kind.OTHER;
    }

    public static Object copy(Object value) {
        return copy(value, Function.identity());
    }

    /**
     * The adapter unwraps one section/node at a time into a shallow map/list or
     * scalar. Its callback must not recursively serialize the entire input.
     * Bounds and cycle detection apply before each unwrap and recursive read.
     */
    public static Object copy(Object value, Function<Object, Object> unwrap) {
        return copy(value, Objects.requireNonNull(unwrap, "unwrap"), 0, new int[1],
                new IdentityHashMap<>(), "$");
    }

    private static Object copy(Object source, Function<Object, Object> unwrap, int depth,
            int[] count, IdentityHashMap<Object, Boolean> ancestors, String path) {
        if (depth > MAX_DEPTH || ++count[0] > MAX_NODES) {
            throw new IllegalArgumentException("Configuration structure exceeds limits at " + path);
        }
        if (source == null) return null;
        if (ancestors.put(source, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("Cyclic configuration structure at " + path);
        }
        try {
            Object value = unwrap.apply(source);
            if (value == null || scalar(value)) return value;
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object rawKey = entry.getKey();
                    if (rawKey == null || !scalar(rawKey)) {
                        throw new IllegalArgumentException("Unsupported configuration key at " + path);
                    }
                    String key = rawKey.toString();
                    if (result.containsKey(key)) {
                        throw new IllegalArgumentException("Ambiguous configuration key at " + path + "." + key);
                    }
                    result.put(key, copy(entry.getValue(), unwrap, depth + 1, count, ancestors, path + "." + key));
                }
                return Collections.unmodifiableMap(result);
            }
            if (value instanceof List<?> list) {
                List<Object> result = new ArrayList<>();
                for (Object item : list) {
                    result.add(copy(item, unwrap, depth + 1, count, ancestors, path + "[" + result.size() + "]"));
                }
                return Collections.unmodifiableList(result);
            }
            throw new IllegalArgumentException("Unsupported configuration value at " + path + ": "
                    + value.getClass().getName());
        } finally {
            ancestors.remove(source);
        }
    }

    private static boolean scalar(Object value) {
        return value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double
                || value instanceof BigInteger || value instanceof BigDecimal;
    }
}
