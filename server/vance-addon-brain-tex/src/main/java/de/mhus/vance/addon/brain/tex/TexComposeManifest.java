package de.mhus.vance.addon.brain.tex;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Parsed representation of a {@code tex-compose} document — the build
 * manifest that drives {@link TexService#compile}.
 *
 * <p>YAML shape (minimal):
 * <pre>{@code
 * main: thesis.tex
 * files:
 *   - thesis.tex
 *   - references.bib
 *   - images/figure1.png
 * }</pre>
 *
 * <p>Full with cross-project references:
 * <pre>{@code
 * main: thesis.tex
 * engine: pdflatex      # pdflatex | xelatex | lualatex (default: pdflatex)
 * passes: auto           # auto = latexmk (default) | explicit list
 * output: thesis.pdf      # default: <main-basename>.pdf
 * outputPath: /reports/thesis.pdf  # where to import the PDF in the document tree
 * #   / = absolute path, no / = relative to compose dir (default: <compose-dir>/<output>)
 * files:
 *   # Same-project files (simple strings, relative to compose dir)
 *   - thesis.tex
 *   - references.bib
 *   # Cross-project references (maps with project/path/target)
 *   - project: tud-template
 *     path: tud-report.cls
 *     target: lib/tud-report.cls
 *   - project: tud-template
 *     path: images/tud-logo.png
 *     target: images/tud-logo.png
 * }</pre>
 *
 * <p>The {@code packages} field from the spec is intentionally absent —
 * runtime {@code tlmgr install} from user-declared lists is a security
 * risk. Everything needed must be in the Docker image.
 */
public record TexComposeManifest(
        String main,
        @Nullable String engine,
        @Nullable String passes,
        @Nullable String output,
        @Nullable String outputPath,
        List<FileEntry> files) {

    private static final String DEFAULT_ENGINE = "pdflatex";

    public String effectiveEngine() {
        return (engine == null || engine.isBlank()) ? DEFAULT_ENGINE : engine.trim();
    }

    public String effectiveOutput() {
        if (output != null && !output.isBlank()) {
            return output.trim();
        }
        String base = main.replaceAll("\\.tex$", "");
        return base + ".pdf";
    }

    /**
     * Resolves the document path where the compiled PDF should be imported.
     *
     * @param composeDir the directory of the tex-compose document
     *                    (e.g. "documents/tex1" for "documents/tex1/tex-compose.yaml")
     * @return the full document path for the PDF.
     *         If {@code outputPath} starts with {@code /}, it is treated as
     *         absolute (leading slash stripped by {@code normalizePath} later).
     *         If {@code outputPath} is set without {@code /}, it is relative
     *         to {@code composeDir}.
     *         If {@code outputPath} is absent, defaults to
     *         {@code <composeDir>/<effectiveOutput()>}.
     */
    public String resolvePdfDocPath(String composeDir) {
        if (outputPath != null && !outputPath.isBlank()) {
            String trimmed = outputPath.trim();
            if (trimmed.startsWith("/")) {
                return trimmed.substring(1);
            }
            return composeDir.isEmpty() ? trimmed : composeDir + "/" + trimmed;
        }
        String out = effectiveOutput();
        return composeDir.isEmpty() ? out : composeDir + "/" + out;
    }

    /**
     * A file entry in the {@code files} list of the manifest. Can be
     * either a same-project file (simple path string) or a cross-project
     * reference (file from another project, with an explicit target path).
     */
    public sealed interface FileEntry {

        /**
         * The path where this file should be placed in the compilation
         * workspace (relative to the workspace root).
         */
        String targetPath();

        /**
         * A file from the same project. The {@code path} is relative
         * to the compose document's directory.
         */
        record LocalFile(String path) implements FileEntry {
            @Override
            public String targetPath() {
                return path;
            }
        }

        /**
         * A file from a different project. The {@code project} field
         * identifies the source project, {@code path} is the document
         * path within that project, and {@code target} is where the
         * file should be placed in the compilation workspace.
         *
         * <p>Access control is enforced by {@code DocumentService.findByPath}
         * — if the caller lacks read access to the source project, the
         * transport will throw an exception.
         */
        record CrossProjectFile(String project, String path, String target) implements FileEntry {
            @Override
            public String targetPath() {
                return target;
            }
        }
    }
}
