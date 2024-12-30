package com.bencodez.simpleapi.file.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Inherited
@Target({ ElementType.METHOD, ElementType.FIELD })
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigDataDouble {
	double defaultValue() default 0;

	String[] options() default "";

	String path();

	String[] possibleValues() default "";

	String secondPath() default "";
}
