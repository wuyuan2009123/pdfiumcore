package pdfiumcore;

import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

/**
 * Verifies the runtime support layer: native library loading, struct layouts and string
 * marshalling. It does not touch documents, so it only needs the library to be loadable.
 */
class PdfiumSupportTest {

    @Test
    void loadsTheNativeLibrary() {
        assertNotNull(PdfiumLibrary.loadedFrom(), "native library location");
        // FPDF_GetLastError() is safe to call before FPDF_InitLibrary().
        assertEquals(0, fpdfview.FPDF_GetLastError());
    }

    @Test
    void structLayoutsMatchTheCAbi() {
        assertEquals(24, FS_MATRIX.LAYOUT.byteSize(), "FS_MATRIX");
        assertEquals(16, FS_RECTF.LAYOUT.byteSize(), "FS_RECTF");
        assertEquals(8, FS_SIZEF.LAYOUT.byteSize(), "FS_SIZEF");
        assertEquals(8, FS_POINTF.LAYOUT.byteSize(), "FS_POINTF");
        assertEquals(32, FS_QUADPOINTSF.LAYOUT.byteSize(), "FS_QUADPOINTSF");
        assertEquals(24, FPDF_FILEACCESS.LAYOUT.byteSize(), "FPDF_FILEACCESS");
        // int + padding + 2 pointers + uint + padding + pointer + 4 ints.
        assertEquals(56, FPDF_LIBRARY_CONFIG.LAYOUT.byteSize(), "FPDF_LIBRARY_CONFIG");
    }

    @Test
    void structAccessorsRoundTrip() {
        try (FS_RECTF rect = new FS_RECTF()) {
            rect.left(1f).top(2f).right(3f).bottom(4f);
            assertEquals(1f, rect.left());
            assertEquals(2f, rect.top());
            assertEquals(3f, rect.right());
            assertEquals(4f, rect.bottom());
        }
        try (FS_MATRIX matrix = new FS_MATRIX()) {
            matrix.a(2f).b(0f).c(0f).d(2f).e(10f).f(20f);
            assertEquals(2f, matrix.a());
            assertEquals(10f, matrix.e());
            assertEquals(20f, matrix.f());
        }
    }

    @Test
    void structViewSharesNativeMemory() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment memory = arena.allocate(FS_RECTF.LAYOUT);
            FS_RECTF view = FS_RECTF.of(memory);
            view.left(7.5f);
            assertEquals(7.5f, memory.get(java.lang.foreign.ValueLayout.JAVA_FLOAT, 0L));
        }
    }

    @Test
    void utf8AndWideStringMarshalling() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment text = PdfiumRuntime.utf8(arena, "pdf-sample.pdf");
            assertEquals("pdf-sample.pdf", PdfiumRuntime.string(text));
            assertEquals("pdf-sample.pdf", PdfiumRuntime.string(text, 64));
            assertSame(MemorySegment.NULL, PdfiumRuntime.utf8(arena, null));

            MemorySegment wide = arena.allocate(16);
            wide.set(JAVA_SHORT, 0L, (short) 'A');
            wide.set(JAVA_SHORT, 2L, (short) 'B');
            wide.set(JAVA_SHORT, 4L, (short) 0);
            assertEquals("AB", PdfiumRuntime.wideString(wide));
        }
    }

    @Test
    void enumValuesMatchTheHeaders() {
        assertEquals(0x01, RenderFlags.RenderAnnotations.value());
        assertEquals(0x4000, RenderFlags.DisablePathAntialiasing.value());
        assertEquals(0x01 | 0x08, RenderFlags.combine(RenderFlags.RenderAnnotations, RenderFlags.Grayscale));
        assertEquals(4, FPDFBitmapFormat.BGRA.value());
        assertEquals(-1, FPDF_TEXT_RENDERMODE.FPDF_TEXTRENDERMODE_UNKNOWN.value());
        // FPDF_TEXTRENDERMODE_LAST is an alias of FPDF_TEXTRENDERMODE_CLIP.
        assertEquals(FPDF_TEXT_RENDERMODE.FPDF_TEXTRENDERMODE_CLIP.value(),
                FPDF_TEXT_RENDERMODE.FPDF_TEXTRENDERMODE_LAST.value());
    }
}
