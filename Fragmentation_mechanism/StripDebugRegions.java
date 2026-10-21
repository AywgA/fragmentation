import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class StripDebugRegions {
    private StripDebugRegions() {
    }

    public static void main(String[] args) throws Exception {
        for (String argument : args) {
            Path path = Path.of(argument);
            List<String> output = new ArrayList<>();
            boolean skipping = false;
            boolean preservingRegion = false;
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.contains("// #region debug-point")) {
                    preservingRegion = line.contains("E:shield-event-tail");
                    skipping = !preservingRegion;
                    continue;
                }
                if (line.contains("// #endregion")) {
                    skipping = false;
                    preservingRegion = false;
                    continue;
                }
                if (skipping || line.contains("java.net.http.HttpClient")) {
                    continue;
                }
                output.add(line);
            }
            Files.write(path, output, StandardCharsets.UTF_8);
        }
    }
}
