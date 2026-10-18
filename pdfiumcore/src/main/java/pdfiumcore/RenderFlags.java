package pdfiumcore;

/**
 * Rendering flags accepted by {@code FPDF_RenderPageBitmap} and friends.
 *
 * <p>Mirrors the {@code FPDF_* } rendering flags documented in {@code fpdfview.h}.
 */
public enum RenderFlags {

    /** FPDF_ANNOT: set if annotations are to be rendered. */
    RenderAnnotations(0x01),

    /** FPDF_LCD_TEXT: text rendering optimized for LCD display. */
    OptimizeTextForLcd(0x02),

    /** FPDF_NO_NATIVETEXT: don't use the native text output available on some platforms. */
    NoNativeText(0x04),

    /** FPDF_GRAYSCALE: grayscale output. */
    Grayscale(0x08),

    /** FPDF_RENDER_LIMITEDIMAGECACHE: limit image cache size. */
    LimitImageCacheSize(0x200),

    /** FPDF_RENDER_FORCEHALFTONE: always use halftone for image stretching. */
    ForceHalftone(0x400),

    /** FPDF_PRINTING: render for printing. */
    RenderForPrinting(0x800),

    /** FPDF_RENDER_NO_SMOOTHTEXT: disable anti-aliasing on text. */
    DisableTextAntialiasing(0x1000),

    /** FPDF_RENDER_NO_SMOOTHIMAGE: disable anti-aliasing on images. */
    DisableImageAntialiasing(0x2000),

    /** FPDF_RENDER_NO_SMOOTHPATH: disable anti-aliasing on paths. */
    DisablePathAntialiasing(0x4000);

    private final int value;

    RenderFlags(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    /** Combines several flags into the bit mask expected by the native API. */
    public static int combine(RenderFlags... flags) {
        int result = 0;
        for (RenderFlags flag : flags) {
            result |= flag.value;
        }
        return result;
    }
}
