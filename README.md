# PDFiumCore (Java)

PDFiumCore is a Java wrapper for the [PDFium](https://pdfium.googlesource.com/pdfium/) library.
It bundles the native binaries and Foreign Function &amp; Memory bindings which are generated
directly from the public PDFium header files.

This project is a Java port of [Dtronix/PDFiumCore](https://github.com/Dtronix/PDFiumCore), which
provides the same bindings for .NET.

## Requirements

- JDK 22 or newer (the bindings use `java.lang.foreign`)
- Maven 3.9+
- A native PDFium library. Binaries for win-x64, win-arm64, linux-x64, linux-arm64,
  macos-x64 and macos-arm64 are bundled; see [Native libraries](#native-libraries).

## Modules

| Module | Description |
| --- | --- |
| `pdfiumcore` | The library: generated bindings, native library loading, helper types and the bundled native binaries. |
| `pdfiumcore-demo` | Console application which renders the first page of a PDF document to a PNG. |
| `pdfiumcore-tests` | Integration tests which run against the native library. |
| `pdfiumcore-bindings-generator` | Parses the PDFium headers and regenerates the `fpdf*` binding classes. |
| `native` | The untouched PDFium distribution: headers, `VERSION` and the PDFium license. |

## Building

```bash
mvn install
```

The build produces the same artifacts as the .NET solution: the library, the demo, the integration
tests, and a regenerated binder.

## Distribution packages

`mvn package` also writes one archive per platform to `dist/` (git-ignored):

| Archive | Contents |
| --- | --- |
| `pdfiumcore-<version>-win-x64.zip` | `lib/pdfium.dll`, `include/`, `VERSION`, `PDFIUM-LICENSE`, `README.md`, `LICENSE` |
| `pdfiumcore-<version>-win-arm64.zip` | `lib/pdfium.dll`, ... |
| `pdfiumcore-<version>-linux-x64.zip` | `lib/libpdfium.so`, ... |
| `pdfiumcore-<version>-linux-arm64.zip` | `lib/libpdfium.so`, ... |
| `pdfiumcore-<version>-macos-x64.zip` | `lib/libpdfium.dylib`, ... |
| `pdfiumcore-<version>-macos-arm64.zip` | `lib/libpdfium.dylib`, ... |
| `pdfiumcore-<version>-all.zip` | every platform under `lib/<platform>/`, plus headers and licenses |

Each archive unpacks into `pdfium-<pdfium.version>-<platform>/`, so two versions can be installed
side by side. The descriptors live in `pdfiumcore/src/assembly/`; to add a platform, drop the
library into `pdfiumcore/src/main/resources/pdfium/native/<platform>/`, add a matching descriptor
and one `<execution>` in `pdfiumcore/pom.xml`.

## Branching model

The repository follows [Git Flow](https://nvie.com/posts/a-successful-git-branching-model/)
(`git flow init`, AVH edition):

| Branch | Purpose |
| --- | --- |
| `main` | Released history only; every commit is a release, tagged `v<version>`. |
| `develop` | Integration branch; the default working branch. |
| `feature/*` | One topic per branch, merged back into `develop`. |
| `release/*` | Stabilisation before a release; merges into `main` and back into `develop`. |
| `hotfix/*` | Urgent fixes branched from `main`, merged into both `main` and `develop`. |

```bash
git flow feature start my-change
# ... work, commit ...
git flow feature finish my-change

git flow release start 1.0.0
# ... bump versions, final checks ...
git flow release finish -m "PDFiumCore 1.0.0" 1.0.0
```

## Usage

The bindings mirror the C API one-to-one: every `FPDF_EXPORT` function becomes a static method on a
class named after its header (`fpdfview`, `fpdf_edit`, `fpdf_text`, ...), and every struct becomes a
class holding a `MemoryLayout` and accessors.

```java
import java.lang.foreign.MemorySegment;
import pdfiumcore.FPDFBitmapFormat;
import pdfiumcore.FS_MATRIX;
import pdfiumcore.FS_RECTF;
import pdfiumcore.RenderFlags;
import pdfiumcore.fpdfview;

fpdfview.FPDF_InitLibrary();
try {
    // Load the document and its first page.
    MemorySegment document = fpdfview.FPDF_LoadDocument("input.pdf", null);
    MemorySegment page = fpdfview.FPDF_LoadPage(document, 0);

    // Render into a BGRA bitmap.
    MemorySegment bitmap = fpdfview.FPDFBitmap_CreateEx(595, 842,
            FPDFBitmapFormat.BGRA.value(), MemorySegment.NULL, 0);
    fpdfview.FPDFBitmap_FillRect(bitmap, 0, 0, 595, 842, 0xFFFFFFFF);

    try (FS_MATRIX matrix = new FS_MATRIX(); FS_RECTF clipping = new FS_RECTF()) {
        matrix.a(1).b(0).c(0).d(1).e(0).f(0);
        clipping.left(0).right(595).bottom(0).top(842);
        fpdfview.FPDF_RenderPageBitmapWithMatrix(bitmap, page, matrix.segment(),
                clipping.segment(), RenderFlags.RenderAnnotations.value());
    }

    // FPDFBitmap_GetBuffer()/FPDFBitmap_GetStride() expose the pixels; see PdfImage in the demo.
    fpdfview.FPDFBitmap_Destroy(bitmap);
    fpdfview.FPDF_ClosePage(page);
    fpdfview.FPDF_CloseDocument(document);
} finally {
    fpdfview.FPDF_DestroyLibrary();
}
```

Run the demo, which renders the bundled sample document:

```bash
cd pdfiumcore-demo
mvn exec:java -Dexec.args="src/main/resources/pdf-sample.pdf output.png"
```

### Structs and strings

- Structs are allocated off-heap. The no-argument constructor owns a confined `Arena` and implements
  `AutoCloseable`; `of(MemorySegment)` wraps memory owned by someone else. Pass `struct.segment()` to
  the bindings, which take pointers.
- `const char*` parameters are exposed as `String` and converted to NUL terminated UTF-8. Wider
  strings (`FPDF_WIDESTRING`) are exposed as `MemorySegment`; use `PdfiumRuntime.wideString(...)` to
  decode them.
- NULL handles are represented by `MemorySegment.NULL`; test them with `segment.address() == 0`.

## Native libraries

`PdfiumLibrary` looks for the native library in this order:

1. the `pdfium.library.path` system property (a library file or a directory containing it),
2. the entries of `java.library.path`,
3. the `pdfium/native/<platform>/` directory of the classpath, extracted to a temporary file.

A library is bundled for every supported 64-bit platform:

| Platform | Bundled resource | File name |
| --- | --- | --- |
| Windows x64 | `pdfium/native/win-x64/` | `pdfium.dll` |
| Windows arm64 | `pdfium/native/win-arm64/` | `pdfium.dll` |
| Linux x64 | `pdfium/native/linux-x64/` | `libpdfium.so` |
| Linux arm64 | `pdfium/native/linux-arm64/` | `libpdfium.so` |
| macOS x64 | `pdfium/native/macos-x64/` | `libpdfium.dylib` |
| macOS arm64 | `pdfium/native/macos-arm64/` | `libpdfium.dylib` |

The platform directory is derived from `os.name` and `os.arch`. To use a different build, point
`-Dpdfium.library.path` at it, or drop it into
`pdfiumcore/src/main/resources/pdfium/native/<platform>/`. `PdfiumLibrary.bundledVersion()` reports
the version of the bundled build; `NativeResourcesTest` checks that every platform is present and
that the file magic matches its type.

## ABI notes

- `long` / `unsigned long` (`FPDF_DWORD`) is carried as a 64-bit Java `long`. On 64-bit Windows the
  argument occupies the low 32 bits of a 64-bit register slot, and any write to a 32-bit register
  zero-extends it, so both LLP64 (Windows) and LP64 (Linux/macOS) targets read the value correctly.
  One binding set therefore serves all bundled 64-bit platforms.
- 32-bit targets are **not** supported: pointers and `size_t` are 4 bytes there, so every struct
  layout would differ. `pdfium-win-x86` is deliberately not bundled.
- Struct layouts keep the C ABI, with explicit padding; `LAYOUT.byteSize()` matches `sizeof` for
  every generated struct, which `PdfiumSupportTest` asserts.

## Continuous integration

- `.github/workflows/build.yml` runs `mvn -B verify` on `ubuntu-latest`, `windows-latest` and
  `macos-14` (arm64) with JDK 22, so each bundled platform is exercised against its own native
  library.
- `.github/workflows/check-pdfium-update.yml` compares `native/VERSION` with the latest
  [pdfium-binaries](https://github.com/bblanchon/pdfium-binaries) release every Monday and opens an
  issue when they diverge.

## Regenerating the bindings

The generated sources are checked in, but they can be regenerated from the headers:

```bash
cd pdfiumcore-bindings-generator
mvn exec:java -Dexec.args="--headers ../native/include --output ../pdfiumcore/src/main/java"
```

The generator records what it wrote in `pdfiumcore/src/main/java/pdfiumcore/.generated-files` and
deletes stale output on the next run. `--platform` accepts `win64`, `linux64` and `macos64`; the
emitted bindings are identical because `long` is carried as 64 bits on every 64-bit target (see
[ABI notes](#abi-notes)).

## Differences from the .NET version

- Bindings use the Foreign Function &amp; Memory API instead of P/Invoke, so there are no unsafety
  and no native shim to compile.
- C struct names are used as class names (`FS_MATRIX`, not CppSharp's `FS_MATRIX_`).
- `unsigned long` (`FPDF_DWORD`) is 32 bits on Windows (LLP64) and 64 bits on Linux/macOS (LP64).
  The bindings carry it as 64 bits, which is ABI-safe on every 64-bit target but means **32-bit
  targets (win-x86) are not supported**; see [ABI notes](#abi-notes).
- `FPDF_SYSFONTINFO` is emitted as `FPDF_SYSFONTINFO_`: Windows file systems are case insensitive
  and cannot hold both `FPDF_SYSFONTINFO.java` and the `fpdf_sysfontinfo` function class.
- A callback which returns a struct (e.g. the `FFI_GetLocalTime` slot of `FPDF_FORMFILLINFO`) cannot
  be backed by an FFM upcall stub; supply a native function pointer for those.
- Coverage matches the headers: all 478 exported functions, 21 structs, 9 enums and the
  `TimerCallback` typedef are bound (the .NET project's CppSharp output exposes 465 of them).

## Resources

- <https://pdfium.googlesource.com/pdfium/>
- <https://github.com/bblanchon/pdfium-binaries>
- <https://github.com/Dtronix/PDFiumCore>

## License

Apache-2.0, matching both this project and PDFium. The bundled PDFium binaries and headers are
distributed under PDFium's BSD-style license, see `native/PDFIUM-LICENSE`.
