package com.bencodez.simpleapi.file.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Inherited
@Target({ ElementType.METHOD, ElementType.FIELD })
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigDataBoolean {
	boolean defaultValue() default false;

	String[] options() default "";

	String path();

	String secondPath() default "";
}
