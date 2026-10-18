package pdfiumcore.demo;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.awt.image.BufferedImage;
import java.lang.foreign.MemorySegment;
import pdfiumcore.fpdfview;

/**
 * Owns a PDFium bitmap and exposes its pixels as an AWT image.
 *
 * <p>PDFium renders into BGRA (or BGRx) buffers whose rows are padded to the stride reported by
 * {@code FPDFBitmap_GetStride}; this class converts them into an {@code TYPE_INT_ARGB} image.
 */
public final class PdfImage implements AutoCloseable {

    private final MemorySegment bitmap;
    private final BufferedImage image;

    PdfImage(MemorySegment bitmap, int width, int height) {
        this.bitmap = bitmap;
        int stride = fpdfview.FPDFBitmap_GetStride(bitmap);
        MemorySegment buffer = fpdfview.FPDFBitmap_GetBuffer(bitmap)
                .reinterpret((long) stride * height);
        this.image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                long offset = (long) y * stride + (long) x * 4;
                int b = buffer.get(JAVA_BYTE, offset) & 0xFF;
                int g = buffer.get(JAVA_BYTE, offset + 1) & 0xFF;
                int r = buffer.get(JAVA_BYTE, offset + 2) & 0xFF;
                int a = buffer.get(JAVA_BYTE, offset + 3) & 0xFF;
                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
    }

    public BufferedImage image() {
        return image;
    }

    public int width() {
        return image.getWidth();
    }

    public int height() {
        return image.getHeight();
    }

    @Override
    public void close() {
        fpdfview.FPDFBitmap_Destroy(bitmap);
    }
}
