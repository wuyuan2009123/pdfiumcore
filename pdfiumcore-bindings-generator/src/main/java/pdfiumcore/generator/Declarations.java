package pdfiumcore.generator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plain data model for the declarations extracted from the PDFium headers. */
final class Declarations {

    private Declarations() {
    }

    /** A single struct field. */
    static final class Field {
        final String name;
        final CType type;
        /** Set when the field is a function pointer, e.g. {@code int (*WriteBlock)(...)}. */
        final CType callbackReturn;
        final List<Param> callbackParams;
        final String doc;

        Field(String name, CType type, CType callbackReturn, List<Param> callbackParams, String doc) {
            this.name = name;
            this.type = type;
            this.callbackReturn = callbackReturn;
            this.callbackParams = callbackParams;
            this.doc = doc;
        }

        boolean isFunctionPointer() {
            return callbackReturn != null;
        }
    }

    static final class StructDef {
        final String name;
        final String tag;
        final String doc;
        final List<Field> fields = new ArrayList<>();
        /** Explicit size from the header, when one is known. */
        int declaredSize = -1;

        StructDef(String name, String tag, String doc) {
            this.name = name;
            this.tag = tag;
            this.doc = doc;
        }
    }

    static final class EnumDef {
        final String name;
        final String doc;
        /** Constant name to C value, in declaration order. */
        final Map<String, String> constants = new LinkedHashMap<>();

        EnumDef(String name, String doc) {
            this.name = name;
            this.doc = doc;
        }
    }

    static final class Param {
        final String name;
        final CType type;

        Param(String name, CType type) {
            this.name = name;
            this.type = type;
        }

        @Override
        public String toString() {
            return type + " " + name;
        }
    }

    static final class FuncDef {
        final String name;
        final CType returnType;
        final List<Param> params = new ArrayList<>();
        final String header;
        final String doc;

        FuncDef(String name, CType returnType, String header, String doc) {
            this.name = name;
            this.returnType = returnType;
            this.header = header;
            this.doc = doc;
        }
    }

    static final class CallbackDef {
        final String name;
        final CType returnType;
        final List<Param> params = new ArrayList<>();
        final String doc;

        CallbackDef(String name, CType returnType, String doc) {
            this.name = name;
            this.returnType = returnType;
            this.doc = doc;
        }
    }
}
