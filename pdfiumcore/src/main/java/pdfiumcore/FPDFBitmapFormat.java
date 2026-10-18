package pdfiumcore;

/**
 * Pixel formats supported by {@code FPDFBitmap_CreateEx}; mirrors {@code FPDFBitmap_GetFormat}.
 */
public enum FPDFBitmapFormat {

    /** Unknown or unsupported format. */
    Unknown(0),

    /** Gray scale bitmap, one byte per pixel. */
    Gray(1),

    /** 3 bytes per pixel, byte order: blue, green, red. */
    BGR(2),

    /** 4 bytes per pixel, byte order: blue, green, red, unused. */
    BGRx(3),

    /** 4 bytes per pixel, byte order: blue, green, red, alpha. */
    BGRA(4);

    private final int value;

    FPDFBitmapFormat(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static FPDFBitmapFormat of(int value) {
        for (FPDFBitmapFormat format : values()) {
            if (format.value == value) {
                return format;
            }
        }
        throw new IllegalArgumentException("Unknown bitmap format: " + value);
    }
}
