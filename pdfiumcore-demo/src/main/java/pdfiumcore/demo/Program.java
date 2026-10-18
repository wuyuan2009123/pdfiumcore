package pdfiumcore.demo;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import javax.imageio.ImageIO;
import pdfiumcore.FPDFBitmapFormat;
import pdfiumcore.FS_MATRIX;
import pdfiumcore.FS_RECTF;
import pdfiumcore.PdfiumException;
import pdfiumcore.RenderFlags;
import pdfiumcore.fpdfview;

/**
 * Renders the first page of a PDF document into a PNG, mirroring the {@code PDFiumCoreDemo}
 * console application of the .NET project.
 *
 * <pre>
 * mvn -q exec:java -Dexec.args="pdf-sample.pdf output.png"
 * </pre>
 */
public final class Program {

    private Program() {
    }

    public static void main(String[] args) throws IOException {
        Path input = args.length > 0 ? Path.of(args[0]) : extractSample();
        Path output = args.length > 1 ? Path.of(args[1]) : Path.of("output.png");

        fpdfview.FPDF_InitLibrary();
        try {
            renderPageToImage(input, output, 0, 1f);
            System.out.println("Rendered " + input.toAbsolutePath() + " -> " + output.toAbsolutePath());
        } finally {
            fpdfview.FPDF_DestroyLibrary();
        }
    }

    /** Renders {@code pageIndex} of {@code input} into {@code output} as a PNG. */
    public static void renderPageToImage(Path input, Path output, int pageIndex, float scale)
            throws IOException {
        MemorySegment document = fpdfview.FPDF_LoadDocument(input.toString(), null);
        if (document.address() == 0) {
            throw new PdfiumException("Unable to load " + input
                    + " (FPDF_GetLastError=" + fpdfview.FPDF_GetLastError() + ")");
        }

        MemorySegment page = MemorySegment.NULL;
        MemorySegment bitmap = MemorySegment.NULL;
        try {
            page = fpdfview.FPDF_LoadPage(document, pageIndex);
            if (page.address() == 0) {
                throw new PdfiumException("Unable to load page " + pageIndex);
            }

            double pageWidth;
            double pageHeight;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment width = arena.allocate(JAVA_DOUBLE);
                MemorySegment height = arena.allocate(JAVA_DOUBLE);
                if (fpdfview.FPDF_GetPageSizeByIndex(document, pageIndex, width, height) == 0) {
                    throw new PdfiumException("Unable to read the size of page " + pageIndex);
                }
                pageWidth = width.get(JAVA_DOUBLE, 0L);
                pageHeight = height.get(JAVA_DOUBLE, 0L);
            }

            Rectangle viewport = new Rectangle(0, 0, (int) Math.round(pageWidth), (int) Math.round(pageHeight));

            bitmap = fpdfview.FPDFBitmap_CreateEx(viewport.width(), viewport.height(),
                    FPDFBitmapFormat.BGRA.value(), MemorySegment.NULL, 0);
            if (bitmap.address() == 0) {
                throw new PdfiumException("Failed to create a bitmap of " + viewport.width()
                        + "x" + viewport.height());
            }

            // White background; leave this out for a transparent bitmap.
            fpdfview.FPDFBitmap_FillRect(bitmap, 0, 0, viewport.width(), viewport.height(), 0xFFFFFFFFL);

            try (FS_MATRIX matrix = new FS_MATRIX(); FS_RECTF clipping = new FS_RECTF()) {
                matrix.a(scale).b(0).c(0).d(scale)
                        .e((float) -viewport.x()).f((float) -viewport.y());

                clipping.left(0).right(viewport.width()).bottom(0).top(viewport.height());

                fpdfview.FPDF_RenderPageBitmapWithMatrix(bitmap, page, matrix.segment(),
                        clipping.segment(), RenderFlags.RenderAnnotations.value());
            }

            try (PdfImage image = new PdfImage(bitmap, viewport.width(), viewport.height())) {
                bitmap = MemorySegment.NULL; // ownership moved to PdfImage
                ImageIO.write(image.image(), "png", output.toFile());
            }
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

    /** Extracts the bundled sample document out of the classpath. */
    private static Path extractSample() throws IOException {
        try (InputStream in = Program.class.getResourceAsStream("/pdf-sample.pdf")) {
            if (in == null) {
                throw new IOException("No pdf-sample.pdf on the classpath and no input file was given");
            }
            Path target = Files.createTempFile("pdf-sample", ".pdf");
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().deleteOnExit();
            return target;
        }
    }
}
