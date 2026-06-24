package de.mhus.vance.brain.tools.tex;

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
 * <p>Full:
 * <pre>{@code
 * main: thesis.tex
 * engine: pdflatex      # pdflatex | xelatex | lualatex (default: pdflatex)
 * passes: auto           # auto = latexmk (default) | explicit list
 * output: thesis.pdf      # default: <main-basename>.pdf
 * files: [...]
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
        List<String> files) {

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
}
