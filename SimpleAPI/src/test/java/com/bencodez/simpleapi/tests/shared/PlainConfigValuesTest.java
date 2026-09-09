package com.bencodez.simpleapi.tests.shared;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.bencodez.simpleapi.core.config.PlainConfigValues;
import com.bencodez.simpleapi.core.config.StructuredConfigView;

class PlainConfigValuesTest {
    @Test void copiesNestedPlainValuesAndNullListEntries() {
        var values = new ArrayList<>(Arrays.asList("text", 1, 2L, false, null, Map.of("value", 3.5)));
        Object copied = PlainConfigValues.copy(Map.of("values", values));
        values.clear();
        assertEquals(Map.of("values", Arrays.asList("text", 1, 2L, false, null, Map.of("value", 3.5))), copied);
    }

    @Test void rejectsCyclesButPermitsSharedSubtrees() {
        var cyclic = new LinkedHashMap<String, Object>(); cyclic.put("self", cyclic);
        assertThrows(IllegalArgumentException.class, () -> PlainConfigValues.copy(cyclic));
        var shared = Map.of("command", "say voted");
        assertEquals(List.of(shared, shared), PlainConfigValues.copy(List.of(shared, shared)));
    }

    @Test void limitsDepthAndNodeCount() {
        Object deep = "leaf";
        for (int i = 0; i < 70; i++) deep = Map.of("child", deep);
        Object finalDeep = deep;
        assertThrows(IllegalArgumentException.class, () -> PlainConfigValues.copy(finalDeep));
        assertThrows(IllegalArgumentException.class, () -> PlainConfigValues.copy(Collections.nCopies(100_001, 1)));
    }

    @Test void rejectsAmbiguousKeysAndMutableNumberObjects() {
        var keys = new LinkedHashMap<Object, Object>(); keys.put(1, "one"); keys.put("1", "other");
        assertThrows(IllegalArgumentException.class, () -> PlainConfigValues.copy(keys));
        assertThrows(IllegalArgumentException.class, () -> PlainConfigValues.copy(new AtomicInteger(1)));
        assertEquals(StructuredConfigView.Kind.OTHER, PlainConfigValues.kind(new AtomicInteger(1)));
        assertThrows(IllegalArgumentException.class, () -> PlainConfigValues.copy(Map.of(new Object(), 1)));
    }

    @Test void unwrapsAdapterNodesUnderTheSameCycleAndDepthChecks() {
        var root = new Node(); root.value = Map.of("nested", new Node(List.of(1, 2)));
        assertEquals(Map.of("nested", List.of(1, 2)), PlainConfigValues.copy(root, PlainConfigValuesTest::unwrap));
        root.value = Map.of("cycle", root);
        assertThrows(IllegalArgumentException.class, () -> PlainConfigValues.copy(root, PlainConfigValuesTest::unwrap));
    }

    private static Object unwrap(Object value) { return value instanceof Node node ? node.value : value; }
    private static final class Node {
        private Object value;
        private Node() { }
        private Node(Object value) { this.value = value; }
    }
}
