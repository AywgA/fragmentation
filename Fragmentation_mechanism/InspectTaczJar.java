import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.spi.ToolProvider;

public final class InspectTaczJar {
    public static void main(String[] args) throws Exception {
        Path jar;
        try (var files = Files.list(Path.of("."))) {
            jar = files
                .filter(path -> path.getFileName().toString().contains("tacz"))
                .filter(path -> path.getFileName().toString().endsWith(".jar"))
                .findFirst()
                .orElseThrow();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            ToolProvider.findFirst("javap").orElseThrow().run(
                stream,
                stream,
                "-classpath",
                jar.toString(),
                "-c",
                "-p",
                "com.tacz.guns.entity.EntityKineticBullet"
            );
        }
        String bytecode = output.toString(StandardCharsets.UTF_8);
        printContext(bytecode, "onBulletTick:()V", 1000, 12000);
        printMethod(bytecode, "protected void onBulletTick");
        printMethod(bytecode, "protected void onHitEntity");
        printMethod(bytecode, "protected void onHitBlock");
    }

    private static void printMethod(String bytecode, String signature) {
        int start = bytecode.indexOf(signature);
        if (start < 0) {
            System.out.println("Method not found: " + signature);
            return;
        }
        int end = bytecode.indexOf("\n  protected ", start + signature.length());
        if (end < 0) {
            end = Math.min(bytecode.length(), start + 12000);
        }
        System.out.println(bytecode.substring(start, end));
    }

    private static void printContext(
        String bytecode,
        String marker,
        int before,
        int after
    ) {
        int markerIndex = bytecode.indexOf(marker);
        if (markerIndex < 0) {
            System.out.println("Marker not found: " + marker);
            return;
        }
        int start = Math.max(0, markerIndex - before);
        int end = Math.min(bytecode.length(), markerIndex + after);
        System.out.println(bytecode.substring(start, end));
    }
}
