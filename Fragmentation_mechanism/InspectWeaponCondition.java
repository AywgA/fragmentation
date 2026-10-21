import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import java.util.spi.ToolProvider;

/**
 * Scans a JAR file for the string "weapon_condition" and related keywords,
 * then disassembles matching classes with javap to reveal the actual
 * durability/condition logic.
 *
 * Output is written to weapon_condition_scan.txt in the same folder.
 */
public final class InspectWeaponCondition {
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args.length > 0
            ? args[0]
            : "C:\\Users\\我的电脑\\Desktop\\兼容\\tacz_tactical_breaching-forge-1.20.1-1.0.4.jar");
        Path output = Path.of("weapon_condition_scan.txt");
        if (!Files.exists(jar)) {
            System.err.println("JAR not found: " + jar);
            return;
        }

        StringBuilder out = new StringBuilder();
        out.append("=== Scanning JAR: ").append(jar).append(" ===\n");
        out.append("Size: ").append(Files.size(jar)).append(" bytes\n\n");

        // Keywords to search for in class file bytes
        String[] keywords = {
            "weapon_condition",
            "WeaponCondition",
            "Condition",
            "condition",
            "Durability",
            "durability",
            "Wear",
            "wear",
            "tacz_tactical_breaching",
            "Inaccuracy",
            "MOA",
            "moa",
            "spread",
            "Spread"
        };

        List<String> allEntries = new ArrayList<>();
        Map<String, List<String>> matchedClasses = new LinkedHashMap<>();

        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                allEntries.add(name);

                if (!name.endsWith(".class")) {
                    continue;
                }

                // Read the decompressed class bytes
                byte[] classBytes;
                try (InputStream is = jarFile.getInputStream(entry)) {
                    classBytes = is.readAllBytes();
                }

                // Convert to string for keyword searching (class files contain
                // UTF-8 string constants in the constant pool)
                String classContent = new String(classBytes, StandardCharsets.ISO_8859_1);

                List<String> foundKeywords = new ArrayList<>();
                for (String keyword : keywords) {
                    if (classContent.contains(keyword)) {
                        foundKeywords.add(keyword);
                    }
                }

                if (!foundKeywords.isEmpty()) {
                    matchedClasses.put(name, foundKeywords);
                }
            }
        }

        // Output all entries
        out.append("=== All JAR entries (").append(allEntries.size()).append(") ===\n");
        for (String entry : allEntries) {
            out.append("  ").append(entry).append("\n");
        }
        out.append("\n");

        // Output matched classes
        out.append("=== Classes matching keywords (").append(matchedClasses.size()).append(") ===\n");
        for (Map.Entry<String, List<String>> e : matchedClasses.entrySet()) {
            out.append("  ").append(e.getKey()).append(" -> ").append(e.getValue()).append("\n");
        }
        out.append("\n");

        // Disassemble matched classes with javap
        out.append("=== Javap disassembly of matched classes ===\n");
        for (String className : matchedClasses.keySet()) {
            String fqcn = className
                .replace('/', '.')
                .replace(".class", "");
            out.append("\n>>>>> ").append(fqcn).append(" <<<<<\n");
            disassembleClass(jar, fqcn, out);
        }

        // Also search lang/JSON files for "condition" or "weapon_condition"
        out.append("\n=== Searching resource files for 'condition' ===\n");
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".json") || name.endsWith(".toml")
                    || name.endsWith(".txt") || name.endsWith(".lang")
                    || name.endsWith(".mcmeta") || name.endsWith(".properties")) {
                    String content;
                    try (InputStream is = jarFile.getInputStream(entry);
                         BufferedReader reader = new BufferedReader(
                             new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line).append("\n");
                        }
                        content = sb.toString();
                    }
                    String lowerContent = content.toLowerCase(Locale.ROOT);
                    if (lowerContent.contains("condition") || lowerContent.contains("durability")
                        || lowerContent.contains("wear") || lowerContent.contains("weapon_condition")) {
                        out.append("\n--- ").append(name).append(" ---\n");
                        out.append(content).append("\n");
                    }
                }
            }
        }

        // Also read mods.toml and MANIFEST.MF
        out.append("\n=== mods.toml ===\n");
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            ZipEntry modsEntry = jarFile.getEntry("META-INF/mods.toml");
            if (modsEntry != null) {
                try (InputStream is = jarFile.getInputStream(modsEntry);
                     BufferedReader reader = new BufferedReader(
                         new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        out.append(line).append("\n");
                    }
                }
            }
            out.append("\n=== MANIFEST.MF ===\n");
            ZipEntry manifestEntry = jarFile.getEntry("META-INF/MANIFEST.MF");
            if (manifestEntry != null) {
                try (InputStream is = jarFile.getInputStream(manifestEntry);
                     BufferedReader reader = new BufferedReader(
                         new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        out.append(line).append("\n");
                    }
                }
            }
        }

        Files.writeString(output, out.toString(), StandardCharsets.UTF_8);
        System.out.println("Scan complete. Results written to: " + output.toAbsolutePath());
        System.out.println("\n--- Preview ---");
        System.out.println(out.substring(0, Math.min(out.length(), 3000)));
    }

    private static void disassembleClass(Path jar, String className, StringBuilder out) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            ToolProvider.findFirst("javap").orElseThrow().run(
                stream,
                stream,
                "-classpath",
                jar.toString(),
                "-p",
                "-c",
                "-constants",
                className
            );
        } catch (Exception e) {
            out.append("  [javap failed: ").append(e.getMessage()).append("]\n");
            // Try without -c flag
            output.reset();
            try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
                ToolProvider.findFirst("javap").orElseThrow().run(
                    stream,
                    stream,
                    "-classpath",
                    jar.toString(),
                    "-p",
                    className
                );
            } catch (Exception e2) {
                out.append("  [javap -p also failed: ").append(e2.getMessage()).append("]\n");
            }
        }
        out.append(output.toString(StandardCharsets.UTF_8)).append("\n");
    }
}
