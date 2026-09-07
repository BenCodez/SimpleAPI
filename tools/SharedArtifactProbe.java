import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.jar.JarFile;

/** Link every published SimpleAPI class using only the selected runtime JARs. */
public final class SharedArtifactProbe {
    public static void main(String[] arguments) throws Exception {
        var urls=new ArrayList<java.net.URL>();
        for (String argument:arguments) urls.add(Path.of(argument).toUri().toURL());
        int checked=0;
        try (var loader=new URLClassLoader(urls.toArray(java.net.URL[]::new), ClassLoader.getPlatformClassLoader())) {
            for (String forbidden:new String[]{"org.bukkit.Bukkit", "net.minecraft.server.MinecraftServer", "net.md_5.bungee.api.ProxyServer", "com.velocitypowered.api.proxy.ProxyServer"}) {
                try { loader.loadClass(forbidden); throw new AssertionError("Platform leaked into native runtime: " + forbidden); }
                catch (ClassNotFoundException expected) { }
            }
            var names=new LinkedHashSet<String>();
            for (String argument:arguments) {
                try (var jar=new JarFile(argument)) {
                    for (var entry:jar.stream().toList()) {
                        String name=entry.getName();
                        if (name.startsWith("com/bencodez/simpleapi/") && name.endsWith(".class"))
                            names.add(name.substring(0,name.length()-6).replace('/', '.'));
                    }
                }
            }
            if (names.isEmpty()) throw new AssertionError("No shared classes found");
            for (String name:names) {
                Class<?> type=Class.forName(name, false, loader);
                type.getDeclaredConstructors(); type.getDeclaredFields(); type.getDeclaredMethods();
                type.getGenericSuperclass(); type.getGenericInterfaces();
                for (var method:type.getDeclaredMethods()) {
                    method.getGenericReturnType(); method.getGenericParameterTypes(); method.getGenericExceptionTypes();
                }
                checked++;
            }
        }
        System.out.println("Packaged headless linkage: " + checked + " classes passed");
    }
}
