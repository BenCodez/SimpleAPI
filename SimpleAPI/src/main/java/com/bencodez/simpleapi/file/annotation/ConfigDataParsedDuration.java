package com.bencodez.simpleapi.file.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * Annotation for loading ParsedDuration values from config.
 * Supports string-based durations such as "5m", "1h", "30s".
 */
@Inherited
@Target({ ElementType.METHOD, ElementType.FIELD })
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigDataParsedDuration {

	/**
	 * Default value as a string (ex: "5m").
	 *
	 * @return default duration string
	 */
	String defaultValue() default "";

	/**
	 * Config path.
	 *
	 * @return path
	 */
	String path();

	/**
	 * Optional fallback path.
	 *
	 * @return second path
	 */
	String secondPath() default "";
	
	TimeUnit defaultTimeUnit() default TimeUnit.SECONDS;
}