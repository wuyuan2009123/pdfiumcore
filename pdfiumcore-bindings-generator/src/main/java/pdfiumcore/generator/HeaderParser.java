package pdfiumcore.generator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Parses the public PDFium C headers into {@link Declarations} and a {@link TypeSystem}.
 *
 * <p>The parser is intentionally declaration oriented: it does not evaluate the C preprocessor,
 * it only strips comments and preprocessor lines so that function declarations, typedefs,
 * structs and enums can be read reliably from the {@code FPDF_EXPORT} annotated public API.
 */
final class HeaderParser {

    private static final Pattern FUNC_POINTER =
            Pattern.compile("^(.*?)\\(\\s*\\*\\s*([A-Za-z_]\\w*)\\s*\\)\\s*\\((.*)\\)$", Pattern.DOTALL);
    private static final Pattern FUNCTION_DECL =
            Pattern.compile("^([A-Za-z_].*?)\\s+([A-Za-z_]\\w*)\\s*\\((.*)\\)$", Pattern.DOTALL);
    private static final Pattern FIELD_DECL =
            Pattern.compile("^(.+?)\\s+([A-Za-z_]\\w*)$", Pattern.DOTALL);
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_]\\w*$");

    private final Path includeDir;
    private final TypeSystem types;
    private final Map<String, Declarations.StructDef> structs = new LinkedHashMap<>();
    private final Map<String, Declarations.EnumDef> enums = new LinkedHashMap<>();
    private final Map<String, Declarations.CallbackDef> callbacks = new LinkedHashMap<>();
    private final List<Declarations.FuncDef> functions = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();

    HeaderParser(Path includeDir, TypeSystem types) {
        this.includeDir = includeDir;
        this.types = types;
    }

    Map<String, Declarations.StructDef> structs() {
        return structs;
    }

    Map<String, Declarations.EnumDef> enums() {
        return enums;
    }

    Map<String, Declarations.CallbackDef> callbacks() {
        return callbacks;
    }

    List<Declarations.FuncDef> functions() {
        return functions;
    }

    List<String> problems() {
        return problems;
    }

    /** Parses every {@code *.h} file in the include directory. */
    void parse() throws IOException {
        List<SourceFile> files;
        try (Stream<Path> paths = Files.list(includeDir)) {
            files = paths.filter(p -> p.getFileName().toString().endsWith(".h"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(this::load)
                    .toList();
        }

        List<TypedefDecl> typedefs = new ArrayList<>();
        for (SourceFile file : files) {
            typedefs.addAll(scanTypedefs(file));
        }

        // First pass: the struct and enum definitions themselves.
        List<TypedefDecl> plainTypedefs = new ArrayList<>();
        for (TypedefDecl td : typedefs) {
            if (td.body != null) {
                if (td.isEnum) {
                    Declarations.EnumDef def = new Declarations.EnumDef(td.name, td.doc);
                    parseEnumBody(td.body, def);
                    enums.put(def.name, def);
                    types.registerEnum(def.name);
                } else {
                    Declarations.StructDef def = new Declarations.StructDef(td.name, td.tag, td.doc);
                    structs.put(def.name, def);
                    types.registerStruct(def.name, td.tag);
                    for (String alias : td.pointerAliases) {
                        types.registerAlias(alias,
                                CType.pointer(CType.struct(def.name, def.name), false, def.name + "*"));
                    }
                }
            } else if (td.callbackReturn != null) {
                Declarations.CallbackDef def = new Declarations.CallbackDef(td.name, types.resolve(td.callbackReturn), td.doc);
                callbacks.put(def.name, def);
                types.registerCallback(def.name);
            } else {
                plainTypedefs.add(td);
            }
        }

        // Second pass: plain typedefs, iterated because aliases may reference each other.
        List<TypedefDecl> pending = plainTypedefs;
        for (int round = 0; round < 8 && !pending.isEmpty(); round++) {
            List<TypedefDecl> next = new ArrayList<>();
            for (TypedefDecl td : pending) {
                CType resolved = types.resolve(td.typeText);
                if (resolved.kind == CType.Kind.UNKNOWN) {
                    next.add(td);
                } else {
                    types.registerAlias(td.name, resolved);
                }
            }
            if (next.size() == pending.size()) {
                for (TypedefDecl td : next) {
                    types.registerAlias(td.name, types.resolve(td.typeText));
                    problems.add("Unresolved typedef: " + td.typeText + " " + td.name);
                }
                break;
            }
            pending = next;
        }

        // Third pass: struct fields, now that aliases are known.
        for (Declarations.StructDef def : structs.values()) {
            TypedefDecl source = typedefs.stream()
                    .filter(t -> t.body != null && !t.isEnum && t.name.equals(def.name))
                    .findFirst().orElseThrow();
            parseStructBody(source.body, def);
        }

        // Fourth pass: callbacks, then the exported functions.
        for (Declarations.CallbackDef def : callbacks.values()) {
            TypedefDecl source = typedefs.stream()
                    .filter(t -> t.callbackReturn != null && t.name.equals(def.name))
                    .findFirst().orElseThrow();
            parseParams(source.callbackParams, def.params, def.name);
        }
        for (SourceFile file : files) {
            scanFunctions(file);
        }
    }

    private SourceFile load(Path path) {
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            return new SourceFile(path.getFileName().toString().replace(".h", ""), raw, sanitize(raw));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read " + path, e);
        }
    }

    // ---------------------------------------------------------------- comments

    /**
     * Blanks out comments and preprocessor lines while preserving offsets, so that documentation
     * comments can still be recovered from the raw text using the offsets found in the code text.
     */
    private static String sanitize(String raw) {
        StringBuilder sb = new StringBuilder(raw);
        int i = 0;
        int len = raw.length();
        while (i < len) {
            char c = raw.charAt(i);
            if (c == '/' && i + 1 < len && raw.charAt(i + 1) == '/') {
                while (i < len && raw.charAt(i) != '\n') {
                    sb.setCharAt(i, ' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < len && raw.charAt(i + 1) == '*') {
                sb.setCharAt(i, ' ');
                sb.setCharAt(i + 1, ' ');
                i += 2;
                while (i < len && !(raw.charAt(i) == '*' && i + 1 < len && raw.charAt(i + 1) == '/')) {
                    if (raw.charAt(i) != '\n') {
                        sb.setCharAt(i, ' ');
                    }
                    i++;
                }
                if (i + 1 < len) {
                    sb.setCharAt(i, ' ');
                    sb.setCharAt(i + 1, ' ');
                    i += 2;
                }
            } else if (c == '#' && onlyWhitespaceBefore(raw, i)) {
                while (i < len && raw.charAt(i) != '\n') {
                    sb.setCharAt(i, ' ');
                    i++;
                }
            } else {
                i++;
            }
        }
        return sb.toString();
    }

    private static boolean onlyWhitespaceBefore(String text, int index) {
        for (int i = index - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '\n') {
                return true;
            }
            if (!Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    /** Collects the {@code //} comment block immediately preceding {@code offset}. */
    private static String docBefore(String raw, int offset) {
        List<String> lines = new ArrayList<>();
        int pos = offset - 1;
        while (pos >= 0) {
            // Skip trailing whitespace on the current line.
            int lineEnd = pos;
            while (lineEnd >= 0 && raw.charAt(lineEnd) != '\n') {
                lineEnd--;
            }
            int lineStart = lineEnd + 1;
            String line = raw.substring(lineStart, pos + 1).strip();
            if (line.startsWith("//")) {
                lines.add(line.substring(2).strip());
                pos = lineStart - 2;
            } else if (line.isEmpty()) {
                // Allow a single blank line between the comment block and the declaration.
                int probe = lineStart - 2;
                boolean allEmpty = true;
                while (probe >= 0 && raw.charAt(probe) != '\n') {
                    if (!Character.isWhitespace(raw.charAt(probe))) {
                        allEmpty = false;
                        break;
                    }
                    probe--;
                }
                if (!allEmpty || probe < 0) {
                    break;
                }
                pos = probe;
            } else {
                break;
            }
        }
        java.util.Collections.reverse(lines);
        while (!lines.isEmpty() && lines.get(0).isEmpty()) {
            lines.remove(0);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return String.join("\n", lines);
    }

    // ---------------------------------------------------------------- typedefs

    private static final class TypedefDecl {
        String name;
        String tag;
        String typeText;
        String body;
        String callbackReturn;
        String callbackParams;
        boolean isEnum;
        String doc;
        final java.util.List<String> pointerAliases = new java.util.ArrayList<>();
    }

    private List<TypedefDecl> scanTypedefs(SourceFile file) {
        List<TypedefDecl> result = new ArrayList<>();
        String code = file.code;
        int i = 0;
        while ((i = indexOfWord(code, "typedef", i)) >= 0) {
            int start = i;
            int brace = 0;
            int paren = 0;
            int j = i + "typedef".length();
            for (; j < code.length(); j++) {
                char c = code.charAt(j);
                if (c == '{') {
                    brace++;
                } else if (c == '}') {
                    brace--;
                } else if (c == '(') {
                    paren++;
                } else if (c == ')') {
                    paren--;
                } else if (c == ';' && brace == 0 && paren == 0) {
                    break;
                }
            }
            if (j >= code.length()) {
                break;
            }
            String decl = code.substring(start, j + 1);
            TypedefDecl td = parseTypedef(decl);
            if (td != null) {
                td.doc = docBefore(file.raw, start);
                result.add(td);
            }
            i = j + 1;
        }
        return result;
    }

    private TypedefDecl parseTypedef(String decl) {
        String d = decl.trim();
        d = d.substring("typedef".length()).trim();
        if (d.endsWith(";")) {
            d = d.substring(0, d.length() - 1).trim();
        }
        if (d.isEmpty()) {
            return null;
        }

        int brace = d.indexOf('{');
        if (brace >= 0) {
            int close = matchingBrace(d, brace);
            if (close < 0) {
                return null;
            }
            String head = d.substring(0, brace).trim();
            String body = d.substring(brace + 1, close);
            String tail = d.substring(close + 1).trim();
            List<String> aliases = new ArrayList<>();
            List<String> pointers = new ArrayList<>();
            for (String part : splitTopLevel(tail, ',')) {
                String p = part.trim();
                if (p.startsWith("*")) {
                    String alias = p.replace("*", "").trim();
                    if (!alias.isEmpty()) {
                        pointers.add(alias);
                    }
                } else if (!p.isEmpty()) {
                    aliases.add(p);
                }
            }
            TypedefDecl td = new TypedefDecl();
            td.body = body;
            td.isEnum = head.startsWith("enum");
            td.tag = head.replace("struct", "").replace("union", "").replace("enum", "").trim();
            if (td.tag.isEmpty()) {
                td.tag = null;
            }
            td.name = aliases.isEmpty() ? td.tag : aliases.get(0);
            td.pointerAliases.addAll(pointers);
            if (td.name == null) {
                return null;
            }
            return td;
        }

        Matcher fn = FUNC_POINTER.matcher(d);
        if (fn.matches()) {
            TypedefDecl td = new TypedefDecl();
            td.callbackReturn = fn.group(1).trim();
            td.name = fn.group(2);
            td.callbackParams = fn.group(3).trim();
            return td;
        }

        // typedef <type> <alias>;
        int idx = lastIdentifierStart(d);
        if (idx < 0) {
            return null;
        }
        String alias = d.substring(idx).trim();
        String typeText = d.substring(0, idx).trim();
        if (!IDENTIFIER.matcher(alias).matches() || typeText.isEmpty()) {
            return null;
        }
        TypedefDecl td = new TypedefDecl();
        td.name = alias;
        td.typeText = typeText;
        return td;
    }

    private static List<String> splitAliasList(String tail) {
        List<String> out = new ArrayList<>();
        for (String part : splitTopLevel(tail, ',')) {
            String p = part.trim();
            while (p.startsWith("*")) {
                p = p.substring(1).trim();
            }
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    private static int matchingBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int lastIdentifierStart(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && (Character.isLetterOrDigit(s.charAt(start - 1)) || s.charAt(start - 1) == '_')) {
            start--;
        }
        if (start == end) {
            return -1;
        }
        if (start > 0 && (s.charAt(start - 1) == '*' || s.charAt(start - 1) == ' ')) {
            return start;
        }
        return start;
    }

    // ---------------------------------------------------------------- enums / structs

    private void parseEnumBody(String body, Declarations.EnumDef def) {
        for (String item : splitTopLevel(body, ',')) {
            String entry = item.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int eq = entry.indexOf('=');
            if (eq < 0) {
                def.constants.put(entry, null);
            } else {
                def.constants.put(entry.substring(0, eq).trim(), entry.substring(eq + 1).trim());
            }
        }
    }

    private void parseStructBody(String body, Declarations.StructDef def) {
        for (String rawField : splitTopLevel(body, ';')) {
            String field = rawField.trim();
            if (field.isEmpty()) {
                continue;
            }
            Matcher fp = FUNC_POINTER.matcher(field);
            if (fp.matches()) {
                CType ret = types.resolve(fp.group(1));
                List<Declarations.Param> params = new ArrayList<>();
                parseParams(fp.group(3).trim(), params, def.name + "." + fp.group(2));
                def.fields.add(new Declarations.Field(fp.group(2), null, ret, params, null));
                continue;
            }
            int idx = lastIdentifierStart(field);
            if (idx < 0) {
                problems.add("Unparsable field in " + def.name + ": " + field);
                continue;
            }
            String typeText = field.substring(0, idx).trim();
            String names = field.substring(idx).trim();
            for (String name : names.split(",")) {
                String n = name.trim();
                if (n.startsWith("*")) {
                    n = n.substring(1).trim();
                    typeText = typeText + "*";
                }
                if (!IDENTIFIER.matcher(n).matches()) {
                    problems.add("Unparsable field name in " + def.name + ": " + n);
                    continue;
                }
                def.fields.add(new Declarations.Field(n, types.resolve(typeText), null, null, null));
            }
        }
    }

    // ---------------------------------------------------------------- functions

    private void scanFunctions(SourceFile file) {
        String code = file.code;
        int i = 0;
        String needle = "FPDF_EXPORT";
        while ((i = code.indexOf(needle, i)) >= 0) {
            int semi = code.indexOf(';', i);
            if (semi < 0) {
                break;
            }
            String decl = code.substring(i + needle.length(), semi).trim();
            decl = decl.replace("FPDF_CALLCONV", " ").replaceAll("\\s+", " ").trim();
            Matcher m = FUNCTION_DECL.matcher(decl);
            if (!m.matches()) {
                problems.add("Unparsable function declaration in " + file.name + ": " + decl);
                i = semi;
                continue;
            }
            String name = m.group(2);
            CType ret = types.resolve(m.group(1));
            Declarations.FuncDef def = new Declarations.FuncDef(name, ret, file.name,
                    docBefore(file.raw, i));
            parseParams(m.group(3).trim(), def.params, name);
            functions.add(def);
            i = semi;
        }
    }

    private void parseParams(String params, List<Declarations.Param> out, String owner) {
        String p = params.trim();
        if (p.isEmpty() || p.equals("void")) {
            return;
        }
        int index = 0;
        for (String part : splitTopLevel(p, ',')) {
            String param = part.trim();
            if (param.isEmpty()) {
                continue;
            }
            Matcher fp = FUNC_POINTER.matcher(param);
            if (fp.matches()) {
                out.add(new Declarations.Param(fp.group(2), CType.pointer(CType.unknown("fn"), false, param)));
                index++;
                continue;
            }
            Matcher m = FIELD_DECL.matcher(param);
            String name;
            String typeText;
            if (m.matches()) {
                name = m.group(2);
                typeText = m.group(1).trim();
            } else {
                name = "arg" + index;
                typeText = param;
            }
            CType type = types.resolve(typeText);
            if (type.kind == CType.Kind.UNKNOWN) {
                problems.add("Unknown parameter type in " + owner + ": " + param);
            }
            out.add(new Declarations.Param(name, type));
            index++;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static int indexOfWord(String text, String word, int from) {
        int i = from;
        while ((i = text.indexOf(word, i)) >= 0) {
            boolean startOk = i == 0 || !isIdent(text.charAt(i - 1));
            int end = i + word.length();
            boolean endOk = end >= text.length() || !isIdent(text.charAt(end));
            if (startOk && endOk) {
                return i;
            }
            i = end;
        }
        return -1;
    }

    private static boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Splits on {@code separator} at nesting depth zero (brackets and parentheses). */
    static List<String> splitTopLevel(String s, char separator) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int last = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == separator && depth == 0) {
                out.add(s.substring(last, i));
                last = i + 1;
            }
        }
        out.add(s.substring(last));
        return out;
    }

    private record SourceFile(String name, String raw, String code) {
    }
}
