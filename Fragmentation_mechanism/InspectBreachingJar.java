import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import java.util.spi.ToolProvider;

public final class InspectBreachingJar {
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args.length > 0 ? args[0] : "C:\\Users\\我的电脑\\Desktop\\兼容\\tacz_tactical_breaching-forge-1.20.1-1.0.4.jar");
        if (!Files.exists(jar)) {
            System.err.println("JAR not found: " + jar);
            return;
        }

        System.out.println("=== JAR file: " + jar + " ===");
        System.out.println("Size: " + Files.size(jar) + " bytes");
        System.out.println();

        // List all entries in the JAR
        System.out.println("=== JAR entries ===");
        List<String> classEntries = new ArrayList<>();
        List<String> resourceEntries = new ArrayList<>();
        try (JarInputStream jis = new JarInputStream(Files.newInputStream(jar))) {
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith(".class")) {
                    classEntries.add(name);
                } else {
                    resourceEntries.add(name);
                }
            }
        }

        // Print all class entries
        System.out.println("--- Class entries (" + classEntries.size() + ") ---");
        for (String c : classEntries) {
            System.out.println(c);
        }

        System.out.println();
        System.out.println("--- Resource entries (" + resourceEntries.size() + ") ---");
        for (String r : resourceEntries) {
            System.out.println(r);
        }

        // Read mods.toml
        System.out.println();
        System.out.println("=== mods.toml ===");
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            ZipEntry modsEntry = jarFile.getEntry("META-INF/mods.toml");
            if (modsEntry != null) {
                try (InputStream is = jarFile.getInputStream(modsEntry);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println(line);
                    }
                }
            }

            // Read mixin configs
            System.out.println();
            System.out.println("=== Mixin configs ===");
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.getName().endsWith(".mixins.json") || e.getName().contains("mixin")) {
                    System.out.println("-- " + e.getName() + " --");
                    try (InputStream is = jarFile.getInputStream(e);
                         BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            System.out.println(line);
                        }
                    }
                    System.out.println();
                }
            }
        }

        // Inspect specific classes with javap
        System.out.println();
        System.out.println("=== Class bytecode inspection ===");
        String[] classesToInspect = {
            "com.corrinedev.gundurability.config.Config",
            "com.corrinedev.gundurability.GunDurabilityMod",
            "com.corrinedev.gundurability.event.EventHandler",
            "com.corrinedev.gundurability.item.GunDurabilityItem",
            "com.corrinedev.gundurability.util.DurabilityUtils",
            "com.corrinedev.gundurability.mixin.GunMixin"
        };

        for (String className : classesToInspect) {
            if (classEntries.contains(className.replace('.', '/') + ".class")) {
                System.out.println();
                System.out.println(">>>>> " + className + " <<<<<");
                inspectClass(jar, className);
            } else {
                System.out.println();
                System.out.println(">>>>> " + className + " NOT FOUND <<<<<");
            }
        }
    }

    private static void inspectClass(Path jar, String className) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            ToolProvider.findFirst("javap").orElseThrow().run(
                stream,
                stream,
                "-classpath",
                jar.toString(),
                "-p",
                className
            );
        }
        System.out.println(output.toString(StandardCharsets.UTF_8));
    }
}
