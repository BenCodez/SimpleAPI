package com.bencodez.simpleapi.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.bencodez.simpleapi.file.annotation.ConfigDataBoolean;
import com.bencodez.simpleapi.file.annotation.ConfigDataConfigurationSection;
import com.bencodez.simpleapi.file.annotation.ConfigDataDouble;
import com.bencodez.simpleapi.file.annotation.ConfigDataInt;
import com.bencodez.simpleapi.file.annotation.ConfigDataListString;
import com.bencodez.simpleapi.file.annotation.ConfigDataString;

public class AnnotationTest {

	@Test
	public void testConfigDataBoolean() throws NoSuchFieldException, IllegalAccessException {
		Field field = SampleConfig.class.getDeclaredField("featureEnabled");
		ConfigDataBoolean annotation = field.getAnnotation(ConfigDataBoolean.class);
		assertNotNull(annotation);
		assertEquals("feature.enabled", annotation.path());
		assertTrue(annotation.defaultValue());

		// Process annotation
		SampleConfig config = new SampleConfig();
		field.setAccessible(true);
		field.set(config, annotation.defaultValue());

		// Get current value
		boolean currentValue = (boolean) field.get(config);
		assertTrue(currentValue);
	}

	@Test
	public void testConfigDataConfigurationSection() throws NoSuchFieldException, IllegalAccessException {
		Field field = SampleConfig.class.getDeclaredField("databaseSettings");
		ConfigDataConfigurationSection annotation = field.getAnnotation(ConfigDataConfigurationSection.class);
		assertNotNull(annotation);
		assertEquals("database.settings", annotation.path());

		// Process annotation
		SampleConfig config = new SampleConfig();
		field.setAccessible(true);
		field.set(config, new Object()); // Assuming default value is a new object

		// Get current value
		Object currentValue = field.get(config);
		assertNotNull(currentValue);
	}

	@Test
	public void testConfigDataDouble() throws NoSuchFieldException, IllegalAccessException {
		Field field = SampleConfig.class.getDeclaredField("thresholdLimit");
		ConfigDataDouble annotation = field.getAnnotation(ConfigDataDouble.class);
		assertNotNull(annotation);
		assertEquals("threshold.limit", annotation.path());
		assertEquals(0.75, annotation.defaultValue(), 0.01);

		// Process annotation
		SampleConfig config = new SampleConfig();
		field.setAccessible(true);
		field.set(config, annotation.defaultValue());

		// Get current value
		double currentValue = (double) field.get(config);
		assertEquals(0.75, currentValue, 0.01);
	}

	@Test
	public void testConfigDataInteger() throws NoSuchFieldException, IllegalAccessException {
		Field field = SampleConfig.class.getDeclaredField("maxConnections");
		ConfigDataInt annotation = field.getAnnotation(ConfigDataInt.class);
		assertNotNull(annotation);
		assertEquals("max.connections", annotation.path());
		assertEquals(10, annotation.defaultValue());

		// Process annotation
		SampleConfig config = new SampleConfig();
		field.setAccessible(true);
		field.set(config, annotation.defaultValue());

		// Get current value
		int currentValue = (int) field.get(config);
		assertEquals(10, currentValue);
	}

	@Test
	public void testConfigDataList() throws NoSuchFieldException, IllegalAccessException {
		Field field = SampleConfig.class.getDeclaredField("userRoles");
		ConfigDataListString annotation = field.getAnnotation(ConfigDataListString.class);
		assertNotNull(annotation);
		assertEquals("user.roles", annotation.path());

		// Process annotation
		SampleConfig config = new SampleConfig();
		field.setAccessible(true);
		field.set(config, Arrays.asList("default")); // Assuming default value is a list with "default"

		// Get current value
		@SuppressWarnings("unchecked")
		List<String> currentValue = (List<String>) field.get(config);
		assertNotNull(currentValue);
	}

	@Test
	public void testConfigDataString() throws NoSuchFieldException, IllegalAccessException {
		Field field = SampleConfig.class.getDeclaredField("welcomeMessage");
		ConfigDataString annotation = field.getAnnotation(ConfigDataString.class);
		assertNotNull(annotation);
		assertEquals("welcome.message", annotation.path());
		assertEquals("Welcome!", annotation.defaultValue());

		// Process annotation
		SampleConfig config = new SampleConfig();
		field.setAccessible(true);
		field.set(config, annotation.defaultValue());

		// Get current value
		String currentValue = (String) field.get(config);
		assertEquals("Welcome!", currentValue);
	}

}
