package pdfiumcore;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the jar carries a native library for every supported platform, so that a build
 * produced on one OS can be consumed on another. The file magic checks catch truncated or
 * mislabelled downloads.
 */
class NativeResourcesTest {

    private record Bundled(String platform, String file, byte[] magic) {
    }

    private static final byte[] PE = {0x4d, 0x5a};
    private static final byte[] ELF = {0x7f, 0x45, 0x4c, 0x46};
    private static final byte[] MACHO64_LE = {(byte) 0xcf, (byte) 0xfa, (byte) 0xed, (byte) 0xfe};

    private static final List<Bundled> PLATFORMS = List.of(
            new Bundled("win-x64", "pdfium.dll", PE),
            new Bundled("win-arm64", "pdfium.dll", PE),
            new Bundled("linux-x64", "libpdfium.so", ELF),
            new Bundled("linux-arm64", "libpdfium.so", ELF),
            new Bundled("macos-x64", "libpdfium.dylib", MACHO64_LE),
            new Bundled("macos-arm64", "libpdfium.dylib", MACHO64_LE));

    @Test
    void bundlesALibraryForEverySupportedPlatform() throws IOException {
        ClassLoader loader = getClass().getClassLoader();
        for (Bundled bundled : PLATFORMS) {
            String resource = "pdfium/native/" + bundled.platform() + "/" + bundled.file();
            try (InputStream in = loader.getResourceAsStream(resource)) {
                assertNotNull(in, "missing native library: " + resource);
                assertArrayEquals(bundled.magic(), in.readNBytes(bundled.magic().length),
                        "unexpected file magic: " + resource);
            }
        }
    }

    @Test
    void bundlesTheHeadersVersionAndLicense() {
        ClassLoader loader = getClass().getClassLoader();
        assertNotNull(loader.getResource("pdfium/include/fpdfview.h"), "pdfium/include/fpdfview.h");
        assertNotNull(loader.getResource("pdfium/include/fpdf_edit.h"), "pdfium/include/fpdf_edit.h");
        assertNotNull(loader.getResource("pdfium/VERSION"), "pdfium/VERSION");
        assertNotNull(loader.getResource("pdfium/PDFIUM-LICENSE"), "pdfium/PDFIUM-LICENSE");
    }

    @Test
    void reportsTheBundledVersion() {
        assertEquals("157.0.8100.0", PdfiumLibrary.bundledVersion());
    }
}
