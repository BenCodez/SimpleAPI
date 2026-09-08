package com.bencodez.simpleapi.core.config;

import com.bencodez.simpleapi.file.annotation.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import java.util.Objects;
import java.util.function.Function;

import com.bencodez.simpleapi.file.config.ConfigView;

import com.bencodez.simpleapi.time.ParsedDuration;

/**
 * Platform-neutral implementation of the existing configuration annotations.
 *
 * <p>This is a compatibility extraction, not a change to the annotation rules.
 * Field fallback values, empty-list handling, declared-field-only traversal,
 * annotation order and per-field exception isolation are intentionally retained.
 * In particular, the legacy zero-default long reflection behavior is unchanged.</p>
 */
public class AnnotationBinder {

    private final Function<ConfigView, ?> sectionValue;

    /** Creates a binder that assigns ConfigView values to section fields. */
    public AnnotationBinder() {
        this(view -> view);
    }

    /**
     * Creates a binder with a platform-specific section projection. Only the
     * compatibility adapter should unwrap a view to a native section. The
     * projection is invoked for present sections, never for an absent section.
     *
     * @param sectionValue projection used for ConfigDataConfigurationSection
     */
    public AnnotationBinder(Function<ConfigView, ?> sectionValue) {
        this.sectionValue = Objects.requireNonNull(sectionValue, "sectionValue");
    }

	@SuppressWarnings("unchecked")
	public void load(ConfigView config, Object classToLoad) {
		Class<?> clazz = classToLoad.getClass();

		for (Field field : clazz.getDeclaredFields()) {
			try {
				field.setAccessible(true);

				ConfigDataString stringAnnotation = field.getAnnotation(ConfigDataString.class);
				if (stringAnnotation != null) {

					String defaultValue = stringAnnotation.defaultValue();
					if (defaultValue.isEmpty()) {
						try {
							String v = (String) field.get(classToLoad);
							defaultValue = v;
						} catch (Exception e) {

						}
					}
					String value = "";
					if (!stringAnnotation.secondPath().isEmpty()) {
						value = config.getString(stringAnnotation.path(),
								config.getString(stringAnnotation.secondPath(), defaultValue));
					} else {
						value = config.getString(stringAnnotation.path(), defaultValue);
					}

					field.set(classToLoad, value);

				}

				ConfigDataBoolean booleanAnnotation = field.getAnnotation(ConfigDataBoolean.class);
				if (booleanAnnotation != null) {
					boolean defaultValue = booleanAnnotation.defaultValue();
					if (!defaultValue) {
						try {
							boolean v = field.getBoolean(classToLoad);
							defaultValue = v;
						} catch (Exception e) {

						}

					}

					boolean value = defaultValue;
					if (config.contains(booleanAnnotation.path())) {
						value = config.getBoolean(booleanAnnotation.path(), defaultValue);
					} else if (!booleanAnnotation.secondPath().isEmpty()
							&& config.contains(booleanAnnotation.secondPath())) {
						value = config.getBoolean(booleanAnnotation.secondPath(), defaultValue);

						if (booleanAnnotation.secondPathInvert()) {
							value = !value;
						}
					} else {
						value = config.getBoolean(booleanAnnotation.path(), defaultValue);
					}

					field.set(classToLoad, value);
				}

				ConfigDataInt intAnnotation = field.getAnnotation(ConfigDataInt.class);
				if (intAnnotation != null) {
					int defaultValue = intAnnotation.defaultValue();
					if (defaultValue == 0) {
						try {
							int v = field.getInt(classToLoad);
							defaultValue = v;
						} catch (Exception e) {

						}
					}
					int value = 0;
					if (!intAnnotation.secondPath().isEmpty()) {
						value = config.getInt(intAnnotation.path(),
								config.getInt(intAnnotation.secondPath(), defaultValue));
					} else {
						value = config.getInt(intAnnotation.path(), defaultValue);
					}

					field.set(classToLoad, value);
				}

				ConfigDataLong longAnnotation = field.getAnnotation(ConfigDataLong.class);
				if (longAnnotation != null) {
					long defaultValue = longAnnotation.defaultValue();
					if (defaultValue == 0) {
						try {
							int v = field.getInt(classToLoad);
							defaultValue = v;
						} catch (Exception e) {

						}
					}
					long value = 0;
					if (!longAnnotation.secondPath().isEmpty()) {
						value = config.getLong(longAnnotation.path(),
								config.getLong(longAnnotation.secondPath(), defaultValue));
					} else {
						value = config.getLong(longAnnotation.path(), defaultValue);
					}

					field.set(classToLoad, value);
				}

				ConfigDataDouble doubleAnnotation = field.getAnnotation(ConfigDataDouble.class);
				if (doubleAnnotation != null) {
					double defaultValue = doubleAnnotation.defaultValue();
					if (defaultValue == 0) {
						try {
							double v = field.getDouble(classToLoad);
							defaultValue = v;
						} catch (Exception e) {

						}
					}
					double value = 0;
					if (!doubleAnnotation.secondPath().isEmpty()) {
						value = config.getDouble(doubleAnnotation.path(),
								config.getDouble(doubleAnnotation.secondPath(), defaultValue));
					} else {
						value = config.getDouble(doubleAnnotation.path(), defaultValue);
					}

					field.set(classToLoad, value);
				}

				ConfigDataListString listAnnotation = field.getAnnotation(ConfigDataListString.class);
				if (listAnnotation != null) {
					ArrayList<String> defaultValue = new ArrayList<>();
					try {
						ArrayList<String> v = (ArrayList<String>) field.get(classToLoad);
						defaultValue = v;
					} catch (Exception e) {

					}

					List<String> list = config.getStringList(listAnnotation.path());

					if (list.isEmpty()) {
						list = config.getStringList(listAnnotation.secondPath());
					}

					ArrayList<String> list1 = new ArrayList<>(list);
					// use default value
					if (list.isEmpty()) {
						list1 = defaultValue;
					}

					field.set(classToLoad, list1);

					/*
					 * ArrayList<String> value = null; if (!listAnnotation.secondPath().isEmpty()) {
					 * value = (ArrayList<String>) config.getList(listAnnotation.path(),
					 * config.getList(listAnnotation.secondPath(), defaultValue)); } else { value =
					 * (ArrayList<String>) config.getList(listAnnotation.path(), defaultValue); }
					 *
					 * field.set(classToLoad, value);
					 */
				}

				ConfigDataListInt intListAnnotation = field.getAnnotation(ConfigDataListInt.class);
				if (intListAnnotation != null) {
					ArrayList<Integer> defaultValue = new ArrayList<>();
					try {
						ArrayList<Integer> v = (ArrayList<Integer>) field.get(classToLoad);
						defaultValue = v;
					} catch (Exception e) {

					}

					List<Integer> list = config.getIntegerList(intListAnnotation.path());

					if (list.isEmpty()) {
						list = config.getIntegerList(intListAnnotation.secondPath());
					}

					ArrayList<Integer> list1 = new ArrayList<>(list);
					// use default value
					if (list.isEmpty()) {
						list1 = defaultValue;
					}

					field.set(classToLoad, list1);

					/*
					 * ArrayList<String> value = null; if (!listAnnotation.secondPath().isEmpty()) {
					 * value = (ArrayList<String>) config.getList(listAnnotation.path(),
					 * config.getList(listAnnotation.secondPath(), defaultValue)); } else { value =
					 * (ArrayList<String>) config.getList(listAnnotation.path(), defaultValue); }
					 *
					 * field.set(classToLoad, value);
					 */
				}

				ConfigDataKeys setAnnotation = field.getAnnotation(ConfigDataKeys.class);
				if (setAnnotation != null) {
					Set<String> value = new HashSet<>();
					if (config.isConfigurationSection(setAnnotation.path())) {
						value = config.getConfigurationSection(setAnnotation.path()).getKeys(false);
					} else if (config.isConfigurationSection(setAnnotation.secondPath())
							&& setAnnotation.secondPath().length() > 0) {
						value = config.getConfigurationSection(setAnnotation.secondPath()).getKeys(false);
					}
					if (value != null) {
						field.set(classToLoad, value);
					}
				}

				ConfigDataConfigurationSection confAnnotation = field
						.getAnnotation(ConfigDataConfigurationSection.class);
				if (confAnnotation != null) {
					ConfigView value = null;
					if (config.isConfigurationSection(confAnnotation.path())) {
						value = config.getConfigurationSection(confAnnotation.path());
					} else if (config.isConfigurationSection(confAnnotation.secondPath())
							&& !confAnnotation.secondPath().isEmpty()) {
						value = config.getConfigurationSection(confAnnotation.secondPath());
					}

					field.set(classToLoad, value == null ? null : sectionValue.apply(value));
				}

				ConfigDataParsedDuration durationAnnotation = field.getAnnotation(ConfigDataParsedDuration.class);
				if (durationAnnotation != null) {

					String defaultValue = durationAnnotation.defaultValue();

					if (defaultValue.isEmpty()) {
						try {
							Object v = field.get(classToLoad);
							if (v != null) {
								defaultValue = v.toString();
							}
						} catch (Exception e) {

						}
					}

					String value = "";

					if (!durationAnnotation.secondPath().isEmpty()) {
						value = config.getString(durationAnnotation.path(),
								config.getString(durationAnnotation.secondPath(), defaultValue));
					} else {
						value = config.getString(durationAnnotation.path(), defaultValue);
					}

					try {
						// Assumes ParsedDuration has a constructor or static parse method
						Object parsedDuration = ParsedDuration.parse(value, durationAnnotation.defaultTimeUnit());
						field.set(classToLoad, parsedDuration);
					} catch (Exception e) {
						e.printStackTrace();
					}
				}

			} catch (Exception e) {
				e.printStackTrace();
			}
		}

	}

}
