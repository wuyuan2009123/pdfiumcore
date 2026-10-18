package pdfiumcore.generator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves C type spellings (as found in the PDFium headers) into {@link CType}s and
 * knows the size/alignment of every primitive for the target platform.
 */
final class TypeSystem {

    /** A primitive C type mapped onto a Java carrier type and a FFM value layout. */
    static final class Primitive {
        final String javaType;
        final int size;
        final int align;
        final String layout;

        Primitive(String javaType, int size, int align, String layout) {
            this.javaType = javaType;
            this.size = size;
            this.align = align;
            this.layout = layout;
        }
    }

    private static final Map<String, Primitive> PRIMITIVES = new LinkedHashMap<>();

    private static void prim(String canonical, String javaType, int size, int align, String layout) {
        PRIMITIVES.put(canonical, new Primitive(javaType, size, align, layout));
    }

    static {
        prim("void", "void", 0, 1, "MemoryLayout.paddingLayout(0)");
        prim("char", "byte", 1, 1, "JAVA_BYTE");
        prim("signed char", "byte", 1, 1, "JAVA_BYTE");
        prim("unsigned char", "byte", 1, 1, "JAVA_BYTE");
        prim("short", "short", 2, 2, "JAVA_SHORT");
        prim("unsigned short", "short", 2, 2, "JAVA_SHORT");
        prim("int", "int", 4, 4, "JAVA_INT");
        prim("unsigned int", "int", 4, 4, "JAVA_INT");
        prim("long long", "long", 8, 8, "JAVA_LONG");
        prim("unsigned long long", "long", 8, 8, "JAVA_LONG");
        prim("float", "float", 4, 4, "JAVA_FLOAT");
        prim("double", "double", 8, 8, "JAVA_DOUBLE");
        prim("size_t", "long", 8, 8, "JAVA_LONG");
        prim("intptr_t", "long", 8, 8, "JAVA_LONG");
        prim("uintptr_t", "long", 8, 8, "JAVA_LONG");
        prim("time_t", "long", 8, 8, "JAVA_LONG");
        prim("bool", "byte", 1, 1, "JAVA_BYTE");
        prim("int8_t", "byte", 1, 1, "JAVA_BYTE");
        prim("uint8_t", "byte", 1, 1, "JAVA_BYTE");
        prim("int16_t", "short", 2, 2, "JAVA_SHORT");
        prim("uint16_t", "short", 2, 2, "JAVA_SHORT");
        prim("int32_t", "int", 4, 4, "JAVA_INT");
        prim("uint32_t", "int", 4, 4, "JAVA_INT");
        prim("int64_t", "long", 8, 8, "JAVA_LONG");
        prim("uint64_t", "long", 8, 8, "JAVA_LONG");
    }

    /** Platform handle types which are ABI-equivalent to {@code void*}. */
    private static final Set<String> OPAQUE_POINTERS = Set.of(
            "HDC", "HANDLE", "HWND", "HFONT", "HBRUSH", "HPEN", "HBITMAP", "HMODULE");

    private final boolean windows;
    private final Map<String, CType> aliases = new LinkedHashMap<>();
    private final Map<String, String> structTags = new HashMap<>();
    private final Set<String> structNames = new HashSet<>();
    private final Set<String> enumNames = new HashSet<>();
    private final Set<String> callbackNames = new HashSet<>();
    private final List<String> unresolved = new ArrayList<>();

    TypeSystem(boolean windows) {
        this.windows = windows;
    }

    boolean isWindows() {
        return windows;
    }

    Map<String, CType> aliases() {
        return aliases;
    }

    List<String> unresolved() {
        return unresolved;
    }

    void registerStruct(String canonicalName, String tag) {
        structNames.add(canonicalName);
        if (tag != null && !tag.isEmpty()) {
            structTags.put(tag, canonicalName);
            structTags.putIfAbsent(canonicalName, canonicalName);
        }
    }

    void registerStructTag(String tag, String canonicalName) {
        structTags.put(tag, canonicalName);
    }

    void registerEnum(String canonicalName) {
        enumNames.add(canonicalName);
    }

    void registerCallback(String canonicalName) {
        callbackNames.add(canonicalName);
    }

    void registerAlias(String alias, CType type) {
        aliases.put(alias, type);
    }

    Set<String> structNames() {
        return structNames;
    }

    Set<String> enumNames() {
        return enumNames;
    }

    Set<String> callbackNames() {
        return callbackNames;
    }

    /** Resolves a raw C type spelling such as {@code const struct _FS_MATRIX_ *}. */
    CType resolve(String raw) {
        return resolve(raw, 0);
    }

    private CType resolve(String raw, int depth) {
        if (depth > 32) {
            return CType.unknown(raw);
        }
        String s = normalize(raw);
        if (s.isEmpty()) {
            return CType.unknown(raw);
        }

        boolean isConst = false;
        boolean changed = true;
        while (changed) {
            changed = false;
            if (s.startsWith("const ")) {
                isConst = true;
                s = s.substring(6).trim();
                changed = true;
            } else if (s.endsWith(" const")) {
                isConst = true;
                s = s.substring(0, s.length() - 6).trim();
                changed = true;
            }
            if (s.startsWith("volatile ")) {
                s = s.substring(9).trim();
                changed = true;
            }
        }

        // Pointer declarator (possibly a pointer to const).
        if (s.equals("void")) {
            return CType.VOID;
        }
        if (s.endsWith("*")) {
            String inner = s.substring(0, s.length() - 1).trim();
            return CType.pointer(resolve(inner, depth + 1), isConst, raw.trim());
        }

        if (s.startsWith("struct ") || s.startsWith("union ")) {
            String tag = s.substring(s.indexOf(' ') + 1).trim();
            String canonical = structTags.getOrDefault(tag, tag);
            return CType.struct(canonical, raw.trim());
        }
        if (s.startsWith("enum ")) {
            String tag = s.substring(5).trim();
            return CType.enumType(aliases.containsKey(tag) ? tag : tag, raw.trim());
        }

        CType alias = aliases.get(s);
        if (alias != null) {
            if (isConst && alias.kind == CType.Kind.POINTER) {
                return alias.asConst();
            }
            return alias;
        }

        Primitive primitive = primitiveFor(s);
        if (primitive != null) {
            CType t = CType.primitive(s, raw.trim());
            return isConst ? t.asConst() : t;
        }

        if (OPAQUE_POINTERS.contains(s)) {
            return CType.pointer(CType.VOID, isConst, raw.trim());
        }

        if (structNames.contains(s)) {
            return CType.struct(s, raw.trim());
        }
        if (enumNames.contains(s)) {
            return CType.enumType(s, raw.trim());
        }
        if (callbackNames.contains(s)) {
            return CType.callback(s, raw.trim());
        }
        return CType.unknown(raw.trim());
    }

    /** Returns the primitive descriptor for a canonical C type name, or null. */
    Primitive primitiveFor(String canonical) {
        String c = canonical(canonical);
        switch (c) {
            case "long":
            case "unsigned long":
                // C `long` is 32 bits on Windows (LLP64) and 64 bits on Linux/macOS (LP64).
                // Both ABIs pass integer arguments in 64-bit registers, and writing a 32-bit
                // register zero-extends it, so a 64-bit carrier is ABI-safe on every 64-bit
                // target and lets a single binding set serve all of them.
                return new Primitive("long", 8, 8, "JAVA_LONG");
            default:
                return PRIMITIVES.get(c);
        }
    }

    /** Normalises spelling: drops redundant {@code int}, sorts qualifiers. */
    static String canonical(String raw) {
        String s = normalize(raw);
        if (s.startsWith("struct ") || s.startsWith("union ") || s.startsWith("enum ")) {
            return s;
        }
        boolean unsigned = false;
        List<String> out = new ArrayList<>();
        for (String token : s.split(" ")) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.equals("unsigned")) {
                unsigned = true;
            } else if (token.equals("signed")) {
                // redundant
            } else {
                out.add(token);
            }
        }
        if (out.isEmpty()) {
            out.add("int");
        } else if (out.size() > 1) {
            out.removeIf("int"::equals);
        }
        if (unsigned) {
            out.add(0, "unsigned");
        }
        return String.join(" ", out);
    }

    static String normalize(String raw) {
        return raw.replaceAll("\\s+", " ").trim();
    }
}
