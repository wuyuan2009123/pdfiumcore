package pdfiumcore.generator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Command line entry point which regenerates the Java bindings from the PDFium headers.
 *
 * <pre>
 * mvn -q exec:java -Dexec.args="--headers ../native/include --output ../pdfiumcore/src/main/java"
 * </pre>
 */
public final class PDFiumCoreBindingsGenerator {

    private PDFiumCoreBindingsGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path headers = Path.of("../native/include");
        Path output = Path.of("../pdfiumcore/src/main/java");
        String platform = defaultPlatform();
        String packageName = "pdfiumcore";
        String version = readVersion(Path.of("../native/VERSION"));

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--headers" -> headers = Path.of(args[++i]);
                case "--output" -> output = Path.of(args[++i]);
                case "--platform" -> platform = args[++i];
                case "--package" -> packageName = args[++i];
                case "--version" -> version = args[++i];
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }

        boolean windows = switch (platform) {
            case "win64" -> true;
            case "linux64", "macos64" -> false;
            default -> throw new IllegalArgumentException(
                    "Unsupported platform: " + platform + " (supported: win64, linux64, macos64)");
        };

        System.out.println("PDFiumCore bindings generator");
        System.out.println("  headers  : " + headers.toAbsolutePath());
        System.out.println("  output   : " + output.toAbsolutePath());
        System.out.println("  platform : " + platform);
        System.out.println("  version  : " + version);

        TypeSystem types = new TypeSystem(windows);
        HeaderParser parser = new HeaderParser(headers, types);
        parser.parse();

        System.out.printf("Parsed: %d functions, %d structs, %d enums, %d callbacks%n",
                parser.functions().size(), parser.structs().size(), parser.enums().size(),
                parser.callbacks().size());

        if (!parser.problems().isEmpty()) {
            System.out.println("Parser problems:");
            parser.problems().stream().distinct().limit(25)
                    .forEach(p -> System.out.println("  - " + p));
            System.out.println("  (" + parser.problems().size() + " total)");
        }

        String description = "PDFium " + version + " (" + platform + ") bindings, generated from the PDFium headers";
        JavaEmitter emitter = new JavaEmitter(parser, types, output, packageName, description);
        emitter.emitAll();

        if (!emitter.warnings().isEmpty()) {
            System.out.println("Emitter warnings:");
            emitter.warnings().stream().distinct().limit(25)
                    .forEach(w -> System.out.println("  - " + w));
        }
        System.out.println("Bindings written to " + output.toAbsolutePath());
    }

    private static String defaultPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? "win64" : "linux64";
    }

    private static String readVersion(Path versionFile) {
        if (Files.isRegularFile(versionFile)) {
            try {
                String text = Files.readString(versionFile, StandardCharsets.UTF_8);
                String major = match(text, "MAJOR");
                String minor = match(text, "MINOR");
                String build = match(text, "BUILD");
                String patch = match(text, "PATCH");
                if (major != null && minor != null && build != null && patch != null) {
                    return major + "." + minor + "." + build + "." + patch;
                }
            } catch (IOException ignored) {
                // fall through to the default below
            }
        }
        return "157.0.8100.0";
    }

    private static String match(String text, String key) {
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.startsWith(key + "=")) {
                return t.substring(key.length() + 1).trim();
            }
        }
        return null;
    }
}
