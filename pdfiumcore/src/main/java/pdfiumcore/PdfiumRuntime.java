package pdfiumcore;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

/**
 * Helpers shared by the generated bindings: string marshalling and exception unwrapping.
 */
public final class PdfiumRuntime {

    private PdfiumRuntime() {
    }

    /** Allocates a NUL terminated UTF-8 copy of {@code value}, or a NULL segment for null. */
    public static MemorySegment utf8(Arena arena, String value) {
        return value == null ? MemorySegment.NULL : arena.allocateFrom(value);
    }

    /** Rethrows {@code throwable} as an unchecked exception. */
    public static RuntimeException rethrow(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        throw new PdfiumException(throwable.getMessage(), throwable);
    }

    /** Reads a NUL terminated UTF-8 string from native memory. */
    public static String string(MemorySegment segment) {
        if (segment == null || segment.address() == 0) {
            return null;
        }
        return segment.getString(0);
    }

    /** Reads a NUL terminated UTF-8 string of at most {@code maxBytes} bytes. */
    public static String string(MemorySegment segment, long maxBytes) {
        if (segment == null || segment.address() == 0) {
            return null;
        }
        long length = 0;
        while (length < maxBytes && segment.get(java.lang.foreign.ValueLayout.JAVA_BYTE, length) != 0) {
            length++;
        }
        byte[] bytes = new byte[(int) length];
        MemorySegment.copy(segment, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, bytes, 0, bytes.length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Reads a NUL terminated UTF-16LE string, as used by the {@code FPDF_WIDESTRING} API. */
    public static String wideString(MemorySegment segment) {
        return wideString(segment, Long.MAX_VALUE);
    }

    /** Reads a NUL terminated UTF-16LE string with a hard upper bound in characters. */
    public static String wideString(MemorySegment segment, long maxChars) {
        if (segment == null || segment.address() == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        long index = 0;
        while (index < maxChars) {
            short value = segment.get(java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED, index * 2);
            if (value == 0) {
                break;
            }
            sb.append((char) value);
            index++;
        }
        return sb.toString();
    }

    /** Copies {@code length} bytes out of native memory. */
    public static byte[] bytes(MemorySegment segment, long length) {
        if (segment == null || segment.address() == 0) {
            return null;
        }
        byte[] bytes = new byte[(int) length];
        MemorySegment.copy(segment, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, bytes, 0, bytes.length);
        return bytes;
    }
}
