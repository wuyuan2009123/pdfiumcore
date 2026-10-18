package pdfiumcore;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Exercises the bindings against the native library, mirroring {@code PDFiumCore.Tests}.
 */
class FpdfviewTests {

    private static final Path SAMPLE = Path.of("src/test/resources/pdf-sample.pdf");

    @BeforeAll
    static void initLibrary() {
        fpdfview.FPDF_InitLibrary();
    }

    @AfterAll
    static void destroyLibrary() {
        fpdfview.FPDF_DestroyLibrary();
    }

    @Test
    void readsPageCount() {
        MemorySegment document = fpdfview.FPDF_LoadDocument(SAMPLE.toString(), null);
        assertNotEquals(0, document.address(), "FPDF_LoadDocument");
        try {
            assertEquals(1, fpdfview.FPDF_GetPageCount(document));
        } finally {
            fpdfview.FPDF_CloseDocument(document);
        }
    }

    @Test
    void loadsDocumentFromMemory() throws IOException {
        byte[] bytes = Files.readAllBytes(SAMPLE);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocateFrom(JAVA_BYTE, bytes);
            MemorySegment document = fpdfview.FPDF_LoadMemDocument(buffer, bytes.length, null);
            assertNotEquals(0, document.address(), "FPDF_LoadMemDocument");
            try {
                assertEquals(1, fpdfview.FPDF_GetPageCount(document));
            } finally {
                fpdfview.FPDF_CloseDocument(document);
            }
        }
    }

    @Test
    void readsPageSize() {
        MemorySegment document = fpdfview.FPDF_LoadDocument(SAMPLE.toString(), null);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment width = arena.allocate(JAVA_DOUBLE);
            MemorySegment height = arena.allocate(JAVA_DOUBLE);
            assertEquals(1, fpdfview.FPDF_GetPageSizeByIndex(document, 0, width, height),
                    "FPDF_GetPageSizeByIndex");
            assertEquals(595.0, width.get(JAVA_DOUBLE, 0L), 0.5);
            assertEquals(842.0, height.get(JAVA_DOUBLE, 0L), 0.5);
        } finally {
            fpdfview.FPDF_CloseDocument(document);
        }
    }

    @Test
    void rendersPageIntoBitmap() {
        MemorySegment document = fpdfview.FPDF_LoadDocument(SAMPLE.toString(), null);
        MemorySegment page = fpdfview.FPDF_LoadPage(document, 0);
        MemorySegment bitmap = MemorySegment.NULL;
        try {
            assertNotEquals(0, page.address(), "FPDF_LoadPage");
            int width = 120;
            int height = 160;
            bitmap = fpdfview.FPDFBitmap_CreateEx(width, height, FPDFBitmapFormat.BGRA.value(),
                    MemorySegment.NULL, 0);
            assertNotEquals(0, bitmap.address(), "FPDFBitmap_CreateEx");

            // White background so unrendered pixels are distinguishable.
            fpdfview.FPDFBitmap_FillRect(bitmap, 0, 0, width, height, 0xFFFFFFFFL);
            fpdfview.FPDF_RenderPageBitmap(bitmap, page, 0, 0, width, height, 0,
                    RenderFlags.RenderAnnotations.value());

            int stride = fpdfview.FPDFBitmap_GetStride(bitmap);
            MemorySegment buffer = fpdfview.FPDFBitmap_GetBuffer(bitmap)
                    .reinterpret((long) stride * height);

            long painted = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (buffer.get(JAVA_INT_UNALIGNED, (long) y * stride + (long) x * 4)
                            != 0xFFFFFFFF) {
                        painted++;
                    }
                }
            }
            assertTrue(painted > 0, "expected at least one painted pixel");
        } finally {
            if (bitmap.address() != 0) {
                fpdfview.FPDFBitmap_Destroy(bitmap);
            }
            if (page.address() != 0) {
                fpdfview.FPDF_ClosePage(page);
            }
            fpdfview.FPDF_CloseDocument(document);
        }
    }

    @Test
    void extractsPageText() {
        MemorySegment document = fpdfview.FPDF_LoadDocument(SAMPLE.toString(), null);
        MemorySegment page = fpdfview.FPDF_LoadPage(document, 0);
        MemorySegment textPage = MemorySegment.NULL;
        try {
            textPage = fpdf_text.FPDFText_LoadPage(page);
            assertNotEquals(0, textPage.address(), "FPDFText_LoadPage");
            assertTrue(fpdf_text.FPDFText_CountChars(textPage) > 0,
                    "expected the sample document to contain text");
        } finally {
            if (textPage.address() != 0) {
                fpdf_text.FPDFText_ClosePage(textPage);
            }
            fpdfview.FPDF_ClosePage(page);
            fpdfview.FPDF_CloseDocument(document);
        }
    }

    @Test
    void reportsInvalidPassword() {
        MemorySegment document = fpdfview.FPDF_LoadDocument(SAMPLE.toString(), "not-the-password");
        try {
            // Either the document loads (password ignored) or the last error is non-zero.
            if (document.address() == 0) {
                assertNotEquals(0, fpdfview.FPDF_GetLastError());
            }
        } finally {
            if (document.address() != 0) {
                fpdfview.FPDF_CloseDocument(document);
            }
        }
    }
}
