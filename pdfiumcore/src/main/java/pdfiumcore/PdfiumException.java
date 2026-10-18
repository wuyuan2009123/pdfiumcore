package pdfiumcore;

/** Unchecked exception raised when a native PDFium call fails. */
public class PdfiumException extends RuntimeException {

    public PdfiumException(String message) {
        super(message);
    }

    public PdfiumException(String message, Throwable cause) {
        super(message, cause);
    }
}
