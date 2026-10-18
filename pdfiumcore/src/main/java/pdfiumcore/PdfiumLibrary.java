package pdfiumcore;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Loads the native PDFium library and exposes the primitives used by the generated bindings.
 *
 * <p>The library is looked up in the following order:
 * <ol>
 *   <li>the {@code pdfium.library.path} system property (a file or a directory),</li>
 *   <li>every entry of {@code java.library.path},</li>
 *   <li>the {@code pdfium/native/<platform>/} directory of the classpath, which is extracted to a
 *       temporary file.</li>
 * </ol>
 */
public final class PdfiumLibrary {

    private static final Object LOCK = new Object();
    private static volatile SymbolLookup lookup;
    private static volatile String loadedFrom;

    private PdfiumLibrary() {
    }

    /** Returns the symbol lookup for the loaded PDFium library. */
    public static SymbolLookup lookup() {
        SymbolLookup result = lookup;
        if (result == null) {
            synchronized (LOCK) {
                result = lookup;
                if (result == null) {
                    result = load();
                    lookup = result;
                }
            }
        }
        return result;
    }

    /** Describes where the native library was loaded from; useful for diagnostics. */
    public static String loadedFrom() {
        lookup();
        return loadedFrom;
    }

    /** Resolves one exported function and turns it into a downcall handle. */
    public static MethodHandle downcall(String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = lookup().find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError(
                        "PDFium symbol not found: " + name + " (library: " + loadedFrom() + ")"));
        return Linker.nativeLinker().downcallHandle(symbol, descriptor);
    }

    /** Creates an upcall stub for a callback which PDFium can invoke. */
    public static MemorySegment upcall(MethodHandle handle, FunctionDescriptor descriptor, Arena arena) {
        return Linker.nativeLinker().upcallStub(handle, descriptor, arena);
    }

    /**
     * Returns the version of the bundled PDFium build (for example {@code 157.0.8100.0}), or
     * {@code null} when the version file is not on the classpath.
     */
    public static String bundledVersion() {
        try (InputStream in = PdfiumLibrary.class.getClassLoader()
                .getResourceAsStream("pdfium/VERSION")) {
            if (in == null) {
                return null;
            }
            String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String major = versionPart(text, "MAJOR");
            String minor = versionPart(text, "MINOR");
            String build = versionPart(text, "BUILD");
            String patch = versionPart(text, "PATCH");
            if (major == null || minor == null || build == null || patch == null) {
                return null;
            }
            return major + "." + minor + "." + build + "." + patch;
        } catch (IOException e) {
            return null;
        }
    }

    private static String versionPart(String text, String key) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(key + "=")) {
                return trimmed.substring(key.length() + 1).trim();
            }
        }
        return null;
    }

    private static SymbolLookup load() {
        String explicit = System.getProperty("pdfium.library.path");
        if (explicit != null && !explicit.isBlank()) {
            Path path = Path.of(explicit);
            if (Files.isDirectory(path)) {
                path = path.resolve(nativeFileName());
            }
            if (!Files.isRegularFile(path)) {
                throw new UnsatisfiedLinkError("pdfium.library.path does not point at a library: " + path);
            }
            return lookup(path);
        }

        String libraryPath = System.getProperty("java.library.path", "");
        for (String entry : libraryPath.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry, nativeFileName());
            if (Files.isRegularFile(candidate)) {
                return lookup(candidate);
            }
        }

        String resource = "pdfium/native/" + platform() + "/" + nativeFileName();
        try (InputStream in = PdfiumLibrary.class.getClassLoader().getResourceAsStream(resource)) {
            if (in != null) {
                Path directory = Files.createTempDirectory("pdfiumcore-native");
                Path target = directory.resolve(nativeFileName());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                target.toFile().deleteOnExit();
                directory.toFile().deleteOnExit();
                return lookup(target);
            }
        } catch (IOException e) {
            throw new UnsatisfiedLinkError("Unable to extract " + resource + ": " + e);
        }

        throw new UnsatisfiedLinkError(
                "Unable to locate the PDFium native library (" + nativeFileName() + "). "
                        + "Set -Dpdfium.library.path=<file-or-directory> or put it on java.library.path.");
    }

    private static SymbolLookup lookup(Path path) {
        try {
            SymbolLookup result = SymbolLookup.libraryLookup(path, Arena.global());
            loadedFrom = path.toAbsolutePath().toString();
            return result;
        } catch (IllegalArgumentException e) {
            throw new UnsatisfiedLinkError("Unable to load " + path.toAbsolutePath() + ": " + e.getMessage());
        }
    }

    private static String nativeFileName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "pdfium.dll";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "libpdfium.dylib";
        }
        return "libpdfium.so";
    }

    private static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String machine = switch (arch) {
            case "amd64", "x86_64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            case "x86", "i386", "i686" -> "x86";
            default -> arch;
        };
        if (os.contains("win")) {
            return "win-" + machine;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos-" + machine;
        }
        return "linux-" + machine;
    }
}
