package com.bencodez.simpleapi.tests.support;

import java.lang.reflect.*;
import java.util.*;

/** Test-only access to the original implementation without expanding production visibility. */
public final class TestReflection {
    private TestReflection() { }
    public interface Handle { Object target(); }
    public static Class<?> type(String name) {
        try { return Class.forName(name); }
        catch (ClassNotFoundException failure) { throw new AssertionError(failure); }
    }
    public static Object unwrap(Object value) {
        if (value instanceof Handle handle) return handle.target();
        if (value instanceof Collection<?> values) return values.stream().map(TestReflection::unwrap).toList();
        return value;
    }
    public static Object field(Class<?> type, Object target, String name) {
        try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(unwrap(target)); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    @SuppressWarnings("unchecked")
    public static <T> T call(Object target, String name, Object... arguments) {
        Class<?> type = target instanceof Class<?> cls ? cls : unwrap(target).getClass();
        Object receiver = target instanceof Class<?> ? null : unwrap(target);
        Object[] values = Arrays.stream(arguments).map(TestReflection::unwrap).toArray();
        List<Method> matches = Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals(name) && matches(method.getParameterTypes(), values)).toList();
        if (matches.size() != 1) throw new AssertionError("Expected one method: " + type + "." + name + ": " + matches);
        try { Method method = matches.get(0); method.setAccessible(true); return (T) method.invoke(receiver, values); }
        catch (InvocationTargetException failure) { return TestReflection.<RuntimeException, T>rethrow(failure.getCause()); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    @SuppressWarnings("unchecked")
    public static <T> T construct(Class<?> type, Object... arguments) {
        Object[] values = Arrays.stream(arguments).map(TestReflection::unwrap).toArray();
        List<Constructor<?>> matches = Arrays.stream(type.getDeclaredConstructors())
                .filter(constructor -> matches(constructor.getParameterTypes(), values)).toList();
        if (matches.size() != 1) throw new AssertionError("Expected one constructor: " + type + ": " + matches);
        try { Constructor<?> constructor = matches.get(0); constructor.setAccessible(true); return (T) constructor.newInstance(values); }
        catch (InvocationTargetException failure) { return TestReflection.<RuntimeException, T>rethrow(failure.getCause()); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static boolean matches(Class<?>[] parameters, Object[] values) {
        if (parameters.length != values.length) return false;
        for (int i = 0; i < parameters.length; i++) {
            Class<?> type = parameters[i];
            if (type == int.class) type = Integer.class;
            else if (type == long.class) type = Long.class;
            else if (type == boolean.class) type = Boolean.class;
            if (values[i] == null ? parameters[i].isPrimitive() : !type.isInstance(values[i])) return false;
        }
        return true;
    }
    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T rethrow(Throwable failure) throws E { throw (E) failure; }
}
