package pdfiumcore.generator;

/**
 * A resolved C type. Parsing happens in {@link TypeSystem}; instances are immutable.
 */
final class CType {

    enum Kind {
        VOID,
        PRIMITIVE,
        POINTER,
        STRUCT,
        ENUM,
        CALLBACK,
        UNKNOWN
    }

    final Kind kind;
    /** Primitive name, struct name, enum name or callback name. Null for VOID/POINTER. */
    final String name;
    /** Pointee type for {@link Kind#POINTER}. */
    final CType pointee;
    final boolean isConst;
    /** Raw spelling, only used for diagnostics. */
    final String spelling;

    private CType(Kind kind, String name, CType pointee, boolean isConst, String spelling) {
        this.kind = kind;
        this.name = name;
        this.pointee = pointee;
        this.isConst = isConst;
        this.spelling = spelling;
    }

    static final CType VOID = new CType(Kind.VOID, "void", null, false, "void");

    static CType primitive(String name, String spelling) {
        return new CType(Kind.PRIMITIVE, name, null, false, spelling);
    }

    static CType pointer(CType pointee, boolean isConst, String spelling) {
        return new CType(Kind.POINTER, null, pointee, isConst, spelling);
    }

    static CType struct(String name, String spelling) {
        return new CType(Kind.STRUCT, name, null, false, spelling);
    }

    static CType enumType(String name, String spelling) {
        return new CType(Kind.ENUM, name, null, false, spelling);
    }

    static CType callback(String name, String spelling) {
        return new CType(Kind.CALLBACK, name, null, false, spelling);
    }

    static CType unknown(String spelling) {
        return new CType(Kind.UNKNOWN, spelling, null, false, spelling);
    }

    boolean isPointer() {
        return kind == Kind.POINTER;
    }

    boolean isVoid() {
        return kind == Kind.VOID;
    }

    /** True when this is a pointer to a type whose pointee is {@code char} (C string). */
    boolean isCharPointer() {
        return kind == Kind.POINTER && pointee != null
                && pointee.kind == Kind.PRIMITIVE && "char".equals(pointee.name);
    }

    CType asConst() {
        return new CType(kind, name, pointee, true, spelling);
    }

    CType pointeeOrSelf() {
        return pointee == null ? this : pointee;
    }

    @Override
    public String toString() {
        return spelling;
    }
}
