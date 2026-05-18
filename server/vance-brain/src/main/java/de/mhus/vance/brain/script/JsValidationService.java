package de.mhus.vance.brain.script;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.io.IOAccess;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Parse-only JavaScript validator. Wraps the GraalJS parser without
 * evaluating any script body — used by Deep Thought's VALIDATING
 * phase and by the (planned) Script Cortex "Validate" button.
 *
 * <p>The check exercises just the parser: a successful return means
 * the source survives Source.newBuilder + Context.parse, i.e. it's
 * syntactically valid JavaScript that GraalJS would accept for eval.
 * Semantic issues (runtime errors, missing bindings, infinite loops)
 * are <em>not</em> caught here — that's the job of subsequent layers
 * (the script-header's @timeout, the dry-run probe, the LLM-review
 * phase).
 *
 * <p>Cheap to call repeatedly: shares the {@link Engine} bean and
 * builds a one-off Context per call with all execution privileges
 * disabled (no eval, no host access, no IO). The Context is closed
 * immediately after parse returns.
 *
 * <p>See {@code specification/script-engine.md} §3.5 for the
 * surrounding header-aware validation pipeline, and
 * {@code planning/deepthought-engine.md} for how the result feeds
 * the DRAFTING-recovery loop.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JsValidationService {

    private final Engine engine;

    /**
     * Parse {@code code} as JavaScript and report errors with
     * line/column information when available.
     *
     * @param code        the script source; null/blank → ok=false
     *                    with an "empty source" error
     * @param sourceName  identifier shown in error messages
     *                    (typically the on-disk path or skill-script
     *                    name); pass null/empty to use "{@code <validate>}"
     */
    public JsValidationResult validate(@Nullable String code, @Nullable String sourceName) {
        String name = sourceName == null || sourceName.isBlank()
                ? "<validate>" : sourceName;
        if (code == null || code.isBlank()) {
            return new JsValidationResult(false, List.of(
                    new JsValidationError(name, 0, 0,
                            "empty source — nothing to validate")));
        }
        Source source;
        try {
            source = Source.newBuilder("js", code, name).buildLiteral();
        } catch (RuntimeException e) {
            return new JsValidationResult(false, List.of(
                    new JsValidationError(name, 0, 0,
                            "Source construction failed: " + e.getMessage())));
        }
        try (Context ctx = Context.newBuilder("js")
                .engine(engine)
                .allowAllAccess(false)
                .allowIO(IOAccess.NONE)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .allowHostClassLoading(false)
                .allowHostClassLookup(n -> false)
                .build()) {
            ctx.parse(source);
            return new JsValidationResult(true, List.of());
        } catch (PolyglotException pe) {
            return new JsValidationResult(false,
                    Collections.singletonList(mapParseError(pe, name)));
        } catch (RuntimeException e) {
            log.warn("JsValidationService unexpected non-Polyglot error for '{}': {}",
                    name, e.toString());
            return new JsValidationResult(false, List.of(
                    new JsValidationError(name, 0, 0,
                            "Unexpected validation failure: " + e.getMessage())));
        }
    }

    private static JsValidationError mapParseError(PolyglotException pe, String name) {
        int line = 0;
        int col = 0;
        SourceSection section = pe.getSourceLocation();
        if (section != null && section.isAvailable()) {
            line = section.getStartLine();
            col = section.getStartColumn();
        }
        String msg = pe.getMessage();
        if (msg == null || msg.isBlank()) msg = pe.toString();
        return new JsValidationError(name, line, col, msg);
    }

    /**
     * One validation error. {@code line}/{@code column} are 1-based
     * when GraalJS supplies a SourceSection; both 0 when the parser
     * didn't pin a location (e.g. premature EOF in a malformed
     * source).
     */
    public record JsValidationError(
            String sourceName, int line, int column, String message) {
    }

    /**
     * Outcome of {@link #validate}. {@code errors} is unmodifiable;
     * empty when {@code ok=true}.
     */
    public record JsValidationResult(boolean ok, List<JsValidationError> errors) {
        public JsValidationResult {
            errors = errors == null ? List.of() : List.copyOf(new ArrayList<>(errors));
        }
    }
}
