package com.bencodez.simpleapi.tests.file.config;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

import com.bencodez.simpleapi.file.annotation.AnnotationBinder;

class AnnotationBinderHeadlessTest {

    @Test
    void bindsUsingOnlyProjectClassesAndTheJdk() throws Exception {
        URL mainClasses = AnnotationBinder.class.getProtectionDomain().getCodeSource().getLocation();
        URL testClasses = getClass().getProtectionDomain().getCodeSource().getLocation();
        // The platform parent supplies only JDK classes, not Maven's dependency classpath.
        try (URLClassLoader isolated = new URLClassLoader(new URL[] { mainClasses, testClasses },
                ClassLoader.getPlatformClassLoader())) {
            assertThrows(ClassNotFoundException.class,
                    () -> isolated.loadClass("org.bukkit.configuration.ConfigurationSection"));
            assertThrows(ClassNotFoundException.class,
                    () -> isolated.loadClass("org.junit.jupiter.api.Test"));
            Class<?> fixture = isolated.loadClass(HeadlessBindingFixture.class.getName());
            assertSame(isolated, fixture.getClassLoader());
            fixture.getMethod("run").invoke(null);
        }
    }
}
